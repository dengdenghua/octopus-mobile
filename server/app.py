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
import secrets
import sqlite3
import time
from contextlib import closing
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse

# ─────────────────────────── 配置(env 可覆盖) ───────────────────────────
DB_PATH = os.environ.get("OCTO_DB", os.path.join(os.path.dirname(__file__), "octo.db"))

# SMS:留空=mock(验证码固定 SMS_MOCK_CODE);生产接阿里云/腾讯云短信时实现 send_sms()
SMS_MOCK_CODE = os.environ.get("SMS_MOCK_CODE", "123456")
SMS_PROVIDER = os.environ.get("SMS_PROVIDER", "mock")  # mock | aliyun | ...

# 支付:mock=下单后查单即视为已支付(本地跑通);生产接微信/支付宝,用 webhook 改单状态
PAYMENT_PROVIDER = os.environ.get("PAYMENT_PROVIDER", "mock")  # mock | wechat | alipay

# 平台大模型(MiMo)——只在服务端持有
MIMO_API_KEY = os.environ.get("MIMO_API_KEY", "")
MIMO_BASE_URL = os.environ.get("MIMO_BASE_URL", "").rstrip("/")  # OpenAI 兼容 base, 例 https://.../v1
MIMO_DEFAULT_MODEL = os.environ.get("MIMO_DEFAULT_MODEL", "mimo-v2-flash")

# 计费:多少积分/1k tokens(prompt+completion 合计)。可按模型细化,这里给个统一近似。
CREDITS_PER_1K_TOKENS = float(os.environ.get("CREDITS_PER_1K_TOKENS", "1"))
SIGNUP_BONUS = int(os.environ.get("SIGNUP_BONUS", "100"))
DAILY_BONUS = int(os.environ.get("DAILY_BONUS", "20"))
MEMBERSHIP_DAYS = int(os.environ.get("MEMBERSHIP_DAYS", "30"))

# 会话:JWT(HS256,无第三方依赖)。生产务必把 JWT_SECRET 换成随机长串。
JWT_SECRET = os.environ.get("JWT_SECRET", "dev-insecure-change-me")
JWT_EXPIRE_SECONDS = int(os.environ.get("JWT_EXPIRE_SECONDS", str(30 * 24 * 3600)))

# 商品目录(kind=membership 的购买会解锁当月 BYO)
GOODS = [
    {"id": "m_month", "title": "会员月卡", "credits": 500, "bonusCredits": 0,
     "priceFen": 1900, "tag": "解锁自有模型", "kind": "membership"},
    {"id": "g_100", "title": "100 积分", "credits": 100, "bonusCredits": 0,
     "priceFen": 990, "tag": None, "kind": "credits"},
    {"id": "g_500", "title": "500 积分", "credits": 500, "bonusCredits": 50,
     "priceFen": 3990, "tag": "划算", "kind": "credits"},
    {"id": "g_1000", "title": "1000 积分", "credits": 1000, "bonusCredits": 200,
     "priceFen": 6900, "tag": "超值", "kind": "credits"},
]
GOODS_BY_ID = {g["id"]: g for g in GOODS}

# 模型目录 + 每模型积分倍率(参考 Molili 的 multiplier 定价)。可用 MODELS_JSON 覆盖。
_DEFAULT_MODELS = [
    {"id": "mimo-v2.5", "display_name": "MiMo 2.5", "multiplier": 0.5, "recommended": True},
    {"id": "mimo-v2.5-pro", "display_name": "MiMo 2.5 Pro", "multiplier": 1.0, "recommended": True},
]
try:
    MODELS = json.loads(os.environ["MODELS_JSON"]) if os.environ.get("MODELS_JSON") else _DEFAULT_MODELS
except Exception:  # noqa: BLE001 — 配置坏了就用默认
    MODELS = _DEFAULT_MODELS
MODEL_MULT = {m["id"]: float(m.get("multiplier", 1.0)) for m in MODELS}

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
    return conn


