"""
Octopus 账号 + 计费 + 会员 + 大模型中转 —— 服务端骨架(FastAPI + SQLite)。

设计要点:
  - 后台统一持有平台的 **小米 MiMo API key**(只在服务端,App 永远看不到),
    用户走 /v1/chat/completions 中转 → 按 token 用量扣 **积分**(付费体系,默认路径)。
  - **会员(BYO 解锁)**:购买"会员"商品后当月可在 App 里接自己的大模型(BYO 走客户端直连、
    不经本中转、不扣积分)。本服务只负责"是否会员"这个权益位 + 账号 + 积分。
  - SMS / 支付 / 模型厂商 都做成可插拔的口子:默认 mock(本地可跑通整条链路),
    生产把对应 env 配上即可,App 侧零改动。

只依赖 fastapi(+ uvicorn 运行)+ httpx(中转转发,惰性 import)。SQLite 用标准库,省内存,
适配 1GB 小机。把它放在现有 nginx 后面(反代到 127.0.0.1:8081),App 的 AccountConfig.baseUrl
指向 https://你的域名 即可。
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import math
import os
import re
import secrets
import sqlite3
import threading
import time
from contextlib import closing
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, StreamingResponse

# ─────────────────────────── 配置(env 可覆盖) ───────────────────────────
DB_PATH = os.environ.get("OCTO_DB", os.path.join(os.path.dirname(__file__), "octo.db"))

# SMS:留空=mock(验证码固定 SMS_MOCK_CODE);生产接阿里云/腾讯云短信时实现 send_sms()
SMS_MOCK_CODE = os.environ.get("SMS_MOCK_CODE", "123456")
SMS_PROVIDER = os.environ.get("SMS_PROVIDER", "mock")  # mock | aliyun | ...

# 仅本地联调放开 mock 验证码(默认关)。否则 mock 固定码会被公网用来无限造号薅免费积分。
# 生产用 EMAIL_PROVIDER=smtp(真实邮件),此开关无影响;本地跑 mock 流程需显式设 ALLOW_MOCK_AUTH=1。
ALLOW_MOCK_AUTH = os.environ.get("ALLOW_MOCK_AUTH", "0").lower() in ("1", "true", "yes")
# 邮箱验证码:mock=固定 EMAIL_MOCK_CODE;生产 EMAIL_PROVIDER=smtp 并配 SMTP_*
EMAIL_PROVIDER = os.environ.get("EMAIL_PROVIDER", "mock")  # mock | smtp
EMAIL_MOCK_CODE = os.environ.get("EMAIL_MOCK_CODE", "123456")
SMTP_HOST = os.environ.get("SMTP_HOST", "")
SMTP_PORT = int(os.environ.get("SMTP_PORT", "587"))
SMTP_USER = os.environ.get("SMTP_USER", "")
SMTP_PASS = os.environ.get("SMTP_PASS", "")
SMTP_FROM = os.environ.get("SMTP_FROM", "")

# 支付:mock=下单后查单即视为已支付(本地跑通);生产接微信/支付宝,用 webhook 改单状态
PAYMENT_PROVIDER = os.environ.get("PAYMENT_PROVIDER", "mock")  # mock | wechat | alipay

# 平台大模型上游(key 只在服务端)。支持多上游:每个模型按 provider 路由到不同 base/key。
MIMO_API_KEY = os.environ.get("MIMO_API_KEY", "")
MIMO_BASE_URL = os.environ.get("MIMO_BASE_URL", "").rstrip("/")  # OpenAI 兼容 base, 例 https://.../v1
# 第二上游:Agnes(永久免费额度),作免费默认档。OpenAI 兼容。
AGNES_API_KEY = os.environ.get("AGNES_API_KEY", "")
AGNES_BASE_URL = os.environ.get("AGNES_BASE_URL", "").rstrip("/")
# provider 注册:模型 spec 里的 "provider" 决定走哪个上游(base + key)。
PROVIDERS = {
    "mimo": {"base_url": MIMO_BASE_URL, "api_key": MIMO_API_KEY},
    "agnes": {"base_url": AGNES_BASE_URL, "api_key": AGNES_API_KEY},
}
# 默认模型(请求未指定 model 或指定了目录外模型时回退);默认走免费的 agnes。
DEFAULT_MODEL = os.environ.get("DEFAULT_MODEL", "agnes-2.0-flash")

# 计费:多少积分/1k tokens(输入+输出合计),再乘模型 multiplier。
# 校准(不亏成本):CREDITS_PER_1K_TOKENS ≥ MiMo每1k_token的¥成本 ÷ (multiplier × 每积分售价¥)。
#   例:goods「100积分=¥9.90」→ 每积分≈¥0.099;若某模型 MiMo 报价 ¥0.004/1k、mult=1,
#   则需 ≥ 0.004/(1×0.099) ≈ 0.04。默认 1 偏保守(远高于成本、不会亏);拿到 MiMo 实际
#   报价后按上式把它下调到贴近成本即可(越小=用户每积分能用越多 token)。
CREDITS_PER_1K_TOKENS = float(os.environ.get("CREDITS_PER_1K_TOKENS", "1"))
# 单次输出 token 上限(成本 + 防跑飞双保险)。请求里更大的 max_tokens 会被压到此值;未指定也设成它。
# 思考型模型别设太小(否则正文被 reasoning 吃光),默认 8192。
MAX_OUTPUT_TOKENS = int(os.environ.get("MAX_OUTPUT_TOKENS", "8192"))
# 无限额度白名单(管理员/内部账号邮箱):走中转不预扣、不扣费、不被余额拦,仍记 usage_log(credits=0)。
# 逗号分隔、大小写不敏感。
UNLIMITED_EMAILS = {s.strip().lower() for s in os.environ.get("UNLIMITED_EMAILS", "").split(",") if s.strip()}
SIGNUP_BONUS = int(os.environ.get("SIGNUP_BONUS", "100"))
DAILY_BONUS = int(os.environ.get("DAILY_BONUS", "20"))
MEMBERSHIP_DAYS = int(os.environ.get("MEMBERSHIP_DAYS", "30"))
# 内测:每账号累计「免费积分」上限(注册礼+每日领+mock充值+邀请 都算,发满即停;真实付费不受限)
FREE_CAP = int(os.environ.get("FREE_CAP", "3000"))
# 邀请码(拉新返利):新人填码→新人得 REDEEMER、邀请人得 INVITER;均走免费上限
REFERRAL_REDEEMER_BONUS = int(os.environ.get("REFERRAL_REDEEMER_BONUS", "200"))
REFERRAL_INVITER_BONUS = int(os.environ.get("REFERRAL_INVITER_BONUS", "200"))

# 会话:JWT(HS256,无第三方依赖)。生产务必把 JWT_SECRET 换成随机长串。
JWT_SECRET = os.environ.get("JWT_SECRET", "dev-insecure-change-me")
JWT_EXPIRE_SECONDS = int(os.environ.get("JWT_EXPIRE_SECONDS", str(30 * 24 * 3600)))

# 管理后台:设了 ADMIN_TOKEN 才开放 /admin/api/*(未设=全部 503,默认安全)。务必用随机长串。
ADMIN_TOKEN = os.environ.get("ADMIN_TOKEN", "")
# 管理后台可选 IP 白名单(逗号分隔;空=不限,仅靠 ADMIN_TOKEN)。基于可信来源 IP 校验。
ADMIN_IP_ALLOWLIST = [s.strip() for s in os.environ.get("ADMIN_IP_ALLOWLIST", "").split(",") if s.strip()]
# app 与公网之间的可信反代跳数(nginx=1)。X-Forwarded-For 最左段客户端可伪造,真实客户端 IP
# 取自右数第 TRUSTED_PROXIES 个。设 0 = 无反代,直接用 socket IP(忽略可伪造的 XFF)。
TRUSTED_PROXIES = int(os.environ.get("TRUSTED_PROXIES", "1"))

# 商品目录(kind=membership 的购买会解锁当月 BYO)。priceFen=人民币分;priceUsdCents=美元分
# (英文区显示,约 = 人民币价 ÷ 汇率 × 1.5 的美区溢价,取整到干净价位)。
GOODS = [
    {"id": "m_month", "title": "会员月卡", "credits": 500, "bonusCredits": 0,
     "priceFen": 3900, "priceUsdCents": 899, "tag": "解锁自有模型", "kind": "membership"},
    {"id": "g_100", "title": "100 积分", "credits": 100, "bonusCredits": 0,
     "priceFen": 990, "priceUsdCents": 299, "tag": None, "kind": "credits"},
    {"id": "g_500", "title": "500 积分", "credits": 500, "bonusCredits": 50,
     "priceFen": 3990, "priceUsdCents": 899, "tag": "划算", "kind": "credits"},
    {"id": "g_1000", "title": "1000 积分", "credits": 1000, "bonusCredits": 200,
     "priceFen": 6900, "priceUsdCents": 1499, "tag": "超值", "kind": "credits"},
]
GOODS_BY_ID = {g["id"]: g for g in GOODS}

# 模型目录 = 用户可见的「两个档位」(display_name 是档位名,不暴露底层模型名)。
# 极速档:agnes(走对平台免费的上游=我们零成本),0.5×,快;高级档:mimo-pro,1.0×,慢但强。
# 两档照常按 multiplier 扣用户积分。可用 MODELS_JSON 覆盖。
_DEFAULT_MODELS = [
    {"id": "agnes-2.0-flash", "display_name": "极速", "tier": "fast", "multiplier": 0.5,
     "provider": "agnes", "recommended": True},
    {"id": "mimo-v2.5-pro", "display_name": "高级", "tier": "premium", "multiplier": 1.0,
     "provider": "mimo", "recommended": True},
]
# 模型 id -> 完整 spec(provider/multiplier/...);MODELS_JSON 里没写 provider 的默认归到 mimo(向后兼容)。
# 坏配置(非 list / item 缺 id / 解析失败)整体回退默认,不让服务起不来。
try:
    MODELS = json.loads(os.environ["MODELS_JSON"]) if os.environ.get("MODELS_JSON") else _DEFAULT_MODELS
    MODEL_SPEC = {m["id"]: {**m, "provider": m.get("provider", "mimo")}
                  for m in MODELS if isinstance(m, dict) and m.get("id")}
    if not MODEL_SPEC:
        raise ValueError("MODELS_JSON 无有效模型")
except Exception:  # noqa: BLE001 — 配置坏了就回退默认
    MODELS = _DEFAULT_MODELS
    MODEL_SPEC = {m["id"]: {**m, "provider": m.get("provider", "mimo")} for m in MODELS}

app = FastAPI(title="octopus-account-relay", version="0.2.0")


# ─────────────────────────── JWT(HS256,stdlib) ───────────────────────────
def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _b64url_decode(seg: str) -> bytes:
    return base64.urlsafe_b64decode(seg + "=" * (-len(seg) % 4))


def jwt_encode(claims: dict[str, Any], secret: str) -> str:
    header = _b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    body = _b64url(json.dumps(claims, separators=(",", ":")).encode())
    signing_input = f"{header}.{body}"
    sig = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return f"{signing_input}.{_b64url(sig)}"


def jwt_decode(token: str, secret: str) -> dict[str, Any] | None:
    try:
        header_seg, body_seg, sig_seg = token.split(".")
    except ValueError:
        return None
    signing_input = f"{header_seg}.{body_seg}"
    expected = _b64url(hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest())
    if not hmac.compare_digest(expected, sig_seg):
        return None
    try:
        claims = json.loads(_b64url_decode(body_seg))
    except Exception:  # noqa: BLE001
        return None
    if int(claims.get("exp", 0)) < int(time.time()):
        return None
    return claims


# ─────────────────────────── DB ───────────────────────────
def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=5000")  # 并发写等锁至多 5s,避免高并发下 SQLITE_BUSY
    return conn


def init_db() -> None:
    with closing(db()) as c:
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS users(
                user_id TEXT PRIMARY KEY, mobile TEXT UNIQUE, nickname TEXT,
                credits INTEGER NOT NULL DEFAULT 0,
                free_granted INTEGER NOT NULL DEFAULT 0,
                member_expire_at INTEGER NOT NULL DEFAULT 0,
                last_claim_day TEXT DEFAULT '', created_at INTEGER NOT NULL,
                invite_code TEXT, invited_by TEXT
            );
            CREATE TABLE IF NOT EXISTS sms_codes(
                mobile TEXT PRIMARY KEY, code TEXT NOT NULL, expire_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS email_codes(
                email TEXT PRIMARY KEY, code TEXT NOT NULL, expire_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS orders(
                order_no TEXT PRIMARY KEY, user_id TEXT NOT NULL, goods_id TEXT NOT NULL,
                amount_fen INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING',
                created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS usage_log(
                id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT, model TEXT,
                tokens_in INTEGER, tokens_out INTEGER, credits INTEGER, ts INTEGER
            );
            CREATE TABLE IF NOT EXISTS admin_log(
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, action TEXT,
                target_user TEXT, detail TEXT
            );
            """
        )
        # 迁移:给已存在的 users 表补 email 列(幂等)
        try:
            c.execute("ALTER TABLE users ADD COLUMN email TEXT")
        except sqlite3.OperationalError:
            pass  # 列已存在
        try:
            c.execute("ALTER TABLE users ADD COLUMN free_granted INTEGER NOT NULL DEFAULT 0")
        except sqlite3.OperationalError:
            pass  # 列已存在
        for _col in ("invite_code TEXT", "invited_by TEXT",
                     "banned INTEGER NOT NULL DEFAULT 0"):
            try:
                c.execute(f"ALTER TABLE users ADD COLUMN {_col}")
            except sqlite3.OperationalError:
                pass  # 列已存在
        c.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_invite "
            "ON users(invite_code) WHERE invite_code IS NOT NULL"
        )
        c.commit()


