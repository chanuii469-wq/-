"""방패 모양 아이콘 PNG 생성: python3 tools/make_icons.py"""
from pathlib import Path
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent.parent / "icons"


def shield(size):
    s = 4  # 슈퍼샘플링
    W = size * s
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = W * 0.08
    top, bot = m, W - m
    pts = [
        (W / 2, top), (W - m, top + W * 0.14), (W - m, W * 0.48),
        (W / 2, bot), (m, W * 0.48), (m, top + W * 0.14),
    ]
    d.polygon(pts, fill=(46, 125, 50, 255))
    # 체크 표시
    lw = max(2, int(W * 0.09))
    d.line([(W * 0.32, W * 0.50), (W * 0.45, W * 0.63), (W * 0.70, W * 0.36)],
           fill=(255, 255, 255, 255), width=lw, joint="curve")
    return img.resize((size, size), Image.LANCZOS)


OUT.mkdir(exist_ok=True)
for n in (16, 32, 48, 128):
    shield(n).save(OUT / f"icon{n}.png")
    print(f"icons/icon{n}.png")
