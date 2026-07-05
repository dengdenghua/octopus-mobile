# -*- coding: utf-8 -*-
"""Octopus Mobile Python 沙箱执行入口。

由 PythonSandbox.kt 通过 Chaquopy Java→Python 桥接调用:
    module.callAttr("run", code, host)

职责:
  1. 捕获 stdout / stderr(含 print 输出)
  2. 把宿主 host API 注入到用户代码的全局命名空间
  3. exec 用户代码;异常时返回完整 traceback
  4. 限制:CPython 无法被 JVM 中断,超时由 PythonSandbox 的 watchdog 线程兜底
     (超时后返回错误,Python 线程继续跑直到自然结束,单线程 executor 串行化防并发)
"""

import io
import os
import sys
import traceback


# ── 沙箱访问守卫(PEP 578 审计钩子)────────────────────────────────────────
# 用户代码拿到的是完整 CPython 运行时(含原生 open / os / __import__),裁剪 builtins
# 挡不住 ().__class__.__subclasses__() 之类的子类遍历逃逸。审计钩子在 C 层拦截真正的
# open/subprocess/exec 操作本身,无论用户代码怎么绕到那里都会触发,是 CPython 官方的
# 沙箱兜底机制。钩子安装一次且不可卸载(PEP 578 无移除 API)。
#
# 抗篡改:_guard 的策略(进程黑名单、私有根、运行时豁免)全部写成函数内字面量;它需要的
# 可调用与常量(abspath/fspath/O_* 位掩码/运行时白名单)在**模块加载时**(装钩子前)就快照
# 进 _guard 的默认参数,回调里只用这些绑定引用与字面量 —— 绝不在回调里从 sys.modules 或模块
# 属性动态取。否则 `sys.modules['os'] = FakeOS` 或 monkeypatch `os.path.abspath` 就能把回调里
# 的路径判定喂假、架空守卫(实测可行的一行绕过)。快照后这些向量全部失效。
#
# 边界模型:令牌/密钥/私有配置都在 app 私有区 /data 下 —— 守卫拦死对 /data(及 /proc、/sys)的
# 原生读/写/删/列举(仅放行 import 机制从 sys.path 读运行时文件),并禁止起进程/建链接。/data 之外
# (Download/Documents/工作空间/sdcard)的原生文件操作放行;「限 Download/Documents」的严格白名单
# 仍由 host 文件 API(read_file/write_file 等,过 ScriptSandbox.isSafePath)负责。
# 残余(inherent,超出「挡死 LLM 生成代码现实逃逸」目标,需独立进程/解释器才能根治):
#   · os.stat/lstat/readlink/getxattr 无 PEP 578 审计事件 → 元数据(大小/时间/软链目标)可泄露;
#   · 直连 _posixsubprocess.fork_exec / ctypes.CDLL(None) 等私有/底层入口起进程;
#   · 改写 _guard.__defaults__/__code__ 这类对函数对象本身的深度 introspection。
#   这些都是刻意构造的人类攻击者行为,不在 LLM 生成代码威胁模型内。


def _snapshot_read_allow(_os, _sys):
    # 模块加载时定格 Python 运行时的可读前缀(sys.path/前缀/标准库目录),供读守卫豁免 import。
    # 用快照而非回调期实时读 sys.path —— 防 `sys.path.append('/data/...')` 把私有区加进白名单。
    out = []
    for e in list(_sys.path):
        if e:
            out.append(_os.path.abspath(e))
    for e in (getattr(_sys, "prefix", ""), getattr(_sys, "base_prefix", ""),
              _os.path.dirname(_os.__file__)):
        if e:
            out.append(_os.path.abspath(e))
    return tuple(out)


