#!/usr/bin/env python3
"""官方教程小程序种子脚本 —— 把本目录下的 tutorial-*.html 以「已审核通过」身份
写入 registry_assets(type='plugin', kind='mini-app'),App 的小程序广场即刻可浏览/安装,
无需发版、无需人工审核队列(官方内容走代码评审,不走用户投稿通道)。

用法(生产):python3 seed_tutorials.py --db /path/to/octo.db
幂等:按 id upsert,重复执行只更新内容与时间戳,不会产生重复行。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import time
from pathlib import Path

HERE = Path(__file__).parent

# slug -> (名称, 简介, 分类)。HTML 文件名 = tutorial-<slug去前缀>.html
TUTORIALS: dict[str, tuple[str, str, str]] = {
    "tutorial-keepalive": (
        "官方教程 · 无障碍开启与保活指南",
        "无障碍总被自动关闭?vivo/小米/OPPO/华为各家白名单设置步骤,一篇搞定。",
        "教程",
    ),
    "tutorial-shizuku": (
        "官方教程 · Shizuku 全自动配置",
        "免电脑一键配置 Shizuku,解锁进阶自动化能力;含重启后重新激活说明。",
        "教程",
    ),
    "tutorial-getting-started": (
        "官方教程 · 新手上手指南",
        "从注册登录到第一个任务:5 分钟让 AI 开始替你操作手机。",
        "教程",
    ),
    "tutorial-channels": (
        "官方教程 · 远程指挥你的手机",
        "接入钉钉/Telegram/微信等渠道,人在外面也能让手机干活;逐人授权,安全可控。",
        "教程",
    ),
}

# mini-app 行的 tags 是对象形状(客户端 MiniAppTags 契约),教程页无任何权限诉求 → 全空。
EMPTY_TAGS = json.dumps(
    {"actions": [], "allow_tools": [], "allow_hosts": [], "allow_device": []},
    ensure_ascii=False,
)

DDL = """
CREATE TABLE IF NOT EXISTS registry_assets(
    id TEXT PRIMARY KEY,
    slug TEXT NOT NULL,
    type TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT '',
    version TEXT DEFAULT '1.0.0',
    name TEXT NOT NULL,
    description TEXT DEFAULT '',
    category TEXT DEFAULT '',
    tags TEXT DEFAULT '[]',
    platforms TEXT DEFAULT '["mobile"]',
    mode TEXT DEFAULT '',
    author_id TEXT DEFAULT '',
    status TEXT DEFAULT 'pending',
    reject_reason TEXT DEFAULT '',
    checksum TEXT DEFAULT '',
    body TEXT DEFAULT '',
    body_size INTEGER DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
)
"""


def seed(db_path: str) -> None:
    # 毫秒!registry_assets 全表 created_at/updated_at 用 app.py now_ms()=int(time.time()*1000) 存,
    # 若这里用秒(int(time.time())),教程行时间戳会比别人小 1000 倍 → 「最新」排序永远沉底、
    # trending「上架时长」算错,官方教程反而不显示。必须与表约定一致用毫秒。
    now = int(time.time() * 1000)
    conn = sqlite3.connect(db_path)
    try:
        conn.execute(DDL)
        for slug, (name, description, category) in TUTORIALS.items():
            html_file = HERE / f"{slug}.html"
            html = html_file.read_text(encoding="utf-8")
            checksum = "sha256:" + hashlib.sha256(html.encode("utf-8")).hexdigest()
            conn.execute(
                """
                INSERT INTO registry_assets(
                    id, slug, type, kind, version, name, description, category,
                    tags, platforms, mode, author_id, status, checksum,
                    body, body_size, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    version=excluded.version, name=excluded.name,
                    description=excluded.description, category=excluded.category,
                    tags=excluded.tags, status=excluded.status,
                    checksum=excluded.checksum, body=excluded.body,
                    body_size=excluded.body_size, updated_at=excluded.updated_at
                """,
                (
                    f"plugin/{slug}", slug, "plugin", "mini-app", "1.0.0",
                    name, description, category,
                    EMPTY_TAGS, json.dumps(["mobile"]), "mini-app",
                    "official", "approved", checksum,
                    html, len(html.encode("utf-8")), now, now,
                ),
            )
        conn.commit()
        rows = conn.execute(
            "SELECT slug, status, body_size FROM registry_assets "
            "WHERE kind='mini-app' AND author_id='official' ORDER BY slug"
        ).fetchall()
        for r in rows:
            print(f"  ✓ {r[0]}  status={r[1]}  {r[2]} bytes")
    finally:
        conn.close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default=str(HERE.parent / "octo.db"), help="octo.db 路径")
    args = ap.parse_args()
    print(f"seeding official tutorials into {args.db}")
    seed(args.db)
    print("done.")