@app.on_event("startup")
def _startup() -> None:
    init_db()


# ─────────────────────────── auth ───────────────────────────
def now_ms() -> int:
    return int(time.time() * 1000)


# ── 简单进程内滑窗限流(单 worker 够用) ──
_rl_lock = threading.Lock()
_rl: dict[str, list[float]] = {}


def client_ip(request: Request) -> str:
    """真实客户端 IP。X-Forwarded-For 最左段由客户端可伪造,故按可信代理跳数从右取:
    nginx 用 proxy_add_x_forwarded_for 把真实 socket IP 追加到 XFF 末尾,右数第
    TRUSTED_PROXIES 个才不可伪造。无 XFF 或跳数不足→回退连接 socket IP。"""
    sock = request.client.host if request.client else "?"
    if TRUSTED_PROXIES <= 0:
        return sock
    xff = request.headers.get("x-forwarded-for")
    if not xff:
        return sock
    parts = [p.strip() for p in xff.split(",") if p.strip()]
    return parts[-TRUSTED_PROXIES] if len(parts) >= TRUSTED_PROXIES else sock


def rate_limit(key: str, limit: int, window_s: float) -> None:
    """同一 key 在 window_s 内最多 limit 次,超限抛 429。"""
    now = time.time()
    cutoff = now - window_s
    with _rl_lock:
        q = _rl.setdefault(key, [])
        drop = 0
        for t in q:
            if t >= cutoff:
                break
            drop += 1
        if drop:
            del q[:drop]
        if len(q) >= limit:
            raise HTTPException(status_code=429, detail="请求过于频繁,请稍后再试")
        q.append(now)
        if len(_rl) > 5000:  # 防字典无限膨胀:清掉「整桶已过期」的 key(比最长窗口还旧 → 任何 key 都已失效)
            stale = now - 3600
            for k in [k for k, v in _rl.items() if not v or v[-1] < stale]:
                _rl.pop(k, None)


def actor(authorization: str = Header(default="")) -> sqlite3.Row:
    """Verify the bearer JWT and load the user row, or 401."""
    token = authorization[7:] if authorization.lower().startswith("bearer ") else ""
    claims = jwt_decode(token, JWT_SECRET) if token else None
    if not claims:
        raise HTTPException(status_code=401, detail="invalid or expired token")
    with closing(db()) as c:
        row = c.execute("SELECT * FROM users WHERE user_id = ?", (claims.get("sub"),)).fetchone()
    if row is None:
        raise HTTPException(status_code=401, detail="unknown user")
    if row["banned"]:  # 封禁:令牌即刻作废,中转/账号接口全部拒绝
        raise HTTPException(status_code=403, detail="账号已被封禁")
    return row


def _user(c: sqlite3.Connection, user_id: str) -> sqlite3.Row:
    return c.execute("SELECT * FROM users WHERE user_id = ?", (user_id,)).fetchone()


