#!/usr/bin/env python3
"""Python 沙箱访问守卫的对抗性回归测试(用宿主 CPython 直接跑)。

为什么单独放这:app/src/main/python/octopus_sandbox.py 的 _guard 用 PEP 578 审计钩子
(sys.addaudithook)拦 open/subprocess/exec/目录列举。这套逻辑是纯 CPython 层、与 Android
无关(Chaquopy 锁 CPython 3.11,审计事件名在 3.3–3.8 引入后未变,3.11/3.12 一致),但需要
真解释器执行 —— 无法在 JVM 单测(Robolectric/JUnit)里覆盖。故用宿主 python3 忠实验证:

    python3 scripts/verify_octopus_sandbox.py

唯一 Android 特有的是 Chaquopy 的 import 机制(标准库经 AssetManager 读,不发 "open"),
守卫已用 sys.path 快照白名单绕开,不误伤 import。退出码非 0 = 有用例失败。
"""
import os
import sys
import tempfile

# 定位仓库内的沙箱模块(本脚本在 <repo>/scripts/,模块在 <repo>/app/src/main/python/)
_HERE = os.path.dirname(os.path.abspath(__file__))
PY_DIR = os.path.join(_HERE, "..", "app", "src", "main", "python")
PY_DIR = os.path.abspath(PY_DIR)

_ALLOWED = os.path.realpath(tempfile.mkdtemp(prefix="octo_ws_"))
sys.path.insert(0, PY_DIR)
import octopus_sandbox as ob  # noqa: E402 —— import 即安装常驻审计钩子


class FakeHost:
    def getWorkspace(self):
        return _ALLOWED

    def readFile(self, p): return open(p).read()
    def writeFile(self, p, c): open(p, "w").write(c); return "ok"
    def listFiles(self, p): return os.listdir(p)
    def exists(self, p): return os.path.exists(p)
    def mkdir(self, p): os.makedirs(p, exist_ok=True); return "ok"
    def deleteFile(self, p): os.remove(p); return "ok"
    def fetch(self, u, o=None): return "{}"
    def callTool(self, n, p=None): return "ok"
    def md5(self, s): return "x"
    def sha256(self, s): return "x"
    def uuid(self): return "x"
    def nowMs(self): return 0


def run(code):
    # 篡改类用例会改 sys.modules['os']/os.path.abspath;每次跑完恢复,避免污染后续用例。
    saved_mod = sys.modules.get("os")
    saved_abspath = os.path.abspath
    saved_fspath = os.fspath
    try:
        return ob.run(code, FakeHost())
    finally:
        if saved_mod is not None:
            sys.modules["os"] = saved_mod
        os.path.abspath = saved_abspath
        os.fspath = saved_fspath


PKG = "com.octopus.mobile"
CASES = [
    # ── 逃逸利用:必须拦(输出含 sandbox 拒绝 + needle) ──
    ("read app-private MMKV via builtin open",
     "open('/data/data/%s/files/mmkv/mmkv.default','rb').read()" % PKG, "block", "access denied"),
    ("read app-private via os.open",
     "import os; os.open('/data/data/%s/x', os.O_RDONLY)" % PKG, "block", "access denied"),
    ("../ traversal back into /data",
     "open('/sdcard/Download/../../../data/data/%s/y','rb').read()" % PKG, "block", "access denied"),
    ("/proc/self/root symlink read",
     "open('/proc/self/root/data/data/%s/files/mmkv/mmkv.default','rb').read()" % PKG, "block", "access denied"),
    ("/proc/self/environ secret leak",
     "open('/proc/self/environ','rb').read()", "block", "access denied"),
    ("subprocess.Popen",
     "import subprocess; subprocess.Popen(['id'])", "block", "process/exec disabled"),
    ("os.system",
     "import os; os.system('id')", "block", "process/exec disabled"),
    ("write to app-private area",
     "open('/data/data/%s/shared_prefs/x.xml','w').write('x')" % PKG, "block", "write/modify denied"),
    ("os.remove app-private",
     "import os; os.remove('/data/data/%s/z')" % PKG, "block", "write/modify denied"),
    ("os.rename dst escape into /data",
     "import os\nopen('%s/payload','w').write('x')\nos.rename('%s/payload','/data/local/tmp/x')" % (_ALLOWED, _ALLOWED),
     "block", "write/modify denied"),
    ("os.listdir enumerate private dir",
     "import os; os.listdir('/data/data/%s')" % PKG, "block", "access denied"),
    ("os.scandir enumerate private dir",
     "import os; list(os.scandir('/data/data/%s/files'))" % PKG, "block", "access denied"),
    ("os.symlink (defeat read guard)",
     "import os; os.symlink('/data/data/%s/s','%s/link')" % (PKG, _ALLOWED), "block", "symlink"),
    # ── 篡改守卫:守卫不依赖任何用户可写状态,应免疫 ──
    ("tamper: null _host still blocks read",
     "import octopus_sandbox as _o; _o.__dict__['_host']=None\nopen('/data/data/%s/files/mmkv/mmkv.default','rb').read()" % PKG,
     "block", "access denied"),
    ("tamper: sys.modules['os']=FakeOS still blocks",
     "import sys\n"
     "class P:\n"
     " abspath=staticmethod(lambda p:'/tmp/harmless')\n"
     " dirname=staticmethod(lambda p:'/tmp')\n"
     "class FakeOS:\n"
     " path=P\n"
     " fspath=staticmethod(lambda p:p)\n"
     " O_WRONLY=1;O_RDWR=2;O_CREAT=64;O_APPEND=1024;O_TRUNC=512\n"
     " __file__='/tmp/x.py'\n"
     "sys.modules['os']=FakeOS\n"
     "open('/data/data/%s/files/mmkv/mmkv.default','rb').read()" % PKG,
     "block", "access denied"),
    ("tamper: monkeypatch os.path.abspath still blocks",
     "import os\nos.path.abspath=lambda p:'/tmp/harmless'\n"
     "open('/data/data/%s/x','rb').read()" % PKG, "block", "access denied"),
    # ── 正常用法:必须放行 ──
    ("ok: stdlib import + print",
     "import json,collections,itertools; print(json.dumps({'a':1}))", "ok", '{"a": 1}'),
    ("ok: workspace read/write",
     "p=WORKSPACE+'/f.txt'\nopen(p,'w').write('hi')\nprint(open(p).read())", "ok", "hi"),
    ("ok: pure compute",
     "print(sum(range(5)))", "ok", "10"),
    ("ok: list workspace dir",
     "import os\nopen(WORKSPACE+'/a','w').write('x')\nprint('LS' if 'a' in os.listdir(WORKSPACE) else 'NO')",
     "ok", "LS"),
]

passed = 0
for name, code, expect, needle in CASES:
    out = run(code)
    blocked = ("sandbox:" in out) or ("PermissionError" in out)
    if expect == "block":
        ok = blocked and (needle in out)
    else:
        ok = (not blocked) and (needle in out)
    print("[%s] %s" % ("PASS" if ok else "FAIL", name))
    if ok:
        passed += 1
    else:
        print("       expect=%s needle=%r got=%r" % (expect, needle, out[:300]))

print("\n%d/%d passed" % (passed, len(CASES)))
sys.exit(0 if passed == len(CASES) else 1)
