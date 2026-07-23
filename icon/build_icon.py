#!/usr/bin/env python3
"""volumetap アイコン生成スクリプト.

108dp のアダプティブアイコン座標系でグリフを定義し、各用途の PNG を書き出す。
すべての図形は「白の中心線ストローク」として描く（外形を塗り→内側を punch out）。
アンチエイリアスはスーパーサンプリング（SS 倍で描画→LANCZOS 縮小）で得る。

    python3 icon/build_icon.py
"""

from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent / "preview"
SS = 4  # スーパーサンプリング倍率

# 決定稿の背景色。res/values/ic_launcher_background.xml と一致させること。
BLUE = (30, 136, 216)  # #1E88D8
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)

# --- グリフ幾何（108dp ビューポート、中心 54,54 基準） -----------------------
# 内容の外接は約 53.4 x 45.4dp。中心から最遠点でも半径 33dp 以内に収まるため、
# アダプティブアイコンのセーフゾーン（中央 66dp 円）を満たす。
SW = 3.4        # 主要ストローク幅
SW_RING = 3.0   # 波紋リングのストローク幅

PILL = (29.0, 33.0, 45.0, 75.0)   # ボリュームロッカー（左）
PILL_R = 8.0
PHONE = (57.0, 35.0, 79.0, 73.0)  # スマートフォン（右）
PHONE_R = 3.5

DOT_C, DOT_R = (37.0, 54.0), 4.2          # ロッカー中央のドット
PLUS_C, PLUS_ARM = (37.0, 42.5), 4.0      # ＋
MINUS_C = (37.0, 65.5)                    # −
RIPPLE_C, RIPPLE_R = (68.0, 54.0), 6.6    # 波紋リング
RIPPLE_DOT_R = 2.6                        # 波紋中心
LINE = (40.0, 54.0, 61.4, 54.0)           # 接続線


def draw_glyph(d, k, ox, oy, punch):
    """グリフを描く。k=1dp あたりのピクセル数、(ox,oy)=108 座標系の原点オフセット。

    punch は「内側をくり抜く色」。前景レイヤーでは透明、
    不透明な背景を持つ版では背景色そのものを渡す。
    """

    def P(x, y):
        return (ox + x * k, oy + y * k)

    def rrect(box, r, w, color):
        """中心線ストロークの角丸矩形。外形を塗って内側を punch する。"""
        x0, y0, x1, y1 = box
        d.rounded_rectangle(
            [*P(x0 - w / 2, y0 - w / 2), *P(x1 + w / 2, y1 + w / 2)],
            radius=(r + w / 2) * k, fill=color,
        )
        d.rounded_rectangle(
            [*P(x0 + w / 2, y0 + w / 2), *P(x1 - w / 2, y1 - w / 2)],
            radius=max(r - w / 2, 0) * k, fill=punch,
        )

    def ring(c, r, w, color):
        cx, cy = c
        d.ellipse([*P(cx - r - w / 2, cy - r - w / 2), *P(cx + r + w / 2, cy + r + w / 2)],
                  fill=color)
        d.ellipse([*P(cx - r + w / 2, cy - r + w / 2), *P(cx + r - w / 2, cy + r - w / 2)],
                  fill=punch)

    def disc(c, r, color):
        cx, cy = c
        d.ellipse([*P(cx - r, cy - r), *P(cx + r, cy + r)], fill=color)

    def bar(x0, y0, x1, y1, w, color):
        d.line([*P(x0, y0), *P(x1, y1)], fill=color, width=int(round(w * k)))
        # 端点を丸める（round cap 相当）
        for cx, cy in ((x0, y0), (x1, y1)):
            disc((cx, cy), w / 2, color)

    # 1. ロッカー外形 → 内側を punch
    rrect(PILL, PILL_R, SW, WHITE)
    # 2. スマートフォン外形 → 画面部を punch
    rrect(PHONE, PHONE_R, SW, WHITE)
    # 3. ロッカー内の記号（punch 後に描く）
    px, py = PLUS_C
    bar(px - PLUS_ARM, py, px + PLUS_ARM, py, SW, WHITE)
    bar(px, py - PLUS_ARM, px, py + PLUS_ARM, SW, WHITE)
    mx, my = MINUS_C
    bar(mx - PLUS_ARM, my, mx + PLUS_ARM, my, SW, WHITE)
    disc(DOT_C, DOT_R, WHITE)
    # 4. 波紋
    ring(RIPPLE_C, RIPPLE_R, SW_RING, WHITE)
    disc(RIPPLE_C, RIPPLE_DOT_R, WHITE)
    # 5. 接続線（スマートフォン左辺をまたぐので最後に描く）
    bar(*LINE, SW, WHITE)