def _grant_free(c: sqlite3.Connection, user_id: str, want: int) -> int:
    """发放免费积分,受每账号累计上限 FREE_CAP 约束。返回实际发放数。"""
    row = c.execute("SELECT free_granted FROM users WHERE user_id = ?", (user_id,)).fetchone()
    used = int((row["free_granted"] if row else 0) or 0)
    grant = max(0, min(want, FREE_CAP - used))
    if grant:
        c.execute(
            "UPDATE users SET credits = credits + ?, free_granted = free_granted + ? WHERE user_id = ?",
            (grant, grant, user_id),
        )
    return grant


_INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"  # 去掉易混的 0/O/1/I/L


def _gen_invite_code(c: sqlite3.Connection) -> str:
    for _ in range(12):
        code = "".join(secrets.choice(_INVITE_ALPHABET) for _ in range(6))
        if not c.execute("SELECT 1 FROM users WHERE invite_code = ?", (code,)).fetchone():
            return code
    return "".join(secrets.choice(_INVITE_ALPHABET) for _ in range(9))


# ─────────────────────────── SMS (pluggable) ───────────────────────────
def send_sms(mobile: str, code: str) -> None:
    """Real provider goes here (aliyun/tencent). Mock just no-ops (code返回给前端/log)."""
    if SMS_PROVIDER == "mock":
        print(f"[sms-mock] {mobile} -> {code}")
        return
    raise HTTPException(status_code=500, detail=f"SMS provider '{SMS_PROVIDER}' not wired yet")


def send_email(email: str, code: str) -> None:
    """Mock 打印;生产 EMAIL_PROVIDER=smtp 走 SMTP。"""
    if EMAIL_PROVIDER == "mock":
        print(f"[email-mock] {email} -> {code}")
        return
    if EMAIL_PROVIDER == "smtp":
        import smtplib
        from email.mime.text import MIMEText

        msg = MIMEText(f"你的登录验证码是 {code},5 分钟内有效。", _charset="utf-8")
        msg["Subject"] = "登录验证码"
        msg["From"] = SMTP_FROM or SMTP_USER
        msg["To"] = email
        sender = msg["From"]
        if SMTP_PORT == 465:  # 隐式 SSL(网易 126/163 用 465)
            with smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, timeout=20) as s:
                if SMTP_USER:
                    s.login(SMTP_USER, SMTP_PASS)
                s.sendmail(sender, [email], msg.as_string())
        else:  # STARTTLS(587 等)
            with smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=20) as s:
                s.starttls()
                if SMTP_USER:
                    s.login(SMTP_USER, SMTP_PASS)
                s.sendmail(sender, [email], msg.as_string())
        return
    raise HTTPException(status_code=500, detail=f"email provider '{EMAIL_PROVIDER}' not wired yet")


# ─────────────────────────── endpoints: auth ───────────────────────────
@app.post("/auth/sms/send")
def sms_send(body: dict[str, Any]) -> dict[str, Any]:
    if SMS_PROVIDER == "mock":  # mock 固定码会被滥用造号;接真短信前禁用该端点
        raise HTTPException(status_code=403, detail="短信登录未开放")
    mobile = str(body.get("mobile", "")).strip()
    if len(mobile) != 11 or not mobile.isdigit():
        raise HTTPException(status_code=400, detail="invalid mobile")
    code = SMS_MOCK_CODE if SMS_PROVIDER == "mock" else f"{secrets.randbelow(1000000):06d}"
    with closing(db()) as c:
        c.execute(
            "INSERT INTO sms_codes(mobile, code, expire_at) VALUES(?,?,?) "
            "ON CONFLICT(mobile) DO UPDATE SET code=excluded.code, expire_at=excluded.expire_at",
            (mobile, code, now_ms() + 300_000),
        )
        c.commit()
    send_sms(mobile, code)
    out = {"ok": True, "ttlSeconds": 300}
    if SMS_PROVIDER == "mock":
        out["devCode"] = code
    return out


@app.post("/auth/sms/login")
def sms_login(body: dict[str, Any]) -> dict[str, Any]:
    if SMS_PROVIDER == "mock":
        raise HTTPException(status_code=403, detail="短信登录未开放")
    mobile = str(body.get("mobile", "")).strip()
    code = str(body.get("code", "")).strip()
    with closing(db()) as c:
        rec = c.execute("SELECT * FROM sms_codes WHERE mobile = ?", (mobile,)).fetchone()
        ok = rec is not None and rec["code"] == code and rec["expire_at"] >= now_ms()
        if not ok:
            raise HTTPException(status_code=400, detail="验证码错误或已过期")
        user = c.execute("SELECT * FROM users WHERE mobile = ?", (mobile,)).fetchone()
        if user is not None and user["banned"]:  # 封号用户不再签发新 token
            raise HTTPException(status_code=403, detail="账号已被封禁")
        is_new = user is None
        if is_new:
            uid = "u_" + secrets.token_hex(8)
            c.execute(
                "INSERT INTO users(user_id, mobile, nickname, credits, created_at, invite_code) "
                "VALUES(?,?,?,?,?,?)",
                (uid, mobile, f"用户{mobile[-4:]}", 0, now_ms(), _gen_invite_code(c)),
            )
            _grant_free(c, uid, SIGNUP_BONUS)
        else:
            uid = user["user_id"]
        c.execute("DELETE FROM sms_codes WHERE mobile = ?", (mobile,))
        c.commit()
    token = jwt_encode(
        {"sub": uid, "mobile": mobile, "iat": int(time.time()),
         "exp": int(time.time()) + JWT_EXPIRE_SECONDS},
        JWT_SECRET,
    )
    return {"token": token, "userId": uid, "mobile": mobile, "isNewUser": is_new,
            "nickname": f"用户{mobile[-4:]}"}


_EMAIL_RE = re.compile(r"^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$")


def _valid_email(s: str) -> bool:
    # 严格白名单字符:从源头挡住换行/控制符/HTML 字符进入 users.email(否则污染 CSV 导出与后台 UI)
    return bool(_EMAIL_RE.match(s)) and len(s) <= 254


@app.post("/auth/email/send")
def email_send(body: dict[str, Any], request: Request) -> dict[str, Any]:
    if EMAIL_PROVIDER == "mock" and not ALLOW_MOCK_AUTH:  # mock 固定码会被滥用造号,默认禁用
        raise HTTPException(status_code=403, detail="邮箱登录未开放")
    email = str(body.get("email", "")).strip().lower()
    if not _valid_email(email):
        raise HTTPException(status_code=400, detail="invalid email")
    rate_limit(f"email_send:{email}", 1, 60)                    # 同邮箱 60s 1 条
    rate_limit(f"email_send_ip:{client_ip(request)}", 10, 3600)  # 同 IP 每小时 10 条
    code = EMAIL_MOCK_CODE if EMAIL_PROVIDER == "mock" else f"{secrets.randbelow(1000000):06d}"
    with closing(db()) as c:
        c.execute(
            "INSERT INTO email_codes(email, code, expire_at) VALUES(?,?,?) "
            "ON CONFLICT(email) DO UPDATE SET code=excluded.code, expire_at=excluded.expire_at",
            (email, code, now_ms() + 300_000),
        )
        c.commit()
    send_email(email, code)
    out = {"ok": True, "ttlSeconds": 300}
    if EMAIL_PROVIDER == "mock":
        out["devCode"] = code
    return out


@app.post("/auth/email/login")
def email_login(body: dict[str, Any], request: Request) -> dict[str, Any]:
    if EMAIL_PROVIDER == "mock" and not ALLOW_MOCK_AUTH:  # 与 email_send 对称:默认禁用 mock 登录
        raise HTTPException(status_code=403, detail="邮箱登录未开放")
    email = str(body.get("email", "")).strip().lower()
    code = str(body.get("code", "")).strip()
    rate_limit(f"email_login:{email}", 10, 600)                  # 同邮箱 10 分钟最多 10 次(防撞码)
    rate_limit(f"email_login_ip:{client_ip(request)}", 30, 600)
    with closing(db()) as c:
        rec = c.execute("SELECT * FROM email_codes WHERE email = ?", (email,)).fetchone()
        ok = rec is not None and rec["code"] == code and rec["expire_at"] >= now_ms()
        if not ok:
            raise HTTPException(status_code=400, detail="验证码错误或已过期")
        user = c.execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()
        if user is not None and user["banned"]:  # 封号用户不再签发新 token
            raise HTTPException(status_code=403, detail="账号已被封禁")
        is_new = user is None
        nick = email.split("@")[0]
        if is_new:
            uid = "u_" + secrets.token_hex(8)
            c.execute(
                "INSERT INTO users(user_id, email, nickname, credits, created_at, invite_code) "
                "VALUES(?,?,?,?,?,?)",
                (uid, email, nick, 0, now_ms(), _gen_invite_code(c)),
            )
            _grant_free(c, uid, SIGNUP_BONUS)
        else:
            uid = user["user_id"]
            nick = user["nickname"] or nick
        c.execute("DELETE FROM email_codes WHERE email = ?", (email,))
        c.commit()
    token = jwt_encode(
        {"sub": uid, "email": email, "iat": int(time.time()),
         "exp": int(time.time()) + JWT_EXPIRE_SECONDS},
        JWT_SECRET,
    )
    return {"token": token, "userId": uid, "mobile": "", "email": email,
            "isNewUser": is_new, "nickname": nick}


