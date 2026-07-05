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
import sys
import traceback


def run(code, host=None):
    # type: (str, object) -> str
    """执行用户 Python 代码,返回捕获的输出(含 stderr/traceback)。"""
    out_buf = io.StringIO()
    err_buf = io.StringIO()
    old_out, old_err = sys.stdout, sys.stderr
    sys.stdout = out_buf
    sys.stderr = err_buf
    # 用户代码的全局命名空间:注入宿主 API + 常用内建
    import json  # noqa: E402  — 供用户代码直接用
    import math  # noqa: E402
    import re  # noqa: E402
    import os  # noqa: E402
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
