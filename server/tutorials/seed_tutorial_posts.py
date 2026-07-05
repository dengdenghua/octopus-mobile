#!/usr/bin/env python3
"""官方教程「图文帖」种子脚本 —— 把 tutorial-*.html 转成纯文本,写入 square_posts
(灵感广场帖子流),并从 registry_assets 删掉同名小程序(教程属于「读的内容」不是「装的应用」,
应在灵感帖子流、不在应用货架)。取代早先的 seed_tutorials.py(那个把教程当 mini-app 上货架)。

- 作者:内置 official 账号(昵称「Octopus 官方」),不存在则建。
- 帖子 id 稳定为 post_tutorial-<slug>(下划线前缀,不含斜杠——斜杠会让单段路由 404),幂等 upsert;
  status='approved' 直接进 feed(官方内容走代码评审)。
- HTML → 纯文本:去标签、块级元素换行、li 加「• 」、折叠多余空行(帖子只渲染纯文本+图片,富样式丢失是已知取舍)。

用法(生产):python3 seed_tutorial_posts.py --db /path/to/octo.db
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
import time
from html.parser import HTMLParser
from pathlib import Path

HERE = Path(__file__).parent

# slug -> (标题, tag)。正文由 tutorial-<slug>.html 提取。
TUTORIALS: dict[str, tuple[str, str]] = {
    "tutorial-keepalive": ("官方教程 · 无障碍开启与保活指南", "教程"),
    "tutorial-shizuku": ("官方教程 · Shizuku 全自动配置", "教程"),
    "tutorial-getting-started": ("官方教程 · 新手上手指南", "教程"),
    "tutorial-channels": ("官方教程 · 远程指挥你的手机", "教程"),
}

OFFICIAL_ID = "official"
OFFICIAL_NICK = "Octopus 官方"

_BLOCK = {"p", "div", "section", "article", "br", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol"}
_SKIP = {"script", "style", "head"}


class _TextExtractor(HTMLParser):
    """把 HTML 抽成可读纯文本:块级元素之间换行,li 前加「• 」,跳过 script/style。"""

    def __init__(self) -> None:
        super().__init__()
        self.parts: list[str] = []
        self._skip_depth = 0

    def handle_starttag(self, tag: str, attrs: list) -> None:
        if tag in _SKIP:
            self._skip_depth += 1
        elif tag == "li":
            self.parts.append("\n• ")
        elif tag in _BLOCK:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in _SKIP and self._skip_depth > 0:
            self._skip_depth -= 1
        elif tag in _BLOCK:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if self._skip_depth == 0:
            self.parts.append(data)

    def text(self) -> str:
        raw = "".join(self.parts)
        # 每行去首尾空白;折叠 3+ 连续空行为最多 1 个空行;整体去首尾空行。
        lines = [ln.strip() for ln in raw.splitlines()]
        out: list[str] = []
        blank = 0
        for ln in lines:
            if ln:
                out.append(ln)
                blank = 0
            else:
                blank += 1
                if blank == 1 and out:
                    out.append("")
        return re.sub(r"\n{3,}", "\n\n", "\n".join(out)).strip()


def html_to_text(html: str) -> str:
    p = _TextExtractor()
    p.feed(html)
    return p.text()


def seed(db_path: str) -> None:
    now = int(time.time() * 1000)  # 毫秒,与 registry_assets/square_posts 全表 now_ms 约定一致
    conn = sqlite3.connect(db_path)
    try:
        # 官方账号(帖子作者名靠它;_present_post 用 nickname 展示)
        conn.execute(
            "INSERT INTO users(user_id, nickname, credits, created_at) VALUES(?,?,0,?) "
            "ON CONFLICT(user_id) DO UPDATE SET nickname=excluded.nickname",
            (OFFICIAL_ID, OFFICIAL_NICK, now),
        )
        for slug, (title, tag) in TUTORIALS.items():
            html = (HERE / f"{slug}.html").read_text(encoding="utf-8")
            content = html_to_text(html)
            post_id = f"post_{slug}"
            conn.execute(
                """
                INSERT INTO square_posts(
                    id, author_id, title, content, cover_url, images, tag,
                    status, moderation_status, moderation_reason,
                    likes_count, comments_count, favorites_count, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,0,0,0,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    title=excluded.title, content=excluded.content, tag=excluded.tag,
                    status=excluded.status, updated_at=excluded.updated_at
                """,
                (
                    post_id, OFFICIAL_ID, title, content, "", "[]", tag,
                    "approved", "", "", now, now,
                ),
            )
            # 教程从应用货架(+混合 feed 的小程序卡)移除
            conn.execute("DELETE FROM registry_assets WHERE id=?", (f"plugin/{slug}",))
            print(f"  ✓ {slug}  正文 {len(content)} 字  → post_{slug}(已建帖 + 删小程序)")
        conn.commit()
        rows = conn.execute(
            "SELECT id, status, LENGTH(content) FROM square_posts WHERE author_id=? ORDER BY id",
            (OFFICIAL_ID,),
        ).fetchall()
        print("现有官方帖:")
        for r in rows:
            print(f"    {r[0]}  status={r[1]}  {r[2]} 字")
        left = conn.execute(
            "SELECT COUNT(*) FROM registry_assets WHERE author_id='official' AND kind='mini-app'"
        ).fetchone()[0]
        print(f"应用货架残留官方小程序:{left}(应为 0)")
    finally:
        conn.close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default=str(HERE.parent / "octo.db"), help="octo.db 路径")
    args = ap.parse_args()
    print(f"seeding official tutorial POSTS into {args.db}")
    seed(args.db)
    print("done.")