def init_db() -> None:
    with closing(db()) as c:
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS users(
                user_id TEXT PRIMARY KEY, mobile TEXT UNIQUE, nickname TEXT,
                credits INTEGER NOT NULL DEFAULT 0,
                member_expire_at INTEGER NOT NULL DEFAULT 0,
                last_claim_day TEXT DEFAULT '', created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sms_codes(
                mobile TEXT PRIMARY KEY, code TEXT NOT NULL, expire_at INTEGER NOT NULL
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
            """
        )
        c.commit()


@app.on_event("startup")
def _startup() -> None:
    init_db()


# ─────────────────────────── auth ───────────────────────────
def now_ms() -> int:
    return int(time.time() * 1000)


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
    return row


def _user(c: sqlite3.Connection, user_id: str) -> sqlite3.Row:
    return c.execute("SELECT * FROM users WHERE user_id = ?", (user_id,)).fetchone()


# ─────────────────────────── SMS (pluggable) ───────────────────────────
def send_sms(mobile: str, code: str) -> None:
    """Real provider goes here (aliyun/tencent). Mock just no-ops (code返回给前端/log)."""
    if SMS_PROVIDER == "mock":
        print(f"[sms-mock] {mobile} -> {code}")
        return
    raise HTTPException(status_code=500, detail=f"SMS provider '{SMS_PROVIDER}' not wired yet")


# ─────────────────────────── endpoints: auth ───────────────────────────
@app.post("/auth/sms/send")
def sms_send(body: dict[str, Any]) -> dict[str, Any]:
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
    mobile = str(body.get("mobile", "")).strip()
    code = str(body.get("code", "")).strip()
    with closing(db()) as c:
        rec = c.execute("SELECT * FROM sms_codes WHERE mobile = ?", (mobile,)).fetchone()
        ok = rec is not None and rec["code"] == code and rec["expire_at"] >= now_ms()
        if not ok:
            raise HTTPException(status_code=400, detail="验证码错误或已过期")
        user = c.execute("SELECT * FROM users WHERE mobile = ?", (mobile,)).fetchone()
        is_new = user is None
        if is_new:
            uid = "u_" + secrets.token_hex(8)
            c.execute(
                "INSERT INTO users(user_id, mobile, nickname, credits, created_at) VALUES(?,?,?,?,?)",
                (uid, mobile, f"用户{mobile[-4:]}", SIGNUP_BONUS, now_ms()),
            )
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
        cur = _user(c, u["user_id"])
        if cur["last_claim_day"] == today:
            return {"claimed": False, "credits": 0, "balance": cur["credits"]}
        bal = cur["credits"] + DAILY_BONUS
        c.execute("UPDATE users SET credits=?, last_claim_day=? WHERE user_id=?",
                  (bal, today, u["user_id"]))
        c.commit()
    return {"claimed": True, "credits": DAILY_BONUS, "balance": bal}


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
    granted = g["credits"] + g["bonusCredits"]
    cur = _user(c, order["user_id"])
    new_credits = cur["credits"] + granted
    expire = cur["member_expire_at"]
    if g["kind"] == "membership":
        base = max(expire, now_ms())
        expire = base + MEMBERSHIP_DAYS * 24 * 3600 * 1000
    c.execute("UPDATE users SET credits=?, member_expire_at=? WHERE user_id=?",
              (new_credits, expire, order["user_id"]))
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


# ─────────────────── 模型目录 + 中转(OpenAI 兼容,按模型倍率扣积分) ───────────────────
def _model_multiplier(model_id: str | None) -> float:
    return MODEL_MULT.get(model_id or "", 1.0)


def _charge(user_id: str, model: str, tin: int, tout: int, mult: float) -> int:
    """按 (输入+输出) tokens × 基准费率 × 模型倍率 扣积分,并记 usage_log。"""
    cost = max(1, math.ceil((tin + tout) / 1000 * CREDITS_PER_1K_TOKENS * mult)) if (tin + tout) else 0
    if cost:
        with closing(db()) as c:
            c.execute("UPDATE users SET credits = MAX(0, credits - ?) WHERE user_id = ?",
                      (cost, user_id))
            c.execute(
                "INSERT INTO usage_log(user_id, model, tokens_in, tokens_out, credits, ts) "
                "VALUES(?,?,?,?,?,?)",
                (user_id, model, tin, tout, cost, now_ms()),
            )
            c.commit()
    return cost


def _mimo_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {MIMO_API_KEY}", "Content-Type": "application/json"}


@app.get("/v1/models")
def list_models() -> dict[str, Any]:
    """公开模型目录(带每模型积分倍率),供 App 渲染。"""
    return {
        "object": "list",
        "data": [
            {"id": m["id"], "object": "model", "owned_by": "octopus",
             "display_name": m.get("display_name", m["id"]),
             "multiplier": float(m.get("multiplier", 1.0)),
             "recommended": bool(m.get("recommended", False))}
            for m in MODELS
        ],
    }


@app.post("/v1/chat/completions")
async def chat_completions(body: dict[str, Any], u: sqlite3.Row = Depends(actor)) -> Any:
    if u["credits"] <= 0:
        raise HTTPException(status_code=402, detail="积分不足,请充值")
    if not MIMO_API_KEY or not MIMO_BASE_URL:
        raise HTTPException(status_code=503, detail="平台模型未配置(MIMO_API_KEY/MIMO_BASE_URL)")

    import httpx  # 惰性 import

    model = body.get("model") or MIMO_DEFAULT_MODEL
    if model not in MODEL_MULT:  # 只服务目录内模型;未知模型回退默认(防止拿平台 key 乱调)
        model = MIMO_DEFAULT_MODEL
    mult = _model_multiplier(model)
    url = f"{MIMO_BASE_URL}/chat/completions"
    user_id = u["user_id"]

    # ── 非流式 ──
    if not bool(body.get("stream")):
        payload = dict(body)
        payload["model"] = model
        async with httpx.AsyncClient(timeout=120) as client:
            resp = await client.post(url, headers=_mimo_headers(), json=payload)
        data = resp.json() if resp.headers.get("content-type", "").startswith("application/json") else {}
        if resp.status_code >= 400:
            return JSONResponse(status_code=resp.status_code, content=data or {"error": resp.text[:200]})
        usage = (data or {}).get("usage", {}) or {}
        _charge(user_id, model, int(usage.get("prompt_tokens", 0) or 0),
                int(usage.get("completion_tokens", 0) or 0), mult)
        return JSONResponse(content=data)

    # ── 流式 SSE 透传:边转发边在末尾抓 usage,流结束后扣费 ──
    payload = dict(body)
    payload["model"] = model
    payload["stream"] = True
    opts = payload.get("stream_options")
    payload["stream_options"] = {**opts, "include_usage": True} if isinstance(opts, dict) else {"include_usage": True}

    async def _gen() -> Any:
        captured: dict[str, Any] = {}
        try:
            async with httpx.AsyncClient(timeout=None) as client:
                async with client.stream("POST", url, headers=_mimo_headers(), json=payload) as r:
                    if r.status_code >= 400:
                        err = (await r.aread()).decode("utf-8", "replace")
                        yield (b"data: " + json.dumps(
                            {"error": {"status": r.status_code, "body": err[:500]}}).encode() + b"\n\n")
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
                                except Exception:  # noqa: BLE001
                                    pass
        finally:
            usage = captured.get("usage") or {}
            _charge(user_id, model, int(usage.get("prompt_tokens", 0) or 0),
                    int(usage.get("completion_tokens", 0) or 0), mult)

    return StreamingResponse(
        _gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "Connection": "keep-alive", "X-Accel-Buffering": "no"},
    )


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok"}
