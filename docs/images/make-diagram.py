#!/usr/bin/env python3
"""Writes the "how it works" diagram once per README language.

Run from the repository root:  python3 docs/images/make-diagram.py
Add a language by adding one entry to TEXT and linking the new file from its README.
"""
from pathlib import Path
from xml.sax.saxutils import escape

OUT = Path(__file__).parent

# group labels, then (title, note) for the four steps
TEXT = {
    "": {
        "player": "Player's game", "server": "Minecraft server",
        "steps": [("Figura", "unchanged"), ("Local proxy", "127.0.0.1"),
                  ("Game connection", "no extra port"), ("Avatar storage", "inside the server")],
    },
    ".th": {
        "player": "เกมของผู้เล่น", "server": "เซิร์ฟเวอร์ Minecraft",
        "steps": [("Figura", "ไม่ถูกแก้"), ("Proxy ในเครื่อง", "127.0.0.1"),
                  ("การเชื่อมต่อของเกม", "ไม่ต้องเปิดพอร์ตเพิ่ม"), ("ที่เก็บอวาตาร์", "อยู่ในเซิร์ฟ")],
    },
    ".zh-CN": {
        "player": "玩家的游戏", "server": "Minecraft 服务器",
        "steps": [("Figura", "未修改"), ("本地代理", "127.0.0.1"),
                  ("游戏连接", "无需额外端口"), ("模型存储", "在服务器内")],
    },
    ".ja": {
        "player": "プレイヤーのゲーム", "server": "Minecraft サーバー",
        "steps": [("Figura", "無改造"), ("ローカルプロキシ", "127.0.0.1"),
                  ("ゲーム接続", "追加ポート不要"), ("アバター保存先", "サーバー内")],
    },
}

FONT = ("system-ui, -apple-system, 'Segoe UI', Roboto, 'Noto Sans', 'Noto Sans Thai', "
        "'Noto Sans CJK SC', 'Noto Sans CJK JP', sans-serif")
BG, CARD, LINE = "#0f1b2d", "#16263d", "#2a3f5f"
TEXT_MAIN, TEXT_DIM = "#e8f1ff", "#9fb3cc"
TEAL, ORANGE, CREAM = "#2dd4bf", "#fb923c", "#fef3c7"

CARD_W, CARD_H, CARD_Y = 180, 132, 96
CARD_X = [44, 264, 502, 756]


def icon(kind, cx, cy):
    """Small drawings, centred on (cx, cy)."""
    if kind == 0:  # a cube head with ears: the player's avatar
        return (f'<path d="M{cx - 20} {cy - 14} l6 -14 l8 14 z M{cx + 6} {cy - 14} l8 -14 l6 14 z" fill="{ORANGE}"/>'
                f'<rect x="{cx - 22}" y="{cy - 16}" width="44" height="38" rx="9" fill="{TEAL}"/>'
                f'<circle cx="{cx - 9}" cy="{cy + 1}" r="3.5" fill="{BG}"/><circle cx="{cx + 9}" cy="{cy + 1}" r="3.5" fill="{BG}"/>')
    if kind == 1:  # two arrows passing each other: traffic handed on in both directions
        return (f'<g fill="none" stroke="{TEAL}" stroke-width="5" stroke-linecap="round" stroke-linejoin="round">'
                f'<path d="M{cx - 24} {cy - 9} h44 m-11 -10 l11 10 l-11 10"/></g>'
                f'<g fill="none" stroke="{ORANGE}" stroke-width="5" stroke-linecap="round" stroke-linejoin="round">'
                f'<path d="M{cx + 24} {cy + 13} h-44 m11 -10 l-11 10 l11 10"/></g>')
    if kind == 2:  # a tube with avatar cubes travelling inside
        return (f'<rect x="{cx - 44}" y="{cy - 13}" width="88" height="30" rx="15" fill="none" stroke="{TEAL}" stroke-width="5"/>'
                f'<rect x="{cx - 29}" y="{cy - 5}" width="14" height="14" rx="3" fill="{ORANGE}"/>'
                f'<rect x="{cx - 7}" y="{cy - 5}" width="14" height="14" rx="3" fill="{CREAM}"/>'
                f'<rect x="{cx + 15}" y="{cy - 5}" width="14" height="14" rx="3" fill="{TEAL}"/>')
    # a server box holding an avatar
    return (f'<rect x="{cx - 28}" y="{cy - 20}" width="56" height="44" rx="9" fill="{TEAL}"/>'
            f'<rect x="{cx - 19}" y="{cy - 11}" width="22" height="20" rx="4" fill="{BG}"/>'
            f'<rect x="{cx - 14}" y="{cy - 6}" width="12" height="10" rx="2.5" fill="{ORANGE}"/>'
            f'<circle cx="{cx + 14}" cy="{cy - 7}" r="3.5" fill="{CREAM}"/><circle cx="{cx + 14}" cy="{cy + 5}" r="3.5" fill="{BG}"/>')


def arrow(x1, x2, y):
    """A two-headed arrow: requests go one way and answers come back."""
    return (f'<g fill="none" stroke="{TEXT_DIM}" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">'
            f'<path d="M{x1 + 6} {y} H{x2 - 6}"/>'
            f'<path d="M{x1 + 14} {y - 7} l-8 7 l8 7"/><path d="M{x2 - 14} {y - 7} l8 7 l-8 7"/></g>')


def render(text):
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 980 280" width="980" height="280" '
        f'font-family="{FONT}" role="img" aria-label="{escape(" - ".join(t for t, _ in text["steps"]))}">',
        f'<rect width="980" height="280" rx="18" fill="{BG}"/>',
        # the two machines
        f'<rect x="22" y="44" width="444" height="208" rx="14" fill="none" stroke="{LINE}" stroke-width="2" stroke-dasharray="7 7"/>',
        f'<text x="40" y="74" font-size="15" fill="{TEXT_DIM}">{escape(text["player"])}</text>',
        f'<rect x="734" y="44" width="224" height="208" rx="14" fill="none" stroke="{LINE}" stroke-width="2" stroke-dasharray="7 7"/>',
        f'<text x="752" y="74" font-size="15" fill="{TEXT_DIM}">{escape(text["server"])}</text>',
    ]
    middle = CARD_Y + CARD_H // 2
    for i in range(3):
        parts.append(arrow(CARD_X[i] + CARD_W, CARD_X[i + 1], middle))
    for i, (title, note) in enumerate(text["steps"]):
        x, cx = CARD_X[i], CARD_X[i] + CARD_W // 2
        parts.append(f'<rect x="{x}" y="{CARD_Y}" width="{CARD_W}" height="{CARD_H}" rx="14" fill="{CARD}" stroke="{LINE}" stroke-width="1.5"/>')
        parts.append(icon(i, cx, CARD_Y + 42))
        parts.append(f'<text x="{cx}" y="{CARD_Y + 94}" font-size="16" font-weight="700" text-anchor="middle" fill="{TEXT_MAIN}">{escape(title)}</text>')
        parts.append(f'<text x="{cx}" y="{CARD_Y + 116}" font-size="13" text-anchor="middle" fill="{TEXT_DIM}">{escape(note)}</text>')
    parts.append("</svg>")
    return "\n".join(parts) + "\n"


for suffix, text in TEXT.items():
    target = OUT / f"how-it-works{suffix}.svg"
    target.write_text(render(text), encoding="utf-8")
    print("wrote", target.name)
