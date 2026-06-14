# octopus 账号 + 计费 + 会员 + 大模型中转(服务端骨架)

App 侧(`com.apk.claw.android.account`)按这套契约调用。默认全 mock,本地直接跑通;
生产把 `MIMO_API_KEY` / 支付 / 短信 env 配上即可,**App 不用改**。

## 它做什么

- **账号**:手机号 + 验证码登录/注册(opaque token 鉴权,存 sqlite)。
- **付费体系(默认路径)**:`/v1/chat/completions` **中转**到平台 MiMo(共享 key 只在服务端),
  按返回的 token 用量**扣积分**;积分不足返回 402。
- **会员(BYO 解锁)**:买 `kind=membership` 的商品 → 当月 `membershipActive=true`。
  App 据此解锁"接自己的大模型"(BYO 走客户端直连、不经本中转、不扣积分)。
- **充值/订单/每日签到**:积分商品下单 + 查单到账;签到每日一次。

## API(App 的 AccountGateway 契约)

```
POST /auth/sms/send            {mobile}            -> {ok, ttlSeconds, devCode?}
POST /auth/sms/login           {mobile, code}      -> {token, userId, mobile, isNewUser, nickname}
GET  /account/profile          (Bearer)            -> {userId, mobile, nickname, avatar}
GET  /account/balance          (Bearer)            -> {credits, membershipActive, membershipExpireAt}
POST /account/daily-claim      (Bearer)            -> {claimed, credits, balance}
GET  /billing/goods            (Bearer)            -> {items:[{id,title,credits,bonusCredits,priceFen,tag,kind}]}
POST /billing/orders           (Bearer){goodsId}   -> {orderNo, payUrl?, amountFen, credits}
GET  /billing/orders/{orderNo} (Bearer)            -> {orderNo, status, credits}
GET  /v1/models                                     -> 公开模型目录(带每模型积分倍率)
POST /v1/chat/completions      (Bearer) OpenAI体    -> 转发 MiMo + 按倍率扣积分(支持 stream)
POST /billing/webhook/{prov}                        -> 生产支付回调(骨架留桩 501)
GET  /healthz
GET  /admin                                         -> 管理后台单页(未启用时 404)
GET  /admin/api/*              (X-Admin-Token)       -> 后台 JSON 接口(见下)
```

## 管理后台 /admin

打开 `https://你的域名/admin`,输入 `ADMIN_TOKEN` 即可:看用户/积分/订单/用量与成本,
手动加减积分、封禁/解封、赠送会员、导出用量 CSV;所有变更写 `admin_log` 审计表。

- **默认关闭**:不设 `ADMIN_TOKEN` 时 `/admin` 返回 404、`/admin/api/*` 返回 503,不增加攻击面。
- **启用**:`.env` 设 `ADMIN_TOKEN=$(openssl rand -hex 32)` 后重启。它是公网后台**唯一应用层防线**
  (能改积分/封号),务必强随机。
- **加固(强烈建议)**:① 在 nginx 给后台加 IP 白名单作第二道门 —— ② 或设 `ADMIN_IP_ALLOWLIST`
  (应用层,基于可信来源 IP)。两者都依赖 `TRUSTED_PROXIES`(nginx 反代=1)正确,否则限流/白名单
  可被伪造的 `X-Forwarded-For` 绕过。nginx 示例:

  ```nginx
  location /admin { allow 1.2.3.4; deny all; proxy_pass http://127.0.0.1:8081; }
  ```

鉴权用 **JWT(HS256)**:登录返回的 token 即 JWT,后续请求带 `Authorization: Bearer <token>`。
计费 = `ceil((输入+输出 tokens)/1000 × CREDITS_PER_1K_TOKENS × 模型 multiplier)`。
中转 `/v1/chat/completions` 同时支持**非流式**和**流式 SSE 透传**(流式自动注入 `stream_options.include_usage`,在末尾抓 usage 后扣费)。

## 在你的服务器上跑(已实测该机:2 vCPU / 1GB / Python 3.12)

```bash
cd ~/octopus-mobile/server          # 把本目录拷到服务器
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env                 # 按需填 MIMO_* 等
set -a; . ./.env; set +a
uvicorn app:app --host 127.0.0.1 --port 8081
```

放在你现有的 nginx(80/443)后面反代到 `127.0.0.1:8081`,再把 App 的
`AccountConfig.baseUrl` 指到 `https://你的域名`。⚠️ 1GB 内存且已跑着一个 Next.js,
正式上量前建议把实例升到 2–4GB,并考虑把中转单独拆一台。

## 已补(参考 Molili)

- **流式 SSE 透传** + 末尾抓 usage 扣费。
- **模型目录 + 每模型倍率定价**(`/v1/models`,`MODELS_JSON` 可覆盖)。
- **JWT(HS256)会话**(stdlib 实现,无 PyJWT 依赖)。

## 还没做(留给生产)

- **真支付**:`PAYMENT_PROVIDER=wechat/alipay` 的收银台 + `/billing/webhook` 验签。
- **真短信**:`SMS_PROVIDER` 接阿里云/腾讯云,实现 `send_sms()`。
- **JWT 吊销**:无状态 JWT 改密码/登出无法立即失效;需要的话加一张吊销表或短期 token+refresh。
- **限流/并发**:按用户限流;高并发再加 gunicorn 多 worker / 拆机。
- **流式扣费兜底**:若上游不返回 usage,当前不扣费(只 log);可补 token 估算。