# ─────────────────────────── endpoints: account ───────────────────────────
@app.get("/account/profile")
def profile(u: sqlite3.Row = Depends(actor)) -> dict[str, Any]:
    return {"userId": u["user_id"], "mobile": u["mobile"], "nickname": u["nickname"], "avatar": None}


@app.get("/account/balance")
def balance(u: sqlite3.Row = Depends(actor)) -> dict[str, Any]:
    active = u["member_expire_at"] > now_ms()
    return {"credits": u["credits"], "membershipActive": active,
            "membershipExpireAt": u["member_expire_at"] if active else 0}


@app.post("/account/daily-claim")
def daily_claim(u: sqlite3.Row = Depends(actor)) -> dict[str, Any]:
    today = time.strftime("%Y-%m-%d", time.gmtime())
    with closing(db()) as c:
        # 原子占位:今天没领过才放行(防并发重复领)
        guard = c.execute(
            "UPDATE users SET last_claim_day = ? WHERE user_id = ? AND last_claim_day <> ?",
            (today, u["user_id"], today),
        )
        granted = _grant_free(c, u["user_id"], DAILY_BONUS) if guard.rowcount > 0 else 0
        c.commit()
        bal = _user(c, u["user_id"])["credits"]
    return {"claimed": granted > 0, "credits": granted, "balance": bal}


# ─────────────────────────── endpoints: invite(拉新返利) ───────────────────────────
@app.get("/invite/info")
def invite_info(u: sqlite3.Row = Depends(actor)) -> dict[str, Any]:
    code = u["invite_code"]
    with closing(db()) as c:
        if not code:  # 老用户懒生成
            code = _gen_invite_code(c)
            c.execute("UPDATE users SET invite_code = ? WHERE user_id = ?", (code, u["user_id"]))
            c.commit()
        invited = c.execute(
            "SELECT COUNT(*) AS n FROM users WHERE invited_by = ?", (u["user_id"],)
        ).fetchone()["n"]
    return {"code": code, "invitedCount": int(invited), "redeemed": bool(u["invited_by"]),
            "redeemerBonus": REFERRAL_REDEEMER_BONUS, "inviterBonus": REFERRAL_INVITER_BONUS}


@app.post("/invite/redeem")
def invite_redeem(body: dict[str, Any], u: sqlite3.Row = Depends(actor)) -> dict[str, Any]:
    rate_limit(f"invite_redeem:{u['user_id']}", 5, 600)
    code = str(body.get("code", "")).strip().upper()
    if not code:
        raise HTTPException(status_code=400, detail="请输入邀请码")
    with closing(db()) as c:
        if _user(c, u["user_id"])["invited_by"]:
            raise HTTPException(status_code=400, detail="你已使用过邀请码")
        inviter = c.execute("SELECT * FROM users WHERE invite_code = ?", (code,)).fetchone()
        if inviter is None:
            raise HTTPException(status_code=400, detail="邀请码无效")
        if inviter["user_id"] == u["user_id"]:
            raise HTTPException(status_code=400, detail="不能使用自己的邀请码")
        # 原子绑定:仅当还没被邀请过才生效(防并发重复)
        upd = c.execute(
            "UPDATE users SET invited_by = ? WHERE user_id = ? AND invited_by IS NULL",
            (inviter["user_id"], u["user_id"]),
        )
        if upd.rowcount == 0:
            raise HTTPException(status_code=400, detail="你已使用过邀请码")
        got = _grant_free(c, u["user_id"], REFERRAL_REDEEMER_BONUS)        # 新人
        _grant_free(c, inviter["user_id"], REFERRAL_INVITER_BONUS)         # 邀请人
        c.commit()
        bal = _user(c, u["user_id"])["credits"]
    return {"ok": True, "credits": got, "balance": bal}


# ─────────────────────────── endpoints: billing ───────────────────────────
@app.get("/billing/goods")
def goods(u: sqlite3.Row = Depends(actor)) -> dict[str, Any]:
    return {"items": GOODS}


@app.post("/billing/orders")
def create_order(body: dict[str, Any], u: sqlite3.Row = Depends(actor)) -> dict[str, Any]:
    g = GOODS_BY_ID.get(str(body.get("goodsId", "")))
    if g is None:
        raise HTTPException(status_code=400, detail="套餐不存在")
    order_no = "O" + secrets.token_hex(10)
    with closing(db()) as c:
        c.execute(
            "INSERT INTO orders(order_no, user_id, goods_id, amount_fen, status, created_at) "
            "VALUES(?,?,?,?,?,?)",
            (order_no, u["user_id"], g["id"], g["priceFen"], "PENDING", now_ms()),
        )
        c.commit()
    # mock: 无收银台,客户端直接查单即支付成功;生产返回微信/支付宝 H5 收银台 url。
    pay_url = None if PAYMENT_PROVIDER == "mock" else _create_cashier(order_no, g)
    return {"orderNo": order_no, "payUrl": pay_url, "amountFen": g["priceFen"],
            "credits": g["credits"] + g["bonusCredits"]}


def _create_cashier(order_no: str, goods: dict[str, Any]) -> str:
    raise HTTPException(status_code=500, detail=f"payment '{PAYMENT_PROVIDER}' not wired yet")


def _settle(c: sqlite3.Connection, order: sqlite3.Row) -> int:
    """Mark order PAID and grant credits / membership. Returns granted credits."""
    g = GOODS_BY_ID[order["goods_id"]]
    want = g["credits"] + g["bonusCredits"]
    if PAYMENT_PROVIDER == "mock":
        granted = _grant_free(c, order["user_id"], want)  # 内测免费充值:受每账号上限约束
    else:
        c.execute("UPDATE users SET credits = credits + ? WHERE user_id = ?", (want, order["user_id"]))
        granted = want  # 真实付费:不受免费上限
    if g["kind"] == "membership":  # 会员到期(解锁 BYO,用自己 key,不耗平台成本)
        cur = _user(c, order["user_id"])
        base = max(cur["member_expire_at"], now_ms())
        c.execute("UPDATE users SET member_expire_at = ? WHERE user_id = ?",
                  (base + MEMBERSHIP_DAYS * 24 * 3600 * 1000, order["user_id"]))
    c.execute("UPDATE orders SET status='PAID' WHERE order_no=?", (order["order_no"],))
    return granted


@app.get("/billing/orders/{order_no}")
def query_order(order_no: str, u: sqlite3.Row = Depends(actor)) -> dict[str, Any]:
    with closing(db()) as c:
        o = c.execute("SELECT * FROM orders WHERE order_no=? AND user_id=?",
                      (order_no, u["user_id"])).fetchone()
        if o is None:
            raise HTTPException(status_code=404, detail="订单不存在")
        granted = 0
        status = o["status"]
        if status == "PENDING" and PAYMENT_PROVIDER == "mock":
            granted = _settle(c, o)
            status = "PAID"
            c.commit()
        elif status == "PAID":
            g = GOODS_BY_ID[o["goods_id"]]
            granted = g["credits"] + g["bonusCredits"]
    return {"orderNo": order_no, "status": status, "credits": granted}


# 生产支付回调(微信/支付宝)在这里验签 → _settle → 200。骨架先留桩。
@app.post("/billing/webhook/{provider}")
async def payment_webhook(provider: str, request: Request) -> JSONResponse:
    raise HTTPException(status_code=501, detail=f"webhook for '{provider}' not implemented")


