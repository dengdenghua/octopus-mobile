"""
Octopus 服务端 pytest 测试套件。

覆盖范围:
  1.  健康检查端点 (/healthz)
  2.  账号注册与登录(手机号验证码、邮箱验证码)
  3.  JWT 鉴权(有效 / 无效 / 过期 token、封禁令牌失效)
  4.  积分预扣与结算(并发安全、余额不足、正常扣费、白名单免费)
  5.  限流(同 IP / 同邮箱超限返回 429)
  6.  管理后台(未设 ADMIN_TOKEN 返回 503、设了 token 后可访问、IP 白名单)
  7.  CSV 公式注入防护
  8.  安全设计(mock auth 默认关闭、可信 IP 提取、封禁用户令牌失效)
  9.  额外:邀请返利、订单结算、每日签到、模型目录

运行: cd server && python -m pytest test_app.py -v
"""
import math
import os
import sys
import tempfile
import threading
import time
from contextlib import closing

# ── 在导入 app 前注入测试环境变量(避免污染真实 octo.db) ──
_TMPDIR = tempfile.mkdtemp(prefix="octo_test_")
os.environ["OCTO_DB"] = os.path.join(_TMPDIR, "test.db")
os.environ["ALLOW_MOCK_AUTH"] = "1"          # 测试中放开 mock 邮箱登录
os.environ["ADMIN_TOKEN"] = "test-token"      # 启用管理后台
os.environ["JWT_SECRET"] = "test-secret-key"
os.environ["EMAIL_PROVIDER"] = "mock"
os.environ["EMAIL_MOCK_CODE"] = "123456"
os.environ["SMS_PROVIDER"] = "mock"           # mock=禁用(测安全设计);流程测试用 monkeypatch 放开
os.environ["SMS_MOCK_CODE"] = "123456"
os.environ["PAYMENT_PROVIDER"] = "mock"       # mock=查单即结算
os.environ["TRUSTED_PROXIES"] = "1"
os.environ["SIGNUP_BONUS"] = "100"
os.environ["DAILY_BONUS"] = "20"
os.environ["FREE_CAP"] = "3000"
os.environ["REFERRAL_REDEEMER_BONUS"] = "200"
os.environ["REFERRAL_INVITER_BONUS"] = "200"

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import app as app_module  # noqa: E402
from app import (  # noqa: E402
    GOODS_BY_ID,
    _csv_cell,
    _reconcile_usage,
    _reserve_credits,
    app,
    client_ip,
    db,
    init_db,
    jwt_decode,
    jwt_encode,
)

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402


# ─────────────────────────── fixtures ───────────────────────────
@pytest.fixture(autouse=True)
def clean_state():
    """每个测试前清空 DB 表 + 限流字典,保证测试独立可运行。"""
    init_db()
    with closing(db()) as c:
        for t in ("users", "sms_codes", "email_codes", "orders", "usage_log", "admin_log",
                  "credit_transactions", "device_reports", "remote_devices", "remote_pair_codes"):
            c.execute(f"DELETE FROM {t}")
        c.commit()
    app_module._rl.clear()
    yield
    app_module._rl.clear()


@pytest.fixture
def client():
    """FastAPI TestClient(触发 startup → init_db)。"""
    with TestClient(app) as c:
        yield c


# ─────────────────────────── helpers ───────────────────────────
def _now_ms() -> int:
    return int(time.time() * 1000)


def _email_register(client, email="alice@example.com"):
    """通过邮箱 mock 登录注册一个新用户,返回 (token, userId)。"""
    r = client.post("/auth/email/send", json={"email": email})
    assert r.status_code == 200, r.text
    r = client.post("/auth/email/login", json={"email": email, "code": "123456"})
    assert r.status_code == 200, r.text
    d = r.json()
    return d["token"], d["userId"]


def _insert_email_code(email, code="123456", ttl_ms=300_000):
    """直接往 DB 插入邮箱验证码(绕过 send 端点的限流,用于需要重复登录同一邮箱的测试)。"""
    with closing(db()) as c:
        c.execute(
            "INSERT INTO email_codes(email, code, expire_at) VALUES(?,?,?) "
            "ON CONFLICT(email) DO UPDATE SET code=excluded.code, expire_at=excluded.expire_at",
            (email, code, _now_ms() + ttl_ms),
        )
        c.commit()


def _make_token(sub: str, exp_offset: int = 3600, secret: str = "test-secret-key") -> str:
    """手工签发 JWT(可控制 exp)。"""
    return jwt_encode(
        {"sub": sub, "iat": int(time.time()), "exp": int(time.time()) + exp_offset},
        secret,
    )


class _MockRequest:
    """轻量 mock Request,用于直接测试 client_ip。"""

    def __init__(self, xff=None, client_host="127.0.0.1"):
        self.headers = {}
        if xff is not None:
            self.headers["x-forwarded-for"] = xff
        self.client = type("C", (), {"host": client_host})()


# ═══════════════════════════════════════════════════════════════════════
# 1. 健康检查端点
# ═══════════════════════════════════════════════════════════════════════
class TestHealthz:
    def test_healthz_ok(self, client):
        r = client.get("/healthz")
        assert r.status_code == 200
        assert r.json() == {"status": "ok"}

    def test_healthz_no_auth_required(self, client):
        """健康检查不需要鉴权(不带 Authorization header 也能访问)。"""
        r = client.get("/healthz")
        assert r.status_code == 200
        assert "status" in r.json()


