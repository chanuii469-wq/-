"""Adlull 아이콘 생성: 초승달 + 작은 별 (광고를 '잠재운다'는 뜻)

python3 tools/make_icons.py
 - 크롬 확장 icons/*.png
 - 안드로이드 벡터 아이콘(ic_moon.xml, ic_launcher_foreground.xml)과 구형 기기용 PNG
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "android/app/src/main/res"

BG = (67, 56, 202)        # 인디고 #4338CA
BG_TOP = (99, 102, 241)   # 그라데이션 위쪽 #6366F1
FG = (255, 255, 255)

# 24x24 좌표계 기준 도형
A = (11.0, 13.0, 8.0)     # 달 원 (cx, cy, r)
B = (15.6, 8.6, 6.6)      # 잘라낼 원
STAR = (18.3, 5.3, 2.6)   # 별 (cx, cy, 반지름)


def circle_intersections(c1, c2):
    (x0, y0, r0), (x1, y1, r1) = c1, c2
    d = math.hypot(x1 - x0, y1 - y0)
    a = (r0 * r0 - r1 * r1 + d * d) / (2 * d)
    h = math.sqrt(r0 * r0 - a * a)
    xm, ym = x0 + a * (x1 - x0) / d, y0 + a * (y1 - y0) / d
    return ((xm + h * (y1 - y0) / d, ym - h * (x1 - x0) / d),
            (xm - h * (y1 - y0) / d, ym + h * (x1 - x0) / d))


def star_points(cx, cy, r, inner=0.38, n=4):
    pts = []
    for i in range(n * 2):
        ang = -math.pi / 2 + i * math.pi / n
        rr = r if i % 2 == 0 else r * inner
        pts.append((cx + rr * math.cos(ang), cy + rr * math.sin(ang)))
    return pts


def svg_path():
    p1, p2 = circle_intersections(A, B)
    f = lambda v: f"{v:.3f}".rstrip("0").rstrip(".")
    moon = (f"M{f(p1[0])},{f(p1[1])}"
            f"A{f(A[2])},{f(A[2])} 0 1,0 {f(p2[0])},{f(p2[1])}"
            f"A{f(B[2])},{f(B[2])} 0 0,1 {f(p1[0])},{f(p1[1])}Z")
    sp = star_points(*STAR)
    star = "M" + "L".join(f"{f(x)},{f(y)}" for x, y in sp) + "Z"
    return moon + star


def vector(path, size_dp, viewport, group=""):
    inner = f'<path android:fillColor="#FFFFFFFF" android:pathData="{path}" />'
    if group:
        inner = f"<group {group}>\n        {inner}\n    </group>"
    return f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size_dp}dp" android:height="{size_dp}dp"
    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">
    {inner}
</vector>
'''


def png(size, rounded=True):
    s = 8
    W = size * s
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    # 세로 그라데이션 배경
    bg = Image.new("RGBA", (W, W))
    gd = ImageDraw.Draw(bg)
    for y in range(W):
        t = y / (W - 1)
        gd.line([(0, y), (W, y)], fill=tuple(int(BG_TOP[i] * (1 - t) + BG[i] * t) for i in range(3)) + (255,))
    mask = Image.new("L", (W, W), 0)
    md = ImageDraw.Draw(mask)
    if rounded:
        md.rounded_rectangle([0, 0, W - 1, W - 1], radius=int(W * 0.22), fill=255)
    else:
        md.ellipse([0, 0, W - 1, W - 1], fill=255)
    img.paste(bg, (0, 0), mask)

    # 도형 (24 단위 → 아이콘의 가운데 80% 영역)
    k = W * 0.8 / 24
    off = W * 0.1
    T = lambda x, y: (off + x * k, off + y * k)
    fg = Image.new("L", (W, W), 0)
    fd = ImageDraw.Draw(fg)
    ax, ay, ar = A
    fd.ellipse([*T(ax - ar, ay - ar), *T(ax + ar, ay + ar)], fill=255)
    bx, by, br = B
    fd.ellipse([*T(bx - br, by - br), *T(bx + br, by + br)], fill=0)
    fd.polygon([T(x, y) for x, y in star_points(*STAR)], fill=255)
    img.paste(Image.new("RGBA", (W, W), FG + (255,)), (0, 0), fg)
    return img.resize((size, size), Image.LANCZOS)


def main():
    path = svg_path()
    # 크롬 확장
    for n in (16, 32, 48, 128):
        png(n).save(ROOT / "icons" / f"icon{n}.png")
    # 안드로이드: 알림·타일용 아이콘
    (RES / "drawable/ic_moon.xml").write_text(vector(path, 24, 24))
    # 적응형 아이콘 전경: 108dp 중 안전 영역(가운데 66dp)에 맞춤
    (RES / "drawable/ic_launcher_foreground.xml").write_text(
        vector(path, 108, 108, 'android:scaleX="2.4" android:scaleY="2.4" android:translateX="25.2" android:translateY="25.2"'))
    (RES / "drawable/ic_launcher_background.xml").write_text('''<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <gradient android:angle="270" android:startColor="#FF6366F1" android:endColor="#FF4338CA" />
</shape>
''')
    # Android 7 이하용 PNG
    for d, n in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        png(n, rounded=False).save(RES / f"mipmap-{d}/ic_launcher.png")
    print("ok", path)


if __name__ == "__main__":
    main()