# ─────────────────── 模型目录 + 中转(多上游路由,按模型倍率扣积分,0=免费) ───────────────────
def _resolve_model(requested: str | None) -> tuple[str, dict[str, Any]]:
    """请求的 model → (规整后 model_id, spec)。目录外/未指定 → 回退 DEFAULT_MODEL(防拿 key 乱调)。"""
    model = requested or DEFAULT_MODEL
    spec = MODEL_SPEC.get(model)
    if spec is None:
        model = DEFAULT_MODEL
        spec = MODEL_SPEC.get(DEFAULT_MODEL) or {"multiplier": 1.0, "provider": "mimo"}
    return model, spec


def _reserve_credits(user_id: str, hold: int) -> bool:
    """原子预扣 hold 积分(余额够才扣)。并发请求各自预扣 → 一旦余额覆盖不了下一个请求的
    worst-case 预扣就被拒,杜绝「调用前判余额>0、扣费在响应后」导致的并发超额消费。"""
    if hold <= 0:
        return True
    with closing(db()) as c:
        cur = c.execute(
            "UPDATE users SET credits = credits - ? WHERE user_id = ? AND credits >= ?",
            (hold, user_id, hold),
        )
        c.commit()
        return cur.rowcount > 0


def _reconcile_usage(user_id: str, model: str, tin: int, tout: int, mult: float, hold: int,
                     free: bool = False) -> int:
    """按实际用量结算:退还(预扣 hold − 实际 cost),多退少补(clamp≥0),记 usage_log。返回实际扣费。
    tin=tout=0(上游报错/异常)时 actual=0 → 全额退还预扣。free=True(无限额度白名单)→ actual 恒 0,仍记 usage。"""
    actual = 0 if free else (
        max(1, math.ceil((tin + tout) / 1000 * CREDITS_PER_1K_TOKENS * mult)) if (tin + tout) else 0
    )
    refund = hold - actual
    with closing(db()) as c:
        if refund:
            c.execute("UPDATE users SET credits = MAX(0, credits + ?) WHERE user_id = ?", (refund, user_id))
        if (tin + tout) > 0:  # 有真实用量就记一条(便于看调用量/成本)
            c.execute(
                "INSERT INTO usage_log(user_id, model, tokens_in, tokens_out, credits, ts) "
                "VALUES(?,?,?,?,?,?)",
                (user_id, model, tin, tout, actual, now_ms()),
            )
        c.commit()
    return actual


@app.get("/v1/models")
def list_models() -> dict[str, Any]:
    """公开模型目录(带每模型积分倍率 + 是否免费),供 App 渲染。"""
    return {
        "object": "list",
        "data": [
            {"id": m["id"], "object": "model", "owned_by": "octopus",
             "display_name": m.get("display_name", m["id"]),
             "tier": m.get("tier", ""),
             "multiplier": float(m.get("multiplier", 1.0)),
             "free": float(m.get("multiplier", 1.0)) <= 0,
             "recommended": bool(m.get("recommended", False))}
            for m in MODELS
        ],
    }


@app.post("/v1/chat/completions")
async def chat_completions(body: dict[str, Any], request: Request, u: sqlite3.Row = Depends(actor)) -> Any:
    rate_limit(f"chat:{u['user_id']}", 60, 60)  # 每用户每分钟 60 次

    # ── 模型 → 上游路由:目录外回退 DEFAULT_MODEL;按 spec.provider 选 base/key ──
    model, spec = _resolve_model(body.get("model"))
    mult = float(spec.get("multiplier", 1.0))
    prov = PROVIDERS.get(spec.get("provider", "mimo"), {})
    base, key = prov.get("base_url") or "", prov.get("api_key") or ""
    if not base or not key:
        raise HTTPException(status_code=503, detail=f"模型 {model} 的上游未配置")
    url = f"{base}/chat/completions"
    headers = {"Authorization": f"Bearer {key}", "Content-Type": "application/json"}
    user_id = u["user_id"]
    unlimited = (u["email"] or "").strip().lower() in UNLIMITED_EMAILS  # 白名单:不预扣、不扣费、不被余额拦

    import httpx  # 惰性 import

    # ── max_tokens 上限:请求里更大的值压到 MAX_OUTPUT_TOKENS,未指定也设成它(成本/跑飞双保险) ──
    req_max = body.get("max_tokens")
    max_out = min(req_max, MAX_OUTPUT_TOKENS) if isinstance(req_max, int) and req_max > 0 else MAX_OUTPUT_TOKENS

    # ── 预扣(pre-auth reserve):按 worst-case(prompt 估算 + max_out)原子预留积分,不足→402;
    # 并发各自预扣,余额覆盖不了就被拒 → 杜绝超支;真实 usage 出来后结算多退少补。白名单 hold=0、不扣费。
    prompt_est = sum(
        len(str(m.get("content", ""))) for m in (body.get("messages") or []) if isinstance(m, dict)
    ) // 4
    hold = 0 if unlimited else max(1, math.ceil((prompt_est + max_out) / 1000 * CREDITS_PER_1K_TOKENS * mult))
    if hold and not _reserve_credits(user_id, hold):
        raise HTTPException(status_code=402, detail="积分不足,请充值")

    def _settle(tin: int, tout: int) -> None:  # 统一结算入口,白名单(free)恒不扣费
        _reconcile_usage(user_id, model, tin, tout, mult, hold, free=unlimited)

    # ── 非流式 ──
    if not bool(body.get("stream")):
        payload = dict(body)
        payload["model"] = model
        payload["max_tokens"] = max_out
        try:
            async with httpx.AsyncClient(timeout=120) as client:
                resp = await client.post(url, headers=headers, json=payload)
        except Exception:  # noqa: BLE001 — 上游请求异常,全额退还预扣
            _settle(0, 0)
            raise HTTPException(status_code=502, detail="上游模型请求失败")
        data = resp.json() if resp.headers.get("content-type", "").startswith("application/json") else {}
        if resp.status_code >= 400:
            _settle(0, 0)  # 上游报错,全额退
            # 不回显上游原始错误体(可能含请求数据/内部细节),只给通用错误 + 状态码
            return JSONResponse(status_code=resp.status_code,
                                content={"error": {"message": "upstream error", "status": resp.status_code}})
        usage = (data or {}).get("usage", {}) or {}
        if usage:
            _settle(int(usage.get("prompt_tokens", 0) or 0), int(usage.get("completion_tokens", 0) or 0))
        else:  # 2xx 但拿不到 usage(非 JSON/缺字段)→ 按 prompt 估算兜底,避免白嫖一次成功响应
            _settle(prompt_est, 0)
        return JSONResponse(content=data)

    # ── 流式 SSE 透传:边转发边抓 usage;客户端中途断开也按已生成内容兜底结算 ──
    payload = dict(body)
    payload["model"] = model
    payload["max_tokens"] = max_out
    payload["stream"] = True
    opts = payload.get("stream_options")
    payload["stream_options"] = {**opts, "include_usage": True} if isinstance(opts, dict) else {"include_usage": True}

    async def _gen() -> Any:
        captured: dict[str, Any] = {}
        out_chars = 0
        settled = False
        try:
            async with httpx.AsyncClient(timeout=None) as client:
                async with client.stream("POST", url, headers=headers, json=payload) as r:
                    if r.status_code >= 400:
                        _settle(0, 0)  # 上游报错,全额退
                        settled = True
                        # 不回显上游原始错误体,只给通用错误 + 状态码
                        yield (b"data: " + json.dumps(
                            {"error": {"message": "upstream error", "status": r.status_code}}).encode() + b"\n\n")
                        yield b"data: [DONE]\n\n"
                        return
                    async for line in r.aiter_lines():
                        yield (line + "\n").encode("utf-8")
                        if line.startswith("data:"):
                            chunk = line[5:].strip()
                            if chunk and chunk != "[DONE]":
                                try:
                                    obj = json.loads(chunk)
                                    if isinstance(obj, dict) and obj.get("usage"):
                                        captured["usage"] = obj["usage"]
                                    for ch in (obj.get("choices") or []):
                                        out_chars += len(str((ch.get("delta") or {}).get("content") or ""))
                                except Exception:  # noqa: BLE001
                                    pass
        finally:
            if not settled:
                try:
                    usage = captured.get("usage")
                    if usage:  # 有真实 usage:按真实结算
                        _settle(int(usage.get("prompt_tokens", 0) or 0), int(usage.get("completion_tokens", 0) or 0))
                    elif out_chars > 0:  # 收到过内容但没拿到 usage(多半中途断开)→ 按 prompt+已收字符估算
                        _settle(prompt_est, out_chars // 4)
                    else:  # 没接通/零字节(连接异常等)→ 全额退,与非流式语义一致,不误扣
                        _settle(0, 0)
                except Exception as e:  # noqa: BLE001 — 对账失败别让流清理崩溃;落日志供人工对账
                    print(f"[reconcile-fail] user={user_id} hold={hold}: {type(e).__name__}: {e}")

    return StreamingResponse(
        _gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "Connection": "keep-alive", "X-Accel-Buffering": "no"},
    )


