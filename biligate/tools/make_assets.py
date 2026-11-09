#!/usr/bin/env python3
"""Generates the launcher icons and the preview mockup.

Run from the project root:
    python tools/make_assets.py
Requires Pillow and a CJK font (Microsoft YaHei on Windows).
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_DIR = os.environ.get("WINDIR", r"C:\Windows") + r"\Fonts"
FONT_REG = os.path.join(FONT_DIR, "msyh.ttc")
FONT_BOLD = os.path.join(FONT_DIR, "msyhbd.ttc")

INK = "#0E1014"
CARD = "#171A21"
CARD_LINE = "#262B36"
DIVIDER = "#262A33"
TEXT = "#F2F3F5"
TEXT2 = "#A7ADB8"
TEXT3 = "#6F7684"
ACCENT = "#FB7299"
ACCENT_TRACK = "#723C51"
OK = "#4ADE80"
WARN = "#FBBF24"


def font(size, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT_REG, size)


# --------------------------------------------------------------------------
# launcher icon
# --------------------------------------------------------------------------
def make_icon(size):
    k = 4
    s = size * k
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))

    grad = Image.new("RGB", (s, s), "#FB7299")
    gd = ImageDraw.Draw(grad)
    top = (255, 145, 178)
    bottom = (226, 66, 112)
    for y in range(s):
        t = y / max(1, s - 1)
        gd.line([(0, y), (s, y)],
                fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))

    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, s - 1, s - 1], radius=s // 5, fill=255)
    img.paste(grad, (0, 0), mask)

    d = ImageDraw.Draw(img)
    body_w = int(s * 0.42)
    body_h = int(s * 0.30)
    bx0 = (s - body_w) // 2
    by0 = int(s * 0.50)
    sh_w = int(body_w * 0.62)
    sh_h = int(body_h * 0.95)
    sx0 = (s - sh_w) // 2
    d.arc([sx0, by0 - sh_h, sx0 + sh_w, by0 + sh_h * 0.6],
          start=180, end=360, fill="white", width=int(s * 0.055))
    d.rounded_rectangle([bx0, by0, bx0 + body_w, by0 + body_h],
                        radius=int(s * 0.07), fill="white")
    r = int(s * 0.035)
    cx = s // 2
    cy = by0 + body_h // 2
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill="#E8507E")
    d.rounded_rectangle([cx - r // 2, cy, cx + r // 2, cy + int(s * 0.075)],
                        radius=r // 2, fill="#E8507E")

    return img.resize((size, size), Image.LANCZOS)


def write_icons():
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    base = os.path.join(ROOT, "app", "src", "main", "res")
    for name, px in densities.items():
        folder = os.path.join(base, "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)
        make_icon(px).save(os.path.join(folder, "ic_launcher.png"))


# --------------------------------------------------------------------------
# mockup helpers
# --------------------------------------------------------------------------
def status_bar(d, w, k, fg=TEXT):
    d.text((24 * k, 16 * k), "22:41", font=font(15 * k), fill=fg)
    x = w - 78 * k
    d.rounded_rectangle([x, 21 * k, x + 26 * k, 34 * k], radius=3 * k,
                        outline=fg, width=max(1, int(1.4 * k)))
    d.rectangle([x + 26 * k, 25 * k, x + 29 * k, 31 * k], fill=fg)
    d.rectangle([x + 3 * k, 24 * k, x + 21 * k, 31 * k], fill=fg)
    d.line([x - 30 * k, 34 * k, x - 26 * k, 30 * k, x - 22 * k, 34 * k],
           fill=fg, width=max(1, int(1.6 * k)))


def shield_screen(w, h, k):
    img = Image.new("RGB", (w, h), "#0F1115")
    d = ImageDraw.Draw(img)
    status_bar(d, w, k)

    d.rounded_rectangle([20 * k, 52 * k, 556 * k, 112 * k], radius=30 * k, fill="#1C1F27")
    cx, cy = 54 * k, 82 * k
    d.ellipse([cx - 9 * k, cy - 9 * k, cx + 9 * k, cy + 9 * k],
              outline="#7B818D", width=int(2 * k))
    d.line([cx + 7 * k, cy + 7 * k, cx + 15 * k, cy + 15 * k],
           fill="#7B818D", width=int(2.4 * k))
    d.text((76 * k, 68 * k), "搜索", font=font(17 * k), fill="#7B818D")

    d.ellipse([572 * k, 64 * k, 612 * k, 104 * k], fill="#2B3040")
    d.text((580 * k, 72 * k), "我", font=font(15 * k), fill=TEXT2)

    d.rectangle([0, 118 * k, w, h], fill="#0B0D10")

    mid = (118 * k + h) // 2
    lx, ly = w // 2, mid - 130 * k
    d.arc([lx - 30 * k, ly - 48 * k, lx + 30 * k, ly + 22 * k],
          start=180, end=360, fill=ACCENT, width=int(9 * k))
    d.rounded_rectangle([lx - 44 * k, ly, lx + 44 * k, ly + 78 * k],
                        radius=14 * k, fill=ACCENT)

    d.text((w // 2, mid - 10 * k), "首页推荐已关闭", font=font(26 * k, True),
           fill=TEXT, anchor="mm")
    d.text((w // 2, mid + 34 * k), "想看什么，请点击顶部的搜索栏",
           font=font(15 * k), fill=TEXT2, anchor="mm")

    d.line([w // 2 - 20 * k, mid + 72 * k, w // 2 + 20 * k, mid + 72 * k],
           fill=DIVIDER, width=int(1 * k))

    d.text((w // 2, mid + 96 * k), "想恢复首页浏览？", font=font(12 * k),
           fill=TEXT3, anchor="mm")
    d.text((w // 2, mid + 120 * k), "打开 BiliGate 并输入密码关闭拦截",
           font=font(12 * k), fill=TEXT3, anchor="mm")

    d.rounded_rectangle([w // 2 - 56 * k, h - 18 * k, w // 2 + 56 * k, h - 12 * k],
                        radius=3 * k, fill="#4A4D55")
    return img


def card(d, x0, y0, x1, y1, k, radius=20):
    d.rounded_rectangle([x0 * k, y0 * k, x1 * k, y1 * k], radius=radius * k,
                        fill=CARD, outline=CARD_LINE, width=max(1, int(1 * k)))


def settings_screen(w, h, k):
    img = Image.new("RGB", (w, h), INK)
    d = ImageDraw.Draw(img)
    status_bar(d, w, k)

    d.text((20 * k, 52 * k), "BiliGate", font=font(30 * k, True), fill=TEXT)
    d.text((20 * k, 100 * k), "哔哩哔哩首页闸 · 关掉推荐流，只留下搜索",
           font=font(11 * k), fill=TEXT2)

    card(d, 20, 132, w - 20, 232, k)
    d.text((36 * k, 150 * k), "无障碍服务", font=font(13 * k), fill=TEXT2)
    d.text((w - 36 * k, 150 * k), "已开启", font=font(13 * k, True), fill=OK, anchor="ra")
    d.text((36 * k, 176 * k), "拦截依赖系统的无障碍服务", font=font(9 * k), fill=TEXT3)

    card(d, 20, 244, w - 20, 400, k)
    d.text((36 * k, 264 * k), "拦截首页推荐", font=font(15 * k, True), fill=TEXT)
    d.text((36 * k, 292 * k), "已关闭推荐流，首页只能搜索", font=font(10 * k), fill=TEXT3)
    sx, sy = w - 116 * k, 268 * k
    d.rounded_rectangle([sx, sy, sx + 80 * k, sy + 44 * k], radius=22 * k, fill=ACCENT_TRACK)
    d.ellipse([sx + 40 * k, sy + 4 * k, sx + 76 * k, sy + 40 * k], fill=ACCENT)
    d.line([36 * k, 330 * k, (w - 36) * k, 330 * k], fill=DIVIDER, width=max(1, int(1 * k)))
    d.rounded_rectangle([36 * k, 346 * k, 290 * k, 388 * k], radius=10 * k, fill="#1E222B")
    d.text((163 * k, 367 * k), "修改密码", font=font(12 * k), fill=TEXT, anchor="mm")
    d.rounded_rectangle([302 * k, 346 * k, 556 * k, 388 * k], radius=10 * k, fill="#1E222B")
    d.text((429 * k, 367 * k), "使用说明", font=font(12 * k), fill=TEXT, anchor="mm")

    card(d, 20, 412, w - 20, 640, k)
    d.text((36 * k, 430 * k), "小米 / 澎湃 OS 适配", font=font(13 * k, True), fill=TEXT)
    d.rounded_rectangle([w - 122 * k, 428 * k, w - 36 * k, 456 * k], radius=14 * k,
                        fill="#1E222B")
    d.text((w - 79 * k, 442 * k), "已识别 MIUI", font=font(9 * k), fill=TEXT2, anchor="mm")
    d.text((36 * k, 462 * k), "建议三项都授权，避免遮罩不显示或重启后失效",
           font=font(9 * k), fill=TEXT3)

    rows = [
        ("显示悬浮窗", "没有它，小米上遮罩可能不显示", "已授权", OK),
        ("自启动", "重启手机后让服务自动恢复", "建议开启", TEXT3),
        ("省电策略 · 无限制", "防止后台被清理后拦截失效", "无限制", OK),
    ]
    y = 492
    for i, (title, desc, state, color) in enumerate(rows):
        if i:
            d.line([36 * k, (y - 8) * k, (w - 36) * k, (y - 8) * k],
                   fill=DIVIDER, width=max(1, int(1 * k)))
        d.text((36 * k, y * k), title, font=font(12 * k), fill=TEXT)
        d.text((36 * k, (y + 22) * k), desc, font=font(9 * k), fill=TEXT3)
        d.text((w - 36 * k, (y + 8) * k), state, font=font(10 * k), fill=color, anchor="ra")
        y += 52

    card(d, 20, 652, w - 20, 790, k)
    d.text((36 * k, 670 * k), "顶部留白微调", font=font(12 * k, True), fill=TEXT)
    d.ellipse([36 * k, 700 * k, 50 * k, 714 * k], fill=ACCENT)
    d.text((60 * k, 700 * k), "自动识别搜索栏位置", font=font(10 * k), fill=TEXT2)
    d.rounded_rectangle([36 * k, 730 * k, (w - 36) * k, 736 * k], radius=3 * k, fill="#3A3F4A")
    d.rounded_rectangle([36 * k, 730 * k, 300 * k, 736 * k], radius=3 * k, fill=ACCENT)
    d.ellipse([290 * k, 723 * k, 306 * k, 743 * k], fill=ACCENT)
    d.text((w - 36 * k, 752 * k), "64 dp", font=font(9 * k), fill=TEXT2, anchor="ra")

    d.text((w // 2, 818 * k), "忘记密码？", font=font(10 * k), fill=TEXT3, anchor="mm")
    d.text((w // 2, 852 * k), "不联网 · 密码和设置只保存在本机",
           font=font(9 * k), fill=TEXT3, anchor="mm")
    d.text((w // 2, 874 * k), "v1.0", font=font(9 * k), fill=TEXT3, anchor="mm")

    d.rounded_rectangle([w // 2 - 56 * k, h - 18 * k, w // 2 + 56 * k, h - 12 * k],
                        radius=3 * k, fill="#4A4D55")
    return img


def compose():
    pw, ph = 640, 1360
    canvas = Image.new("RGB", (1440, 1500), "#0A0C10")
    d = ImageDraw.Draw(canvas)

    screens = [
        (shield_screen(pw, ph, 1), "哔哩哔哩首页：只留下搜索栏"),
        (settings_screen(pw, ph, 1), "BiliGate 设置：密码开关 + 小米授权"),
    ]
    for i, (screen, caption) in enumerate(screens):
        x = 40 + i * 720
        d.rounded_rectangle([x - 10, 50, x + pw + 10, 50 + ph + 10], radius=40,
                            fill="#23262E", outline="#3A4050", width=2)
        canvas.paste(screen, (x, 55))
        d.text((x + pw // 2, 50 + ph + 40), caption, font=font(20), fill=TEXT2, anchor="mm")

    d.text((720, 1470), "BiliGate · 示意图", font=font(16), fill=TEXT3, anchor="mm")
    out = os.path.join(ROOT, "docs")
    os.makedirs(out, exist_ok=True)
    canvas.save(os.path.join(out, "preview.png"))
    print("wrote", os.path.join(out, "preview.png"))


def main():
    write_icons()
    compose()
    print("done")


if __name__ == "__main__":
    main()
