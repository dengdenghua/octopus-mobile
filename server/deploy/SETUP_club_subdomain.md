# 部署 club.octoapk.com（技能中心 / 广场子域）

**现状**（`dig` 实测）：`api.octoapk.com → 32.185.238.217`，`club.octoapk.com` 暂无记录。
服务器：Ubuntu，nginx(80/443) 反代 `uvicorn app:app @ 127.0.0.1:8081`。

> 这四步需在**服务器 32.185.238.217** 和**octoapk.com 的 DNS 服务商后台**操作——
> 都需要你的账号/SSH 权限，无法从本机代办。准备好后逐条执行即可（约 5 分钟）。

## 1) 申请 / 解析子域名（DNS 服务商后台，如 Cloudflare / 阿里云 / DNSPod）
加一条记录：

| 类型 | 主机 | 值 | TTL |
|---|---|---|---|
| A | `club` | `32.185.238.217` | 600 |

（也可用 `CNAME club → api.octoapk.com`；A 记录更直接。）
验证：`dig +short club.octoapk.com` → 应返回 `32.185.238.217`

## 2) 重新部署带新端点的后端
新端点：`GET /square/feed`、`GET /square/discovery`、`GET /config`（在最新 `app.py`）。

```bash
cd ~/octopus-server            # 你的部署目录
git pull                       # 或拷入最新 server/app.py
. .venv/bin/activate
# 按你的进程管理方式重启（systemd / pm2 / nohup）：
sudo systemctl restart octopus-server   # 例
```

自检：`curl -s http://127.0.0.1:8081/config` → `{"squareBaseUrl":"https://club.octoapk.com"}`

## 3) 配 nginx vhost + 申请证书
```bash
sudo cp club.octoapk.com.conf /etc/nginx/sites-available/club.octoapk.com.conf
sudo ln -s /etc/nginx/sites-available/club.octoapk.com.conf /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d club.octoapk.com    # 申请 Let's Encrypt 证书（免费、自动续期）
```

> 更省事替代：把 `club.octoapk.com` 直接加进现有 api 的 `server_name`，
> `sudo certbot --nginx -d api.octoapk.com -d club.octoapk.com` 共用一张证书，免新建 vhost。

## 4) 验证（公网）
```bash
curl -s https://club.octoapk.com/config            # {"squareBaseUrl":"https://club.octoapk.com"}
curl -s https://club.octoapk.com/square/discovery  # {"posts":[...]}
curl -s https://api.octoapk.com/config             # 同样派生出 club（Host 派生逻辑）
```

完成后 App 进广场会：`api/config → 得到 club 域 → club/square/*`。无需发版。