def _guard(event, args,
           _abspath=os.path.abspath,
           _fspath=os.fspath,
           _wmask=(os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_APPEND | os.O_TRUNC),
           _allow=_snapshot_read_allow(os, sys)):
    # 1) 起进程 / 动态库 —— 永不允许(纯字面量,用户改不动)
    if (event in ("os.system", "subprocess.Popen", "os.fork", "os.forkpty",
                  "os.posix_spawn", "os.startfile", "pty.spawn",
                  "ctypes.dlopen", "ctypes.dlsym", "ctypes.call_function")
            or event.startswith("os.exec") or event.startswith("os.spawn")):
        raise PermissionError(
            "sandbox: process/exec disabled (use call_tool or the gated shell_exec tool)")
    # 2) 建软/硬链接 —— 禁(防用链接把 /data 私有文件映射到 /data 外再读)
    if event in ("os.symlink", "os.link"):
        raise PermissionError("sandbox: symlink/link disabled")

    is_open = (event == "open")
    is_mutate = event in ("os.remove", "os.rename", "os.replace", "os.rmdir",
                          "os.mkdir", "os.truncate", "os.chmod", "os.chown")
    # os.listdir/os.scandir 发独立审计事件(非 "open"),按「读」处理,拦对私有区的目录列举。
    is_enum = event in ("os.listdir", "os.scandir")
    if not (is_open or is_mutate or is_enum) or not args:
        return
    raw = args[0]
    if isinstance(raw, int):
        return  # 已是 fd,无路径可校验
    try:
        # abspath 纯字符串规整(含 ..)+ 拼 cwd,不触发文件系统/审计事件,避免递归。
        path = _abspath(_fspath(raw))
    except Exception:
        return

    def _under(p, roots):
        for r in roots:
            rr = r if r.endswith("/") else r + "/"
            if p == r or p.startswith(rr):
                return True
        return False

    # 敏感区:app 私有数据 /data,外加 /proc、/sys —— /proc/self/root、/proc/<pid>/cwd 等是
    # 指回文件系统根的符号链接,纯字符串前缀判断会把它们当 /proc 放行、内核却解析到 /data,
    # 是绕过读守卫的现实途径;/proc/self/environ、maps 也会漏私密,一并拦。
    sensitive = ("/data", "/proc", "/sys")

    # 要校验的路径:rename/replace 的目的地(args[1])也必须查,否则可把白名单文件改名进 /data。
    targets = [path]
    if event in ("os.rename", "os.replace") and len(args) > 1:
        try:
            targets.append(_abspath(_fspath(args[1])))
        except Exception:
            pass

    # 写模式判定(open 看 mode/flags;变更事件按写处理;列举按读处理)。
    if is_open:
        mode = args[1] if len(args) > 1 else None
        flags = args[2] if len(args) > 2 else None
        writing = (isinstance(mode, str) and any(c in mode for c in ("w", "a", "x", "+"))) or (
            isinstance(flags, int) and bool(flags & _wmask))
    else:
        writing = is_mutate

    for t in targets:
        if not _under(t, sensitive):
            continue  # 不在敏感区(Download/Documents/工作空间/sdcard 等)—— 放行
        if (not writing) and _under(t, _allow):
            continue  # 读/列举:放行 Python 运行时自身文件(标准库/扩展)
        verb = "write/modify" if writing else "access"
        raise PermissionError(
            "sandbox: " + verb + " denied (app-private/system area): " + t)


_hook_installed = False


def _install_guard():
    global _hook_installed
    if _hook_installed:
        return
    sys.addaudithook(_guard)
    _hook_installed = True


# 模块加载即装钩子(此后一直生效;读私有区/起进程等下限不依赖 _host)。
_install_guard()


def run(code, host=None):
    # type: (str, object) -> str
    """执行用户 Python 代码,返回捕获的输出(含 stderr/traceback)。"""
    out_buf = io.StringIO()
    err_buf = io.StringIO()
    old_out, old_err = sys.stdout, sys.stderr
    sys.stdout = out_buf
    sys.stderr = err_buf
    # 访问守卫 _guard 自模块加载即常驻生效,不依赖此处任何状态(策略全内联在 _guard 内)。
    # 用户代码的全局命名空间:注入宿主 API + 常用内建
    import json  # noqa: E402  — 供用户代码直接用
    import math  # noqa: E402
    import re  # noqa: E402
    import time  # noqa: E402
    import datetime as _dt  # noqa: E402

    g = {
        "__name__": "__main__",
        "__builtins__": __builtins__,
        "json": json,
        "math": math,
        "re": re,
        "os": os,
        "time": time,
        "datetime": _dt,
        "host": host,
    }
    # 注入工作空间常量 + chdir 进去,使 open("file.txt") 等相对路径默认落到工作空间目录
    # (与 JS 沙箱的 WORKSPACE 全局常量行为一致)。host=None 时退化为不 chdir。
    if host is not None:
        try:
            workspace = host.getWorkspace()
            g["WORKSPACE"] = workspace
            # 切到工作空间目录,让相对路径(open/os.listdir/Path)默认解析到这里。
            # 失败不致命:用户仍可用绝对路径或 WORKSPACE 拼接。
            try:
                os.chdir(workspace)
            except Exception:
                pass
        except Exception:
            pass
    # 便捷别名:让用户代码可直接调 readFile / fetch / callTool 等,无需 host. 前缀
    if host is not None:
        g["read_file"] = lambda p: host.readFile(p)
        g["write_file"] = lambda p, c: host.writeFile(p, c)
        g["list_files"] = lambda p: host.listFiles(p)
        g["exists"] = lambda p: host.exists(p)
        g["mkdir"] = lambda p: host.mkdir(p)
        g["delete_file"] = lambda p: host.deleteFile(p)
        g["fetch"] = lambda u, o=None: host.fetch(u, o)
        g["call_tool"] = lambda n, p=None: host.callTool(n, p)
        g["md5"] = lambda s: host.md5(s)
        g["sha256"] = lambda s: host.sha256(s)
        g["uuid"] = lambda: host.uuid()
        g["now_ms"] = lambda: host.nowMs()

    try:
        exec(code, g)
        output = out_buf.getvalue()
        err_output = err_buf.getvalue()
        if err_output:
            if output:
                return output + "\n--- stderr ---\n" + err_output
            return err_output
        return output
    except SystemExit:
        # 用户代码调 sys.exit() —— 视为正常结束,返回已捕获输出
        return out_buf.getvalue()
    except Exception:
        tb = traceback.format_exc()
        captured = out_buf.getvalue()
        if captured:
            return captured + "\n" + tb
        return tb
    finally:
        sys.stdout = old_out
        sys.stderr = old_err