# ─────────────────────────── 管理后台(/admin) ───────────────────────────
def admin_guard(request: Request, x_admin_token: str = Header(default="")) -> bool:
    """管理鉴权:未设 ADMIN_TOKEN → 503(默认关闭);口令错 → 401。
    可信来源 IP 限流防爆破 + 可选 IP 白名单 + 常数时间(字节)比较。"""
    if not ADMIN_TOKEN:
        raise HTTPException(status_code=503, detail="管理后台未启用(未设 ADMIN_TOKEN)")
    ip = client_ip(request)
    rate_limit(f"admin:{ip}", 120, 60)  # 同 IP 每分钟 120 次(基于不可伪造的可信 IP)
    if ADMIN_IP_ALLOWLIST and ip not in ADMIN_IP_ALLOWLIST:
        raise HTTPException(status_code=403, detail="forbidden")
    # encode 成字节再比:compare_digest 对非 ASCII str 会抛 TypeError(→500),字节则恒定时间且不抛
    if not hmac.compare_digest(x_admin_token.encode("utf-8"), ADMIN_TOKEN.encode("utf-8")):
        raise HTTPException(status_code=401, detail="管理口令错误")
    return True


def _admin_log(c: sqlite3.Connection, action: str, target_user: str, detail: str) -> None:
    c.execute("INSERT INTO admin_log(ts, action, target_user, detail) VALUES(?,?,?,?)",
              (now_ms(), action, target_user, detail))


@app.get("/admin", response_class=HTMLResponse)
@app.get("/admin/", response_class=HTMLResponse)
def admin_page() -> HTMLResponse:
    if not ADMIN_TOKEN:  # 未启用时不暴露后台控制台的存在
        raise HTTPException(status_code=404, detail="not found")
    return HTMLResponse(ADMIN_HTML, headers={
        "X-Robots-Tag": "noindex",
        # connect-src 'self' 即便后台被 XSS 也无法把数据外传到他源;inline 脚本/样式需放行
        "Content-Security-Policy": ("default-src 'self'; img-src 'self' data:; "
                                    "style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; "
                                    "connect-src 'self'; base-uri 'none'; form-action 'none'"),
    })


@app.get("/admin/api/stats")
def admin_stats(_: bool = Depends(admin_guard)) -> dict[str, Any]:
    with closing(db()) as c:
        u = c.execute(
            "SELECT COUNT(*) n, COALESCE(SUM(credits),0) cr, COALESCE(SUM(free_granted),0) fg, "
            "SUM(CASE WHEN member_expire_at > ? THEN 1 ELSE 0 END) mem, "
            "SUM(CASE WHEN banned THEN 1 ELSE 0 END) ban, "
            "SUM(CASE WHEN invited_by IS NOT NULL THEN 1 ELSE 0 END) inv FROM users",
            (now_ms(),),
        ).fetchone()
        o = c.execute(
            "SELECT COUNT(*) n, SUM(CASE WHEN status='PAID' THEN 1 ELSE 0 END) paid, "
            "COALESCE(SUM(CASE WHEN status='PAID' THEN amount_fen ELSE 0 END),0) rev FROM orders"
        ).fetchone()
        g = c.execute(
            "SELECT COALESCE(SUM(tokens_in),0) tin, COALESCE(SUM(tokens_out),0) tout, "
            "COALESCE(SUM(credits),0) spent, COUNT(*) calls FROM usage_log"
        ).fetchone()
    return {
        "users": u["n"], "totalCredits": u["cr"], "freeGranted": u["fg"],
        "members": u["mem"] or 0, "banned": u["ban"] or 0, "invited": u["inv"] or 0,
        "orders": o["n"], "paidOrders": o["paid"] or 0, "revenueFen": o["rev"],
        "tokensIn": g["tin"], "tokensOut": g["tout"], "creditsSpent": g["spent"], "calls": g["calls"],
    }


@app.get("/admin/api/users")
def admin_users(_: bool = Depends(admin_guard), q: str = "", limit: int = 50, offset: int = 0) -> dict[str, Any]:
    limit = max(1, min(200, limit))
    offset = max(0, offset)
    where, params = "", []
    if q.strip():
        like = f"%{q.strip()}%"
        where = "WHERE user_id LIKE ? OR email LIKE ? OR mobile LIKE ? OR invite_code LIKE ?"
        params = [like, like, like, like]
    with closing(db()) as c:
        total = c.execute(f"SELECT COUNT(*) n FROM users {where}", params).fetchone()["n"]
        rows = c.execute(
            f"SELECT user_id, email, mobile, nickname, credits, free_granted, member_expire_at, "
            f"banned, invite_code, invited_by, created_at FROM users {where} "
            f"ORDER BY created_at DESC LIMIT ? OFFSET ?", params + [limit, offset]
        ).fetchall()
    now = now_ms()
    return {"total": total, "items": [
        {"userId": r["user_id"], "email": r["email"], "mobile": r["mobile"], "nickname": r["nickname"],
         "credits": r["credits"], "freeGranted": r["free_granted"],
         "memberActive": r["member_expire_at"] > now, "memberExpireAt": r["member_expire_at"],
         "banned": bool(r["banned"]), "inviteCode": r["invite_code"], "invitedBy": r["invited_by"],
         "createdAt": r["created_at"]} for r in rows]}


@app.post("/admin/api/users/{uid}/credits")
def admin_adjust_credits(uid: str, body: dict[str, Any], _: bool = Depends(admin_guard)) -> dict[str, Any]:
    try:
        delta = int(body.get("delta"))
    except (TypeError, ValueError):
        raise HTTPException(status_code=400, detail="delta 必须是整数")
    if delta == 0 or abs(delta) > 10_000_000:
        raise HTTPException(status_code=400, detail="delta 超出范围")
    reason = str(body.get("reason", ""))[:200]
    with closing(db()) as c:
        cur = _user(c, uid)
        if cur is None:
            raise HTTPException(status_code=404, detail="用户不存在")
        before = cur["credits"]
        c.execute("UPDATE users SET credits = MAX(0, credits + ?) WHERE user_id = ?", (delta, uid))
        bal = _user(c, uid)["credits"]
        # 审计记真实生效量(负向调整会被 MAX(0,…) 截断,applied 可能 != 请求 delta)
        _admin_log(c, "credits", uid, f"req_delta={delta} applied={bal - before} reason={reason} {before}->{bal}")
        c.commit()
    return {"ok": True, "balance": bal}


@app.post("/admin/api/users/{uid}/membership")
def admin_membership(uid: str, body: dict[str, Any], _: bool = Depends(admin_guard)) -> dict[str, Any]:
    try:
        days = int(body.get("days"))
    except (TypeError, ValueError):
        raise HTTPException(status_code=400, detail="days 必须是整数")
    if abs(days) > 3650:
        raise HTTPException(status_code=400, detail="days 超出范围")
    reason = str(body.get("reason", ""))[:200]
    with closing(db()) as c:
        if _user(c, uid) is None:
            raise HTTPException(status_code=404, detail="用户不存在")
        # 原子:在「max(现有到期, now) + days」上 clamp>=0,一条 SQL 完成,避免读改写竞态
        c.execute(
            "UPDATE users SET member_expire_at = MAX(0, MAX(member_expire_at, ?) + ?) WHERE user_id = ?",
            (now_ms(), days * 24 * 3600 * 1000, uid),
        )
        new_exp = _user(c, uid)["member_expire_at"]
        _admin_log(c, "membership", uid, f"days={days} reason={reason} -> exp={new_exp}")
        c.commit()
    return {"ok": True, "memberExpireAt": new_exp, "memberActive": new_exp > now_ms()}


@app.post("/admin/api/users/{uid}/ban")
def admin_ban(uid: str, body: dict[str, Any], _: bool = Depends(admin_guard)) -> dict[str, Any]:
    if not isinstance(body.get("banned"), bool):  # 必须显式 true/false,防漏字段静默解封
        raise HTTPException(status_code=400, detail="banned 必须是 true 或 false")
    banned = 1 if body["banned"] else 0
    reason = str(body.get("reason", ""))[:200]
    with closing(db()) as c:
        if _user(c, uid) is None:
            raise HTTPException(status_code=404, detail="用户不存在")
        c.execute("UPDATE users SET banned = ? WHERE user_id = ?", (banned, uid))
        _admin_log(c, "ban", uid, f"banned={banned} reason={reason}")
        c.commit()
    return {"ok": True, "banned": bool(banned)}