def render(size, bg=None, content_scale=None):
    """size ピクセル四方の画像を返す。bg=None なら前景のみ（透過）。

    content_scale を与えると、108dp ビューポートではなくその倍率でグリフを配置する
    （マスクのないストア用アイコン向け）。
    """
    n = size * SS
    img = Image.new("RGBA", (n, n), (*bg, 255) if bg else CLEAR)
    d = ImageDraw.Draw(img)
    punch = (*bg, 255) if bg else CLEAR

    if content_scale is None:
        k = n / 108.0
        ox = oy = 0.0
    else:
        k = n * content_scale / 108.0
        ox = oy = (n - 108.0 * k) / 2.0

    draw_glyph(d, k, ox, oy, punch)
    return img.resize((size, size), Image.LANCZOS)


def mask(img, squircle=False):
    """アダプティブアイコンのマスク適用プレビュー（中央 72/108 を切り出す）。"""
    n = img.size[0]
    v = int(n * 72 / 108)
    off = (n - v) // 2
    body = img.crop((off, off, off + v, off + v))

    m = Image.new("L", (v * SS, v * SS), 0)
    md = ImageDraw.Draw(m)
    if squircle:
        md.rounded_rectangle([0, 0, v * SS - 1, v * SS - 1], radius=v * SS * 0.28, fill=255)
    else:
        md.ellipse([0, 0, v * SS - 1, v * SS - 1], fill=255)
    m = m.resize((v, v), Image.LANCZOS)

    out = Image.new("RGBA", (v, v), CLEAR)
    out.paste(body, (0, 0), m)
    return out


def main():
    OUT.mkdir(parents=True, exist_ok=True)

    # 前景レイヤー（透過・白グリフ）— そのままモノクロレイヤーにも流用できる
    fg = render(432)
    fg.save(OUT / "ic_launcher_foreground_432.png")
    fg.save(OUT / "ic_launcher_monochrome_432.png")

    full = Image.new("RGBA", (432, 432), (*BLUE, 255))
    full.alpha_composite(fg)
    full.save(OUT / "adaptive_full.png")

    circ = mask(full)
    sq = mask(full, squircle=True)
    circ.save(OUT / "masked_circle.png")
    sq.save(OUT / "masked_squircle.png")

    # Play ストア用: 512x512・不透明・影も角丸もなし
    render(512, bg=BLUE, content_scale=1.42).convert("RGB").save(OUT / "play_store_512.png")

    # プレビューシート: 円マスク / 角丸マスク / 48dp 実寸
    tile, pad = 288, 24
    sheet = Image.new("RGBA", (tile * 3 + pad * 4, tile + pad * 2), (245, 245, 245, 255))
    small = circ.resize((48, 48), Image.LANCZOS).resize((tile, tile), Image.NEAREST)
    for col, im in enumerate((circ, sq, small)):
        sheet.alpha_composite(im.resize((tile, tile), Image.LANCZOS), (pad + col * (tile + pad), pad))
    sheet.convert("RGB").save(OUT / "preview_sheet.png")

    for p in sorted(OUT.iterdir()):
        print(f"  {p.name}  {Image.open(p).size}")


if __name__ == "__main__":
    main()