class TestShizukuDownload:
    def test_shizuku_latest_unconfigured(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "SHIZUKU_APK_PATH", os.path.join(_TMPDIR, "missing-shizuku.apk"))
        r = client.get("/downloads/shizuku/latest")
        assert r.status_code == 200
        data = r.json()
        assert data["name"] == "Shizuku"
        assert data["available"] is False
        assert "downloadUrl" not in data

        r = client.get("/downloads/shizuku.apk")
        assert r.status_code == 404

    def test_shizuku_latest_and_apk_download(self, client, tmp_path, monkeypatch):
        apk = tmp_path / "shizuku.apk"
        payload = b"fake apk bytes for endpoint test"
        apk.write_bytes(payload)
        monkeypatch.setattr(app_module, "SHIZUKU_APK_PATH", str(apk))
        monkeypatch.setattr(app_module, "SHIZUKU_VERSION", "test-version")

        r = client.get("/downloads/shizuku/latest")
        assert r.status_code == 200
        data = r.json()
        assert data["available"] is True
        assert data["version"] == "test-version"
        assert data["sizeBytes"] == len(payload)
        assert data["sha256"] == app_module._file_sha256(str(apk))
        assert data["downloadUrl"].endswith("/downloads/shizuku.apk")

        r = client.get("/downloads/shizuku.apk")
        assert r.status_code == 200
        assert r.content == payload
        assert r.headers["content-type"].startswith("application/vnd.android.package-archive")


# ═══════════════════════════════════════════════════════════════════════
# 2. 账号注册与登录
# ═══════════════════════════════════════════════════════════════════════
class TestEmailAuth:
    def test_email_send_ok(self, client):
        r = client.post("/auth/email/send", json={"email": "bob@example.com"})
        assert r.status_code == 200
        data = r.json()
        assert data["ok"] is True
        assert data["ttlSeconds"] == 300
        assert data["devCode"] == "123456"  # mock 模式返回固定码

    def test_email_send_invalid_email(self, client):
        for bad in ["not-an-email", "a@b", "@example.com", "a@.com", ""]:
            r = client.post("/auth/email/send", json={"email": bad})
            assert r.status_code == 400, f"{bad!r} should be 400"

    def test_email_send_failure_removes_code(self, client, monkeypatch):
        email = "smtp-fail@example.com"

        def _boom(_email, _code):
            raise RuntimeError("smtp failed")

        monkeypatch.setattr(app_module, "EMAIL_PROVIDER", "smtp")
        monkeypatch.setattr(app_module, "send_email", _boom)
        r = client.post("/auth/email/send", json={"email": email})
        assert r.status_code == 502
        assert r.json()["detail"] == "email send failed"
        with closing(db()) as c:
            rec = c.execute("SELECT * FROM email_codes WHERE email = ?", (email,)).fetchone()
        assert rec is None

    def test_email_login_new_user(self, client):
        email = "new@example.com"
        client.post("/auth/email/send", json={"email": email})
        r = client.post("/auth/email/login", json={"email": email, "code": "123456"})
        assert r.status_code == 200
        d = r.json()
        assert d["isNewUser"] is True
        assert d["email"] == email
        assert d["token"]
        assert d["userId"].startswith("u_")
        # 新用户应得注册礼
        r2 = client.get("/account/balance", headers={"Authorization": f"Bearer {d['token']}"})
        assert r2.json()["credits"] == 100

    def test_email_login_existing_user(self, client):
        email = "exist@example.com"
        _email_register(client, email)
        # 第二次登录:直接插验证码绕过 send 限流
        _insert_email_code(email)
        r = client.post("/auth/email/login", json={"email": email, "code": "123456"})
        assert r.status_code == 200
        assert r.json()["isNewUser"] is False

    def test_email_login_wrong_code(self, client):
        email = "wrong@example.com"
        client.post("/auth/email/send", json={"email": email})
        r = client.post("/auth/email/login", json={"email": email, "code": "000000"})
        assert r.status_code == 400
        assert "验证码错误" in r.json()["detail"]

    def test_email_login_expired_code(self, client):
        email = "expired@example.com"
        client.post("/auth/email/send", json={"email": email})
        # 直接改 DB 让验证码过期
        with closing(db()) as c:
            c.execute("UPDATE email_codes SET expire_at = ? WHERE email = ?", (0, email))
            c.commit()
        r = client.post("/auth/email/login", json={"email": email, "code": "123456"})
        assert r.status_code == 400

    def test_email_login_banned_user(self, client):
        """封禁用户不能再签发新 token → 403。"""
        email = "banned@example.com"
        token, uid = _email_register(client, email)
        client.post(
            f"/admin/api/users/{uid}/ban",
            json={"banned": True, "reason": "test"},
            headers={"X-Admin-Token": "test-token"},
        )
        _insert_email_code(email)  # 绕过 send 限流
        r = client.post("/auth/email/login", json={"email": email, "code": "123456"})
        assert r.status_code == 403


class TestSmsAuth:
    def test_sms_send_disabled_in_mock(self, client):
        """SMS_PROVIDER=mock 时短信端点禁用(防薅羊毛)。"""
        r = client.post("/auth/sms/send", json={"mobile": "13800000001"})
        assert r.status_code == 403

    def test_sms_login_disabled_in_mock(self, client):
        r = client.post("/auth/sms/login", json={"mobile": "13800000001", "code": "123456"})
        assert r.status_code == 403

    def test_sms_send_and_login_flow(self, client, monkeypatch):
        """完整 SMS 登录流程(放开 SMS_PROVIDER 并 mock send_sms)。"""
        monkeypatch.setattr(app_module, "SMS_PROVIDER", "test")
        monkeypatch.setattr(app_module, "send_sms", lambda m, c: None)
        mobile = "13900000001"
        r = client.post("/auth/sms/send", json={"mobile": mobile})
        assert r.status_code == 200
        # 非 mock 模式验证码是随机的,从 DB 读出
        with closing(db()) as c:
            rec = c.execute("SELECT code FROM sms_codes WHERE mobile = ?", (mobile,)).fetchone()
        code = rec["code"]
        r = client.post("/auth/sms/login", json={"mobile": mobile, "code": code})
        assert r.status_code == 200
        d = r.json()
        assert d["isNewUser"] is True
        assert d["mobile"] == mobile
        assert d["token"]

    def test_sms_send_invalid_mobile(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "SMS_PROVIDER", "test")
        monkeypatch.setattr(app_module, "send_sms", lambda m, c: None)
        for bad in ["123", "abcdefghijk", "123456"]:
            r = client.post("/auth/sms/send", json={"mobile": bad})
            assert r.status_code == 400

    def test_sms_login_wrong_code(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "SMS_PROVIDER", "test")
        monkeypatch.setattr(app_module, "send_sms", lambda m, c: None)
        mobile = "13900000002"
        client.post("/auth/sms/send", json={"mobile": mobile})
        r = client.post("/auth/sms/login", json={"mobile": mobile, "code": "000000"})
        assert r.status_code == 400