@app.get("/admin/api/usage")
def admin_usage(_: bool = Depends(admin_guard), uid: str = "", limit: int = 100) -> dict[str, Any]:
    limit = max(1, min(500, limit))
    where, params = "", []
    if uid.strip():
        where = "WHERE g.user_id = ?"
        params = [uid.strip()]
    with closing(db()) as c:
        rows = c.execute(
            f"SELECT g.id, g.user_id, g.model, g.tokens_in, g.tokens_out, g.credits, g.ts, "
            f"u.email, u.mobile FROM usage_log g LEFT JOIN users u ON u.user_id = g.user_id "
            f"{where} ORDER BY g.id DESC LIMIT ?", params + [limit]
        ).fetchall()
    return {"items": [dict(r) for r in rows]}


def _csv_cell(v: Any) -> str:
    s = "" if v is None else str(v)
    if s and s[0] in ("=", "+", "-", "@"):  # 防 CSV 公式注入
        s = "'" + s
    if any(ch in s for ch in (",", '"', "\n", "\r")):
        s = '"' + s.replace('"', '""') + '"'
    return s


@app.get("/admin/api/usage.csv")
def admin_usage_csv(request: Request, _: bool = Depends(admin_guard)) -> PlainTextResponse:
    cols = ["id", "user_id", "email", "mobile", "model", "tokens_in", "tokens_out", "credits", "ts"]
    with closing(db()) as c:
        rows = c.execute(
            "SELECT g.id, g.user_id, u.email, u.mobile, g.model, g.tokens_in, g.tokens_out, "
            "g.credits, g.ts FROM usage_log g LEFT JOIN users u ON u.user_id = g.user_id "
            "ORDER BY g.id DESC"
        ).fetchall()
        _admin_log(c, "export_usage_csv", "*", f"rows={len(rows)} ip={client_ip(request)}")  # PII 导出留痕
        c.commit()
    lines = [",".join(cols)] + [",".join(_csv_cell(r[k]) for k in cols) for r in rows]
    return PlainTextResponse("\n".join(lines), media_type="text/csv",
                             headers={"Content-Disposition": "attachment; filename=usage.csv"})


@app.get("/admin/api/orders")
def admin_orders(_: bool = Depends(admin_guard), limit: int = 100) -> dict[str, Any]:
    limit = max(1, min(500, limit))
    with closing(db()) as c:
        rows = c.execute(
            "SELECT o.order_no, o.user_id, u.email, u.mobile, o.goods_id, o.amount_fen, o.status, "
            "o.created_at FROM orders o LEFT JOIN users u ON u.user_id = o.user_id "
            "ORDER BY o.created_at DESC LIMIT ?", (limit,)
        ).fetchall()
    return {"items": [dict(r) for r in rows]}


@app.get("/admin/api/logs")
def admin_logs(_: bool = Depends(admin_guard), limit: int = 100) -> dict[str, Any]:
    limit = max(1, min(500, limit))
    with closing(db()) as c:
        rows = c.execute("SELECT id, ts, action, target_user, detail FROM admin_log "
                         "ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
    return {"items": [dict(r) for r in rows]}


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok"}


# ─────────────────────────── 管理后台前端(单页,无依赖) ───────────────────────────
ADMIN_HTML = r"""<!doctype html>
<html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Octopus 管理后台</title>
<style>
:root{--bg:#0f1115;--card:#171a21;--line:#262b36;--fg:#e6e9ef;--mut:#8b93a7;--brand:#6c5ce7;--ok:#21c08b;--warn:#e0a106;--bad:#e0533d}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 -apple-system,Segoe UI,Roboto,"PingFang SC",sans-serif}
a{color:var(--brand)}.wrap{max-width:1180px;margin:0 auto;padding:20px}
.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
button{background:var(--brand);color:#fff;border:0;border-radius:8px;padding:7px 12px;cursor:pointer;font-size:13px}
button.ghost{background:transparent;border:1px solid var(--line);color:var(--fg)}
button.sm{padding:4px 9px;font-size:12px}
input{background:#0b0d11;border:1px solid var(--line);color:var(--fg);border-radius:8px;padding:8px 10px;font-size:14px}
h1{font-size:18px;margin:0}.mut{color:var(--mut)}.mono{font-family:ui-monospace,Menlo,monospace}
.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px;margin:16px 0}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px}
.card .k{color:var(--mut);font-size:12px}.card .v{font-size:22px;font-weight:700;margin-top:4px}
.tabs{display:flex;gap:6px;margin:18px 0 10px}.tabs button{background:transparent;border:1px solid var(--line);color:var(--mut)}
.tabs button.on{background:var(--brand);color:#fff;border-color:var(--brand)}
table{width:100%;border-collapse:collapse;font-size:13px}th,td{text-align:left;padding:9px 8px;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--mut);font-weight:600}tr:hover td{background:#12151c}
.pill{display:inline-block;padding:1px 8px;border-radius:99px;font-size:11px}
.pill.ok{background:rgba(33,192,139,.15);color:var(--ok)}.pill.no{background:#20242e;color:var(--mut)}
.pill.bad{background:rgba(224,83,61,.15);color:var(--bad)}
#login{max-width:360px;margin:12vh auto;text-align:center}
#login .card{padding:24px}#login input{width:100%;margin:12px 0}#login button{width:100%}
.err{color:var(--bad);margin-top:8px;min-height:18px}.hide{display:none}
.actbar button{margin-right:6px;margin-bottom:4px}
</style></head><body>

<div id="login">
  <div class="card">
    <h1>Octopus 管理后台</h1>
    <p class="mut">输入管理口令(服务器 .env 的 ADMIN_TOKEN)</p>
    <input id="tokIn" type="password" placeholder="管理口令" autocomplete="off">
    <button onclick="doLogin()">进入</button>
    <div class="err" id="loginErr"></div>
  </div>
</div>

<div id="app" class="wrap hide">
  <div class="row" style="justify-content:space-between">
    <h1>Octopus 管理后台</h1>
    <div class="row"><button class="ghost sm" onclick="refresh()">刷新</button>
      <button class="ghost sm" onclick="logout()">退出</button></div>
  </div>
  <div class="cards" id="stats"></div>
  <div class="tabs">
    <button class="on" data-tab="users" onclick="tab('users')">用户</button>
    <button data-tab="usage" onclick="tab('usage')">用量</button>
    <button data-tab="orders" onclick="tab('orders')">订单</button>
    <button data-tab="logs" onclick="tab('logs')">操作审计</button>
  </div>
  <div id="bar" class="row" style="margin-bottom:10px"></div>
  <div id="view"></div>
</div>

<script>
const SS="octo_admin"; let curTab="users";
const $=s=>document.querySelector(s);
const tok=()=>sessionStorage.getItem(SS)||"";
const esc=s=>String(s==null?"":s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const dt=ms=>ms?new Date(Number(ms)).toLocaleString():"";
const money=fen=>"¥"+(Number(fen||0)/100).toFixed(2);
async function api(path,opts){
  opts=opts||{}; opts.headers=Object.assign({"X-Admin-Token":tok()},opts.headers||{});
  const r=await fetch(path,opts);
  if(r.status===401||r.status===503){logout(await detail(r));throw new Error("auth");}
  if(!r.ok) throw new Error(await detail(r));
  const ct=r.headers.get("content-type")||""; return ct.includes("json")?r.json():r.text();
}
async function detail(r){try{return (await r.json()).detail||("HTTP "+r.status)}catch(e){return "HTTP "+r.status}}
function doLogin(){const v=$("#tokIn").value.trim();if(!v)return;sessionStorage.setItem(SS,v);
  $("#loginErr").textContent="";api("/admin/api/stats").then(()=>{enter()}).catch(e=>{$("#loginErr").textContent=e.message;});}
function enter(){$("#login").classList.add("hide");$("#app").classList.remove("hide");refresh();}
function logout(msg){sessionStorage.removeItem(SS);$("#app").classList.add("hide");$("#login").classList.remove("hide");
  if(msg)$("#loginErr").textContent=msg;}
function refresh(){loadStats();tab(curTab);}
function tab(t){curTab=t;document.querySelectorAll(".tabs button").forEach(b=>b.classList.toggle("on",b.dataset.tab===t));
  $("#bar").innerHTML=""; ({users:loadUsers,usage:loadUsage,orders:loadOrders,logs:loadLogs}[t])();}

async function loadStats(){
  try{const s=await api("/admin/api/stats");
  const cards=[["用户",s.users],["总积分余额",s.totalCredits],["免费已发",s.freeGranted],
    ["有效会员",s.members],["封禁",s.banned],["邀请兑换",s.invited],
    ["订单(已付)",s.orders+" / "+s.paidOrders],["收入",money(s.revenueFen)],
    ["调用次数",s.calls],["消耗积分",s.creditsSpent],["输入tok",s.tokensIn],["输出tok",s.tokensOut]];
  $("#stats").innerHTML=cards.map(([k,v])=>`<div class="card"><div class="k">${k}</div><div class="v">${esc(v)}</div></div>`).join("");
  }catch(e){if(e.message!=="auth")$("#stats").innerHTML=`<div class="card">加载失败:${esc(e.message)}</div>`;}
}

let usersListenerBound=false;
async function loadUsers(q){
  $("#bar").innerHTML=`<input id="uq" placeholder="搜索 邮箱/手机/uid/邀请码" value="${esc(q||"")}" style="width:280px">
    <button class="sm" onclick="loadUsers($('#uq').value)">搜索</button>`;
  $("#uq").addEventListener("keydown",e=>{if(e.key==="Enter")loadUsers(e.target.value);});
  try{const d=await api("/admin/api/users?limit=200&q="+encodeURIComponent(q||""));
  const rows=d.items.map(u=>`<tr data-uid="${esc(u.userId)}">
    <td><div>${esc(u.email||u.mobile||"-")}</div><div class="mut mono" style="font-size:11px">${esc(u.userId)}</div></td>
    <td><b>${u.credits}</b><div class="mut" style="font-size:11px">免:${u.freeGranted}</div></td>
    <td>${u.memberActive?`<span class="pill ok">会员</span><div class="mut" style="font-size:11px">${dt(u.memberExpireAt)}</div>`:'<span class="pill no">非会员</span>'}</td>
    <td>${u.banned?'<span class="pill bad">封禁</span>':'<span class="pill ok">正常</span>'}</td>
    <td class="mono">${esc(u.inviteCode||"-")}${u.invitedBy?`<div class="mut" style="font-size:11px">←${esc(u.invitedBy)}</div>`:""}</td>
    <td class="mut" style="font-size:12px">${dt(u.createdAt)}</td>
    <td class="actbar">
      <button class="sm" data-act="cr">积分±</button>
      <button class="sm ghost" data-act="mem">会员</button>
      <button class="sm ghost" data-act="ban">${u.banned?"解封":"封禁"}</button>
    </td></tr>`).join("");
  $("#view").innerHTML=`<div class="mut" style="margin-bottom:6px">共 ${d.total} 人</div>
    <table><thead><tr><th>账号</th><th>积分</th><th>会员</th><th>状态</th><th>邀请</th><th>注册</th><th>操作</th></tr></thead><tbody>${rows}</tbody></table>`;
  if(!usersListenerBound){usersListenerBound=true;
    $("#view").addEventListener("click",onUserAction);}
  $("#view").onclick=onUserAction;
  }catch(e){if(e.message!=="auth")$("#view").innerHTML=esc(e.message);}
}
async function onUserAction(ev){
  const btn=ev.target.closest("button[data-act]"); if(!btn)return;
  const uid=btn.closest("tr").dataset.uid; const act=btn.dataset.act;
  try{
    if(act==="cr"){const d=prompt("加/减积分(正数=加,负数=减),例如 +500 或 -100");if(d==null)return;
      const delta=parseInt(d,10);if(!delta){alert("请输入非零整数");return;}
      const reason=prompt("备注(可空)")||"";
      const r=await api(`/admin/api/users/${encodeURIComponent(uid)}/credits`,{method:"POST",
        headers:{"Content-Type":"application/json"},body:JSON.stringify({delta,reason})});
      alert("新余额:"+r.balance);}
    else if(act==="mem"){const d=prompt("会员天数(正=延长,负=减少),例如 30 或 -30");if(d==null)return;
      const days=parseInt(d,10);if(isNaN(days)){alert("请输入整数");return;}
      const reason=prompt("备注(可空)")||"";
      const r=await api(`/admin/api/users/${encodeURIComponent(uid)}/membership`,{method:"POST",
        headers:{"Content-Type":"application/json"},body:JSON.stringify({days,reason})});
      alert(r.memberActive?("会员至 "+dt(r.memberExpireAt)):"已设为非会员");}
    else if(act==="ban"){const isBan=btn.textContent==="封禁";
      if(!confirm(isBan?"确认封禁该账号?其令牌将立即失效":"确认解封?"))return;
      const reason=prompt("备注(可空)")||"";
      await api(`/admin/api/users/${encodeURIComponent(uid)}/ban`,{method:"POST",
        headers:{"Content-Type":"application/json"},body:JSON.stringify({banned:isBan,reason})});}
    loadStats();loadUsers($("#uq")?$("#uq").value:"");
  }catch(e){if(e.message!=="auth")alert("失败:"+e.message);}
}

async function loadUsage(){
  $("#bar").innerHTML=`<button class="sm" onclick="dlCsv()">导出 CSV</button>`;
  try{const d=await api("/admin/api/usage?limit=300");
  $("#view").innerHTML=`<table><thead><tr><th>时间</th><th>账号</th><th>模型</th><th>输入</th><th>输出</th><th>扣分</th></tr></thead><tbody>${
    d.items.map(r=>`<tr><td class="mut" style="font-size:12px">${dt(r.ts)}</td>
      <td>${esc(r.email||r.mobile||r.user_id)}</td><td class="mono">${esc(r.model)}</td>
      <td>${r.tokens_in}</td><td>${r.tokens_out}</td><td><b>${r.credits}</b></td></tr>`).join("")||
      '<tr><td colspan=6 class="mut">暂无用量</td></tr>'}</tbody></table>`;
  }catch(e){if(e.message!=="auth")$("#view").innerHTML=esc(e.message);}
}
async function dlCsv(){try{const txt=await api("/admin/api/usage.csv");
  const blob=new Blob([txt],{type:"text/csv"});const a=document.createElement("a");
  a.href=URL.createObjectURL(blob);a.download="usage.csv";a.click();URL.revokeObjectURL(a.href);
  }catch(e){if(e.message!=="auth")alert("导出失败:"+e.message);}}

async function loadOrders(){
  try{const d=await api("/admin/api/orders?limit=200");
  $("#view").innerHTML=`<table><thead><tr><th>时间</th><th>订单号</th><th>账号</th><th>商品</th><th>金额</th><th>状态</th></tr></thead><tbody>${
    d.items.map(o=>`<tr><td class="mut" style="font-size:12px">${dt(o.created_at)}</td>
      <td class="mono" style="font-size:12px">${esc(o.order_no)}</td><td>${esc(o.email||o.mobile||o.user_id)}</td>
      <td>${esc(o.goods_id)}</td><td>${money(o.amount_fen)}</td>
      <td>${o.status==="PAID"?'<span class="pill ok">已付</span>':'<span class="pill no">'+esc(o.status)+'</span>'}</td></tr>`).join("")||
      '<tr><td colspan=6 class="mut">暂无订单</td></tr>'}</tbody></table>`;
  }catch(e){if(e.message!=="auth")$("#view").innerHTML=esc(e.message);}
}

async function loadLogs(){
  try{const d=await api("/admin/api/logs?limit=300");
  $("#view").innerHTML=`<table><thead><tr><th>时间</th><th>操作</th><th>目标</th><th>详情</th></tr></thead><tbody>${
    d.items.map(l=>`<tr><td class="mut" style="font-size:12px">${dt(l.ts)}</td><td class="mono">${esc(l.action)}</td>
      <td class="mono" style="font-size:12px">${esc(l.target_user)}</td><td>${esc(l.detail)}</td></tr>`).join("")||
      '<tr><td colspan=4 class="mut">暂无操作记录</td></tr>'}</tbody></table>`;
  }catch(e){if(e.message!=="auth")$("#view").innerHTML=esc(e.message);}
}

if(tok())api("/admin/api/stats").then(enter).catch(()=>logout());
$("#tokIn").addEventListener("keydown",e=>{if(e.key==="Enter")doLogin();});
</script></body></html>"""