# ═══════════════════════════════════════════════════════════════════════
# 3. JWT 鉴权
# ═══════════════════════════════════════════════════════════════════════
class TestJwtAuth:
    def test_profile_with_valid_token(self, client):
        token, uid = _email_register(client, "jwt1@example.com")
        r = client.get("/account/profile", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["userId"] == uid

    def test_profile_without_token(self, client):
        r = client.get("/account/profile")
        assert r.status_code == 401

    def test_profile_with_malformed_token(self, client):
        r = client.get("/account/profile", headers={"Authorization": "Bearer not.a.jwt"})
        assert r.status_code == 401

    def test_profile_with_invalid_signature(self, client):
        """用错误 secret 签的 token → 401。"""
        bad = _make_token("u_x", secret="wrong-secret")
        r = client.get("/account/profile", headers={"Authorization": f"Bearer {bad}"})
        assert r.status_code == 401

    def test_profile_with_expired_token(self, client):
        """过期 token → 401。"""
        token, uid = _email_register(client, "jwt3@example.com")
        expired = _make_token(uid, exp_offset=-1)
        r = client.get("/account/profile", headers={"Authorization": f"Bearer {expired}"})
        assert r.status_code == 401

    def test_profile_with_unknown_user(self, client):
        """token 合法但 user 不在 DB → 401。"""
        token = _make_token("u_nonexistent")
        r = client.get("/account/profile", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 401
        assert "unknown user" in r.json()["detail"]

    def test_banned_user_token_invalidated(self, client):
        """封禁后,已签发的 token 立即失效 → 403。"""
        token, uid = _email_register(client, "jwt4@example.com")
        # 先确认能访问
        r = client.get("/account/profile", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        # 封禁
        client.post(
            f"/admin/api/users/{uid}/ban",
            json={"banned": True},
            headers={"X-Admin-Token": "test-token"},
        )
        # 再访问 → 403
        r = client.get("/account/profile", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 403

    def test_jwt_decode_roundtrip(self):
        """JWT 编码 → 解码 往返一致。"""
        claims = {"sub": "u_abc", "exp": int(time.time()) + 60}
        token = jwt_encode(claims, "secret")
        decoded = jwt_decode(token, "secret")
        assert decoded is not None
        assert decoded["sub"] == "u_abc"

    def test_jwt_decode_expired(self):
        """过期的 token 解码返回 None。"""
        token = jwt_encode({"sub": "u_x", "exp": int(time.time()) - 1}, "secret")
        assert jwt_decode(token, "secret") is None

    def test_jwt_decode_bad_sig(self):
        """签名错误解码返回 None。"""
        token = jwt_encode({"sub": "u_x", "exp": int(time.time()) + 60}, "secret")
        assert jwt_decode(token, "other-secret") is None


# ═══════════════════════════════════════════════════════════════════════
# 4. 积分预扣与结算
# ═══════════════════════════════════════════════════════════════════════
class TestBilling:
    def test_reserve_credits_success(self, client):
        """正常预扣:余额够 → 成功。"""
        token, uid = _email_register(client, "bill1@example.com")
        ok = _reserve_credits(uid, 50)
        assert ok is True
        with closing(db()) as c:
            bal = c.execute("SELECT credits FROM users WHERE user_id = ?", (uid,)).fetchone()["credits"]
        assert bal == 50  # 100 - 50

    def test_reserve_credits_insufficient(self, client):
        """余额不足 → 预扣失败,余额不变。"""
        token, uid = _email_register(client, "bill2@example.com")
        ok = _reserve_credits(uid, 200)  # 只有 100
        assert ok is False
        with closing(db()) as c:
            bal = c.execute("SELECT credits FROM users WHERE user_id = ?", (uid,)).fetchone()["credits"]
        assert bal == 100

    def test_reserve_credits_zero_hold(self, client):
        """hold=0 → 永远成功(白名单语义)。"""
        token, uid = _email_register(client, "bill3@example.com")
        ok = _reserve_credits(uid, 0)
        assert ok is True

    def test_reconcile_refund(self, client, monkeypatch):
        """预扣 50,实际扣 10 → 退 40。"""
        monkeypatch.setattr(app_module, "CREDITS_PER_1K_TOKENS", 1.0)
        token, uid = _email_register(client, "bill4@example.com")
        _reserve_credits(uid, 50)
        actual = _reconcile_usage(uid, "agnes-2.0-flash", 5000, 5000, 1.0, 50)
        # actual = ceil(10000/1000 * 1 * 1.0) = 10
        assert actual == 10
        with closing(db()) as c:
            bal = c.execute("SELECT credits FROM users WHERE user_id = ?", (uid,)).fetchone()["credits"]
        # 100 - 50(reserve) + 40(refund) = 90
        assert bal == 90

    def test_reconcile_zero_usage_full_refund(self, client):
        """上游报错(tin=tout=0)→ 全额退还预扣。"""
        token, uid = _email_register(client, "bill5@example.com")
        _reserve_credits(uid, 50)
        actual = _reconcile_usage(uid, "agnes-2.0-flash", 0, 0, 1.0, 50)
        assert actual == 0
        with closing(db()) as c:
            bal = c.execute("SELECT credits FROM users WHERE user_id = ?", (uid,)).fetchone()["credits"]
        assert bal == 100  # 全额退

    def test_reconcile_free_user(self, client):
        """白名单用户(free=True)→ actual 恒 0,仍记 usage_log。"""
        token, uid = _email_register(client, "bill6@example.com")
        actual = _reconcile_usage(uid, "agnes-2.0-flash", 1000, 1000, 1.0, 0, free=True)
        assert actual == 0
        with closing(db()) as c:
            n = c.execute("SELECT COUNT(*) n FROM usage_log WHERE user_id = ?", (uid,)).fetchone()["n"]
        assert n == 1  # 有用量记录

    def test_concurrent_reserve(self, client):
        """并发预扣:100 积分,5 个线程各扣 50 → 只有 2 个成功(原子 UPDATE 防超支)。"""
        token, uid = _email_register(client, "bill7@example.com")
        results = []

        def try_reserve():
            results.append(_reserve_credits(uid, 50))

        threads = [threading.Thread(target=try_reserve) for _ in range(5)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        assert sum(results) == 2  # 100 / 50 = 2
        with closing(db()) as c:
            bal = c.execute("SELECT credits FROM users WHERE user_id = ?", (uid,)).fetchone()["credits"]
        assert bal == 0

    def test_chat_insufficient_credits_402(self, client, monkeypatch):
        """余额不足 → POST /v1/chat/completions → 402。"""
        monkeypatch.setattr(app_module, "FREE_DAILY_CREDITS", 0)
        token, uid = _email_register(client, "bill8@example.com")
        # 清空积分
        with closing(db()) as c:
            c.execute("UPDATE users SET credits = 0 WHERE user_id = ?", (uid,))
            c.commit()
        # 配置假上游(只需通过 base/key 检查;402 在调上游前返回)
        monkeypatch.setitem(
            app_module.PROVIDERS, "agnes",
            {"base_url": "https://fake.example.com/v1", "api_key": "fake-key"},
        )
        r = client.post(
            "/v1/chat/completions",
            json={"model": "agnes-2.0-flash", "messages": [{"role": "user", "content": "hi"}]},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 402

    def test_chat_no_upstream_503(self, client):
        """上游未配置(base/key 空)→ 503。"""
        token, uid = _email_register(client, "bill9@example.com")
        r = client.post(
            "/v1/chat/completions",
            json={"model": "agnes-2.0-flash", "messages": [{"role": "user", "content": "hi"}]},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 503

    def test_chat_unauthorized(self, client):
        """未鉴权调中转 → 401。"""
        r = client.post(
            "/v1/chat/completions",
            json={"messages": [{"role": "user", "content": "hi"}]},
        )
        assert r.status_code == 401

    def test_daily_free_quota_covers_small_chat(self, client, monkeypatch):
        """每日免费额度优先抵扣,小请求不扣余额。"""
        monkeypatch.setattr(app_module, "FREE_DAILY_CREDITS", 5)
        token, uid = _email_register(client, "bill10@example.com")
        with closing(db()) as c:
            c.execute("UPDATE users SET credits = 0 WHERE user_id = ?", (uid,))
            c.commit()
        # 配置假上游,让请求走到结算(上游返回空 usage 会按 prompt 估算兜底)
        monkeypatch.setitem(
            app_module.PROVIDERS, "agnes",
            {"base_url": "https://fake.example.com/v1", "api_key": "fake-key"},
        )
        # 伪造上游返回 200 + 无 usage(测试里 httpx 会真的发请求到 fake.example.com,
        # 无法连接会抛异常并走 _settle(0,0),这样也能验证免费额度不扣余额)
        r = client.post(
            "/v1/chat/completions",
            json={"model": "agnes-2.0-flash", "messages": [{"role": "user", "content": "hi"}]},
            headers={"Authorization": f"Bearer {token}"},
        )
        # 上游连不上返回 502,但重点是余额没被扣
        with closing(db()) as c:
            bal = c.execute("SELECT credits FROM users WHERE user_id = ?", (uid,)).fetchone()["credits"]
        assert bal == 0


# ═══════════════════════════════════════════════════════════════════════
# 5. 限流
# ═══════════════════════════════════════════════════════════════════════
class TestRateLimit:
    def test_email_send_same_email_429(self, client):
        """同邮箱 60s 内只能发 1 次 → 第 2 次 429。"""
        email = "rl1@example.com"
        r1 = client.post("/auth/email/send", json={"email": email})
        assert r1.status_code == 200
        r2 = client.post("/auth/email/send", json={"email": email})
        assert r2.status_code == 429

    def test_email_send_ip_limit_429(self, client):
        """同 IP 每小时 10 条 → 第 11 条 429。"""
        for i in range(10):
            r = client.post("/auth/email/send", json={"email": f"ip{i}@example.com"})
            assert r.status_code == 200
        r = client.post("/auth/email/send", json={"email": "ip10@example.com"})
        assert r.status_code == 429

    def test_email_login_rate_limit(self, client):
        """同邮箱 10 分钟最多 10 次登录尝试 → 第 11 次 429。"""
        email = "rl2@example.com"
        for _ in range(10):
            r = client.post("/auth/email/login", json={"email": email, "code": "wrong"})
            assert r.status_code == 400  # 验证码错(还没到限流)
        r = client.post("/auth/email/login", json={"email": email, "code": "wrong"})
        assert r.status_code == 429

    def test_admin_rate_limit(self, client):
        """同 IP 每分钟 120 次管理请求 → 第 121 次 429。"""
        for _ in range(120):
            r = client.get("/admin/api/stats", headers={"X-Admin-Token": "test-token"})
            assert r.status_code == 200
        r = client.get("/admin/api/stats", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 429


# ═══════════════════════════════════════════════════════════════════════
# 6. 管理后台
# ═══════════════════════════════════════════════════════════════════════
class TestAdmin:
    def test_admin_503_without_token(self, client, monkeypatch):
        """未设 ADMIN_TOKEN → 503(默认安全关闭)。"""
        monkeypatch.setattr(app_module, "ADMIN_TOKEN", "")
        r = client.get("/admin/api/stats")
        assert r.status_code == 503

    def test_admin_page_404_without_token(self, client, monkeypatch):
        """未设 ADMIN_TOKEN 时 /admin 页面也不暴露 → 404。"""
        monkeypatch.setattr(app_module, "ADMIN_TOKEN", "")
        r = client.get("/admin")
        assert r.status_code == 404

    def test_admin_401_wrong_token(self, client):
        """口令错 → 401。"""
        r = client.get("/admin/api/stats", headers={"X-Admin-Token": "wrong"})
        assert r.status_code == 401

    def test_admin_stats_ok(self, client):
        """正确 token → 200 + 统计数据。"""
        r = client.get("/admin/api/stats", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200
        d = r.json()
        assert "users" in d
        assert "totalCredits" in d
        assert "calls" in d

    def test_admin_users_list(self, client):
        _email_register(client, "adm1@example.com")
        _email_register(client, "adm2@example.com")
        r = client.get("/admin/api/users", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200
        d = r.json()
        assert d["total"] == 2
        assert len(d["items"]) == 2

    def test_admin_users_search(self, client):
        _email_register(client, "search@example.com")
        _email_register(client, "other@example.com")
        r = client.get("/admin/api/users?q=search", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200
        assert r.json()["total"] == 1

    def test_admin_adjust_credits(self, client):
        token, uid = _email_register(client, "adm3@example.com")
        r = client.post(
            f"/admin/api/users/{uid}/credits",
            json={"delta": 500, "reason": "test"},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 200
        assert r.json()["balance"] == 600  # 100 + 500

    def test_admin_adjust_credits_negative_clamp(self, client):
        """负向调整会被 MAX(0,...) 截断到 0。"""
        token, uid = _email_register(client, "adm4@example.com")
        r = client.post(
            f"/admin/api/users/{uid}/credits",
            json={"delta": -10000, "reason": "test"},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 200
        assert r.json()["balance"] == 0

    def test_admin_adjust_credits_invalid_delta(self, client):
        token, uid = _email_register(client, "adm4b@example.com")
        r = client.post(
            f"/admin/api/users/{uid}/credits",
            json={"delta": "abc"},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 400

    def test_admin_membership_grant(self, client):
        token, uid = _email_register(client, "adm5@example.com")
        r = client.post(
            f"/admin/api/users/{uid}/membership",
            json={"days": 30, "reason": "test"},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 200
        d = r.json()
        assert d["memberActive"] is True
        assert d["memberExpireAt"] > 0

    def test_admin_ban_and_unban(self, client):
        token, uid = _email_register(client, "adm6@example.com")
        r = client.post(
            f"/admin/api/users/{uid}/ban",
            json={"banned": True, "reason": "test"},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 200
        assert r.json()["banned"] is True
        r = client.post(
            f"/admin/api/users/{uid}/ban",
            json={"banned": False, "reason": "test"},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 200
        assert r.json()["banned"] is False

    def test_admin_ban_requires_bool(self, client):
        """banned 必须显式 true/false,防漏字段静默解封。"""
        token, uid = _email_register(client, "adm7@example.com")
        r = client.post(
            f"/admin/api/users/{uid}/ban",
            json={"banned": "yes"},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 400

    def test_admin_logs(self, client):
        token, uid = _email_register(client, "adm8@example.com")
        client.post(
            f"/admin/api/users/{uid}/credits",
            json={"delta": 100, "reason": "log test"},
            headers={"X-Admin-Token": "test-token"},
        )
        r = client.get("/admin/api/logs", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200
        items = r.json()["items"]
        assert len(items) >= 1
        assert items[0]["action"] == "credits"

    def test_admin_user_not_found(self, client):
        r = client.post(
            "/admin/api/users/u_nope/credits",
            json={"delta": 100},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 404


# ═══════════════════════════════════════════════════════════════════════
# 7. CSV 公式注入防护
# ═══════════════════════════════════════════════════════════════════════
class TestCsvInjection:
    def test_csv_cell_formula_equal(self):
        assert _csv_cell("=cmd|/c calc") == "'=cmd|/c calc"

    def test_csv_cell_formula_plus(self):
        assert _csv_cell("+1+1") == "'+1+1"

    def test_csv_cell_formula_minus(self):
        assert _csv_cell("-1+1") == "'-1+1"

    def test_csv_cell_formula_at(self):
        assert _csv_cell("@SUM(A1)") == "'@SUM(A1)"

    def test_csv_cell_normal(self):
        assert _csv_cell("normal") == "normal"

    def test_csv_cell_comma(self):
        assert _csv_cell("a,b") == '"a,b"'

    def test_csv_cell_quote(self):
        assert _csv_cell('say "hi"') == '"say ""hi"""'

    def test_csv_cell_none(self):
        assert _csv_cell(None) == ""

    def test_csv_export_with_injection(self, client):
        """导出 CSV 时,含公式注入字符的字段应被转义。"""
        token, uid = _email_register(client, "csv@example.com")
        with closing(db()) as c:
            c.execute(
                "INSERT INTO usage_log(user_id, model, tokens_in, tokens_out, credits, ts) "
                "VALUES(?,?,?,?,?,?)",
                (uid, '=HYPERLINK("http://evil")', 100, 50, 5, _now_ms()),
            )
            c.commit()
        r = client.get("/admin/api/usage.csv", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200
        text = r.text
        # 恶意 model 名应被转义(前面加 ')
        assert "'=HYPERLINK" in text
        # 逐行检查:数据行不应有单元格以 = 开头(应被 ' 转义)
        for line in text.split("\n"):
            if line.startswith("id,"):  # 表头跳过
                continue
            if not line.strip():
                continue
            # 简易检查:不应出现裸露的 = 开头单元格
            assert not line.startswith("="), f"unescaped formula at line: {line}"


# ═══════════════════════════════════════════════════════════════════════
# 8. 安全设计
# ═══════════════════════════════════════════════════════════════════════
class TestSecurity:
    def test_mock_auth_disabled_by_default(self, client, monkeypatch):
        """ALLOW_MOCK_AUTH=0 时邮箱 mock 登录禁用 → 403。"""
        monkeypatch.setattr(app_module, "ALLOW_MOCK_AUTH", False)
        r = client.post("/auth/email/send", json={"email": "sec1@example.com"})
        assert r.status_code == 403
        r = client.post("/auth/email/login", json={"email": "sec1@example.com", "code": "123456"})
        assert r.status_code == 403

    def test_sms_disabled_in_mock(self, client):
        """SMS_PROVIDER=mock 时短信端点禁用。"""
        assert client.post("/auth/sms/send", json={"mobile": "13800000000"}).status_code == 403
        assert client.post("/auth/sms/login", json={"mobile": "13800000000", "code": "123456"}).status_code == 403

    def test_client_ip_no_xff(self):
        """无 XFF → 用 socket IP。"""
        req = _MockRequest(xff=None, client_host="1.2.3.4")
        assert client_ip(req) == "1.2.3.4"

    def test_client_ip_with_trusted_proxy(self):
        """TRUSTED_PROXIES=1 → 取 XFF 右数第 1 个(不可伪造)。"""
        req = _MockRequest(xff="1.1.1.1, 2.2.2.2", client_host="3.3.3.3")
        assert client_ip(req) == "2.2.2.2"

    def test_client_ip_single_xff(self):
        """XFF 只有 1 段且 TRUSTED_PROXIES=1 → 取该段。"""
        req = _MockRequest(xff="1.1.1.1", client_host="2.2.2.2")
        assert client_ip(req) == "1.1.1.1"

    def test_client_ip_trusted_proxies_zero(self, monkeypatch):
        """TRUSTED_PROXIES=0 → 忽略 XFF,用 socket IP(防伪造)。"""
        monkeypatch.setattr(app_module, "TRUSTED_PROXIES", 0)
        req = _MockRequest(xff="1.1.1.1, 2.2.2.2", client_host="3.3.3.3")
        assert client_ip(req) == "3.3.3.3"

    def test_admin_ip_allowlist_blocked(self, client, monkeypatch):
        """设了 IP 白名单后,非白名单 IP → 403。"""
        monkeypatch.setattr(app_module, "ADMIN_IP_ALLOWLIST", ["10.0.0.1"])
        r = client.get("/admin/api/stats", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 403

    def test_admin_ip_allowlist_allowed(self, client, monkeypatch):
        """白名单 IP + 正确 token → 200。"""
        monkeypatch.setattr(app_module, "ADMIN_IP_ALLOWLIST", ["testclient"])
        r = client.get("/admin/api/stats", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200

    def test_admin_empty_token_401(self, client):
        """空口令 → 401(不泄露任何信息)。"""
        r = client.get("/admin/api/stats", headers={"X-Admin-Token": ""})
        assert r.status_code == 401


# ═══════════════════════════════════════════════════════════════════════
# 9. 邀请返利
# ═══════════════════════════════════════════════════════════════════════
class TestInvite:
    def test_invite_info(self, client):
        token, uid = _email_register(client, "inv1@example.com")
        r = client.get("/invite/info", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["code"]  # 有邀请码
        assert d["invitedCount"] == 0
        assert d["redeemed"] is False

    def test_invite_redeem_ok(self, client):
        """新人填邀请码:新人得 200,邀请人也得 200。"""
        token_a, uid_a = _email_register(client, "inv2@example.com")
        token_b, uid_b = _email_register(client, "inv3@example.com")
        r = client.get("/invite/info", headers={"Authorization": f"Bearer {token_a}"})
        code = r.json()["code"]
        r = client.post("/invite/redeem", json={"code": code},
                        headers={"Authorization": f"Bearer {token_b}"})
        assert r.status_code == 200
        # B:100(注册) + 200(邀请) = 300
        assert r.json()["balance"] == 300
        # A:100(注册) + 200(邀请人奖励) = 300
        r = client.get("/account/balance", headers={"Authorization": f"Bearer {token_a}"})
        assert r.json()["credits"] == 300

    def test_invite_redeem_own_code(self, client):
        """不能使用自己的邀请码。"""
        token, uid = _email_register(client, "inv4@example.com")
        r = client.get("/invite/info", headers={"Authorization": f"Bearer {token}"})
        code = r.json()["code"]
        r = client.post("/invite/redeem", json={"code": code},
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 400
        assert "自己" in r.json()["detail"]

    def test_invite_redeem_twice_rejected(self, client):
        """同一新人只能用一次邀请码。"""
        token_a, _ = _email_register(client, "inv5@example.com")
        token_b, _ = _email_register(client, "inv6@example.com")
        r = client.get("/invite/info", headers={"Authorization": f"Bearer {token_a}"})
        code = r.json()["code"]
        # B 第一次使用 → 成功
        client.post("/invite/redeem", json={"code": code},
                    headers={"Authorization": f"Bearer {token_b}"})
        # B 第二次使用 → 拒绝
        r = client.post("/invite/redeem", json={"code": code},
                        headers={"Authorization": f"Bearer {token_b}"})
        assert r.status_code == 400

    def test_invite_redeem_invalid_code(self, client):
        token, uid = _email_register(client, "inv8@example.com")
        r = client.post("/invite/redeem", json={"code": "NOPE"},
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 400


# ═══════════════════════════════════════════════════════════════════════
# 10. 订单与结算
# ═══════════════════════════════════════════════════════════════════════
class TestOrders:
    def test_billing_estimate(self, client):
        """/billing/estimate 返回预估积分与人民币。"""
        token, uid = _email_register(client, "est1@example.com")
        r = client.post(
            "/billing/estimate",
            json={"model": "agnes-2.0-flash", "messages": [{"role": "user", "content": "hello"}]},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 200
        d = r.json()
        assert d["model"] == "agnes-2.0-flash"
        assert d["multiplier"] == 0.2
        assert d["worstCaseCredits"] >= 1
        assert "chargeableCredits" in d
        assert "estimatedRmb" in d

    def test_create_order(self, client):
        token, uid = _email_register(client, "ord1@example.com")
        r = client.post("/billing/orders", json={"goodsId": "g_100"},
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["orderNo"]
        assert d["amountFen"] == 990
        assert d["credits"] == 100

    def test_create_order_invalid_goods(self, client):
        token, uid = _email_register(client, "ord2@example.com")
        r = client.post("/billing/orders", json={"goodsId": "nope"},
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 400

    def test_query_order_mock_settle(self, client):
        """mock 支付:查单即结算 → PAID + 积分到账。"""
        token, uid = _email_register(client, "ord3@example.com")
        r = client.post("/billing/orders", json={"goodsId": "g_100"},
                        headers={"Authorization": f"Bearer {token}"})
        order_no = r.json()["orderNo"]
        r = client.get(f"/billing/orders/{order_no}",
                       headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["status"] == "PAID"
        assert d["credits"] == 100
        # 查余额:100(注册) + 100(充值) = 200
        r = client.get("/account/balance", headers={"Authorization": f"Bearer {token}"})
        assert r.json()["credits"] == 200

    def test_query_order_not_found(self, client):
        token, uid = _email_register(client, "ord4@example.com")
        r = client.get("/billing/orders/ONOTEXIST",
                       headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 404

    def test_membership_order_extends_expire(self, client):
        """购买会员商品 → member_expire_at 延长。"""
        token, uid = _email_register(client, "ord5@example.com")
        r = client.post("/billing/orders", json={"goodsId": "m_month"},
                        headers={"Authorization": f"Bearer {token}"})
        order_no = r.json()["orderNo"]
        client.get(f"/billing/orders/{order_no}",
                   headers={"Authorization": f"Bearer {token}"})
        r = client.get("/account/balance",
                       headers={"Authorization": f"Bearer {token}"})
        assert r.json()["membershipActive"] is True
        assert r.json()["membershipExpireAt"] > 0

    def test_billing_goods(self, client):
        """商品目录接口。"""
        token, uid = _email_register(client, "ord6@example.com")
        r = client.get("/billing/goods", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        items = r.json()["items"]
        assert len(items) == len(GOODS_BY_ID)
        assert any(g["id"] == "m_month" for g in items)


# ═══════════════════════════════════════════════════════════════════════
# 11. 每日签到 & 模型目录
# ═══════════════════════════════════════════════════════════════════════
class TestDailyClaim:
    def test_daily_claim_first_time(self, client):
        token, uid = _email_register(client, "dc1@example.com")
        r = client.post("/account/daily-claim",
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["claimed"] is True
        assert d["credits"] == 20
        assert d["balance"] == 120  # 100 + 20

    def test_daily_claim_twice_same_day(self, client):
        """同一天重复签到 → claimed=False。"""
        token, uid = _email_register(client, "dc2@example.com")
        client.post("/account/daily-claim",
                    headers={"Authorization": f"Bearer {token}"})
        r = client.post("/account/daily-claim",
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["claimed"] is False
        assert d["credits"] == 0


class TestModels:
    def test_list_models(self, client):
        r = client.get("/v1/models")
        assert r.status_code == 200
        d = r.json()
        assert d["object"] == "list"
        assert len(d["data"]) >= 2
        assert any(m["id"] == "agnes-2.0-flash" for m in d["data"])

    def test_list_models_public(self, client):
        """/v1/models 不需要鉴权。"""
        r = client.get("/v1/models")
        assert r.status_code == 200


# ═══════════════════════════════════════════════════════════════════════
# 12. 官网远程控制台配对
# ═══════════════════════════════════════════════════════════════════════
class TestRemotePairing:
    def test_pair_start_and_claim_device(self, client):
        token, uid = _email_register(client, "remote1@example.com")
        headers = {"Authorization": f"Bearer {token}"}

        r = client.post("/remote/pair/start", json={"deviceName": "测试手机"}, headers=headers)
        assert r.status_code == 200
        code = r.json()["code"]
        assert len(code) == 6
        assert r.json()["ttlSeconds"] == 300

        r = client.post(
            "/remote/pair/claim",
            json={"code": code, "deviceName": "测试手机", "deviceId": "dev_test_1"},
            headers=headers,
        )
        assert r.status_code == 200
        d = r.json()
        assert d["deviceId"] == "dev_test_1"
        assert d["deviceToken"].startswith("rt_")

        r = client.get("/remote/devices", headers=headers)
        assert r.status_code == 200
        items = r.json()["items"]
        assert len(items) == 1
        assert items[0]["deviceName"] == "测试手机"
        assert items[0]["online"] is False
        assert items[0]["revoked"] is False

    def test_pair_claim_requires_same_user(self, client):
        token_a, _ = _email_register(client, "remote2a@example.com")
        token_b, _ = _email_register(client, "remote2b@example.com")
        code = client.post(
            "/remote/pair/start",
            json={},
            headers={"Authorization": f"Bearer {token_a}"},
        ).json()["code"]

        r = client.post(
            "/remote/pair/claim",
            json={"code": code, "deviceName": "不该成功"},
            headers={"Authorization": f"Bearer {token_b}"},
        )
        assert r.status_code == 403

    def test_revoke_remote_device(self, client):
        token, uid = _email_register(client, "remote3@example.com")
        headers = {"Authorization": f"Bearer {token}"}
        code = client.post("/remote/pair/start", json={}, headers=headers).json()["code"]
        client.post(
            "/remote/pair/claim",
            json={"code": code, "deviceName": "待撤销", "deviceId": "dev_revoke_1"},
            headers=headers,
        )

        r = client.post("/remote/devices/dev_revoke_1/revoke", headers=headers)
        assert r.status_code == 200
        assert r.json()["ok"] is True

        r = client.get("/remote/devices", headers=headers)
        assert r.status_code == 200
        assert r.json()["items"][0]["revoked"] is True

    def test_remote_console_page_public_shell(self, client):
        r = client.get("/remote/console")
        assert r.status_code == 200
        assert "Octopus 远程控制台" in r.text


# ═══════════════════════════════════════════════════════════════════════
# 13. 会员/积分/计费模型增强
# ═══════════════════════════════════════════════════════════════════════
class TestMembership:
    def test_membership_inactive_for_new_user(self, client):
        token, uid = _email_register(client, "mem1@example.com")
        r = client.get("/account/membership", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["active"] is False
        assert d["expireAt"] == 0
        assert d["remainingDays"] == 0
        assert any("解锁自有模型" in b for b in d["benefits"])

    def test_membership_active_after_order(self, client):
        token, uid = _email_register(client, "mem2@example.com")
        r = client.post("/billing/orders", json={"goodsId": "m_month"},
                        headers={"Authorization": f"Bearer {token}"})
        order_no = r.json()["orderNo"]
        client.get(f"/billing/orders/{order_no}", headers={"Authorization": f"Bearer {token}"})
        r = client.get("/account/membership", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["active"] is True
        assert d["expireAt"] > 0
        assert d["remainingDays"] >= 29


class TestCreditLedger:
    def test_signup_recorded_in_ledger(self, client):
        token, uid = _email_register(client, "ledger1@example.com")
        r = client.get("/account/credits/transactions", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["total"] >= 1
        assert any(tx["source"] == "signup" and tx["delta"] == 100 for tx in d["items"])

    def test_daily_claim_recorded_in_ledger(self, client):
        token, uid = _email_register(client, "ledger2@example.com")
        client.post("/account/daily-claim", headers={"Authorization": f"Bearer {token}"})
        r = client.get("/account/credits/transactions", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        assert any(tx["source"] == "daily" and tx["delta"] == 20 for tx in r.json()["items"])

    def test_order_settle_recorded_in_ledger(self, client):
        token, uid = _email_register(client, "ledger3@example.com")
        r = client.post("/billing/orders", json={"goodsId": "g_100"},
                        headers={"Authorization": f"Bearer {token}"})
        order_no = r.json()["orderNo"]
        client.get(f"/billing/orders/{order_no}", headers={"Authorization": f"Bearer {token}"})
        r = client.get("/account/credits/transactions", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        assert any(tx["source"] == "order" and tx["delta"] == 100 for tx in r.json()["items"])

    def test_admin_adjust_recorded_in_ledger(self, client):
        token, uid = _email_register(client, "ledger4@example.com")
        client.post(
            f"/admin/api/users/{uid}/credits",
            json={"delta": 50, "reason": "ledger test"},
            headers={"X-Admin-Token": "test-token"},
        )
        r = client.get("/account/credits/transactions", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        assert any(tx["source"] == "admin_adjust" and tx["delta"] == 50 for tx in r.json()["items"])

    def test_credit_transactions_pagination(self, client):
        token, uid = _email_register(client, "ledger5@example.com")
        r = client.get("/account/credits/transactions?limit=1", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert len(d["items"]) == 1
        assert d["total"] >= 1


class TestAccountUsage:
    def test_account_usage_empty(self, client):
        token, uid = _email_register(client, "usage1@example.com")
        r = client.get("/account/usage", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["total"] == 0
        assert d["summary"]["calls"] == 0

    def test_account_usage_records(self, client):
        token, uid = _email_register(client, "usage2@example.com")
        # 直接写入 usage_log
        with closing(db()) as c:
            c.execute(
                "INSERT INTO usage_log(user_id, model, tokens_in, tokens_out, credits, ts) "
                "VALUES(?,?,?,?,?,?)",
                (uid, "agnes-2.0-flash", 100, 50, 5, _now_ms()),
            )
            c.commit()
        r = client.get("/account/usage", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["total"] == 1
        assert d["summary"]["calls"] == 1
        assert d["summary"]["credits"] == 5
        assert d["items"][0]["model"] == "agnes-2.0-flash"


# ═══════════════════════════════════════════════════════════════════════
# 14. App 协议对齐:设备注册/心跳/上报/状态
# ═══════════════════════════════════════════════════════════════════════
class TestDeviceProtocol:
    def test_device_register_new(self, client):
        token, uid = _email_register(client, "dev1@example.com")
        r = client.post(
            "/device/register",
            json={"deviceName": "测试机", "deviceModel": "Pixel 8", "osVersion": "14",
                  "appVersion": "1.2.3", "pushToken": "push_xxx"},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 200
        d = r.json()
        assert d["deviceId"].startswith("d_")
        assert d["deviceToken"].startswith("dt_")
        assert d["deviceName"] == "测试机"

    def test_device_heartbeat(self, client):
        token, uid = _email_register(client, "dev2@example.com")
        reg = client.post("/device/register", json={"deviceName": "测试机"},
                          headers={"Authorization": f"Bearer {token}"}).json()
        device_id = reg["deviceId"]
        r = client.post(
            "/device/heartbeat",
            json={"deviceId": device_id, "battery": 75, "isCharging": True,
                  "currentApp": "com.example.app", "screenHash": "abc123"},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 200
        d = r.json()
        assert d["ok"] is True
        assert d["battery"] == 75

    def test_device_heartbeat_requires_device_id(self, client):
        token, uid = _email_register(client, "dev3@example.com")
        r = client.post("/device/heartbeat", json={"battery": 50},
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 400

    def test_device_heartbeat_other_users_device_fails(self, client):
        token_a, _ = _email_register(client, "dev4a@example.com")
        token_b, _ = _email_register(client, "dev4b@example.com")
        device_id = client.post("/device/register", json={},
                                headers={"Authorization": f"Bearer {token_a}"}).json()["deviceId"]
        r = client.post("/device/heartbeat", json={"deviceId": device_id, "battery": 50},
                        headers={"Authorization": f"Bearer {token_b}"})
        assert r.status_code == 404

    def test_device_report(self, client):
        token, uid = _email_register(client, "dev5@example.com")
        device_id = client.post("/device/register", json={"deviceName": "测试机"},
                                headers={"Authorization": f"Bearer {token}"}).json()["deviceId"]
        r = client.post(
            "/device/report",
            json={"deviceId": device_id, "type": "crash", "payload": {"reason": "oom"}},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 200
        assert r.json()["ok"] is True

    def test_device_status(self, client):
        token, uid = _email_register(client, "dev6@example.com")
        device_id = client.post("/device/register", json={"deviceName": "测试机", "osVersion": "14"},
                                headers={"Authorization": f"Bearer {token}"}).json()["deviceId"]
        client.post("/device/heartbeat", json={"deviceId": device_id, "battery": 60},
                    headers={"Authorization": f"Bearer {token}"})
        r = client.get(f"/device/{device_id}/status", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["deviceId"] == device_id
        assert d["deviceName"] == "测试机"
        assert d["osVersion"] == "14"
        assert d["batteryLevel"] == 60
        assert d["lastHeartbeatAt"] > 0
