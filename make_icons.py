"""
Генератор иконок NexVPN.

Почему так: исходник — 1254x1254 RGB БЕЗ альфы, фон залит почти чёрным.
Если такой файл положить в adaptive-icon foreground, лаунчер накладывает маску
поверх КАРТИНКИ С ФОНОМ -> получается чёрно-фиолетовая каша.

Правильно:
  * background  — сплошной тёмно-фиолетовый градиент (#0A0614 -> #1A0B33)
  * foreground  — щит, вырезанный по яркости: чёрный фон становится прозрачным,
                  свечение сохраняется через альфу
  * масштаб foreground = 62% от 108dp, чтобы влезть в safe zone (72dp) любой маски
"""

from PIL import Image, ImageDraw, ImageFilter
import os

SRC = r'C:\Users\AI\Downloads\Неоновый щит NexVpn с глобусом.png'
OUT = r'C:\Users\AI\Desktop\avatar\nexvpn\app\src\main\res'

BG_TOP = (10, 6, 20)
BG_BOT = (26, 11, 51)
ACCENT = (124, 58, 237)


def make_foreground(size=432):
    """Щит с прозрачным фоном. Чёрный -> alpha 0, яркое -> alpha 255."""
    im = Image.open(SRC).convert('RGB')
    # alpha по максимальному каналу: фон чёрный => прозрачно, неон => непрозрачно
    r, g, b = im.split()
    mx = Image.new('L', im.size)
    px_src = [max(r.getpixel((x, y)), g.getpixel((x, y)), b.getpixel((x, y)))
              for y in range(0, im.size[1], 1) for x in range(0, im.size[0], 1)]
    # быстрее через point на каждом канале
    alpha = Image.eval(im.convert('L'), lambda v: v)
    from PIL import ImageChops
    alpha = ImageChops.lighter(ImageChops.lighter(r, g), b)
    # мягкий порог: всё темнее 18 -> прозрачно, 18..60 -> плавный въезд
    alpha = alpha.point(lambda v: 0 if v < 18 else (255 if v > 60 else int((v - 18) * 255 / 42)))
    alpha = alpha.filter(ImageFilter.GaussianBlur(1.2))
    fg = im.convert('RGBA')
    fg.putalpha(alpha)
    # обрезаем по содержимому, чтобы щит занял максимум места
    bbox = alpha.getbbox()
    if bbox:
        fg = fg.crop(bbox)
    # вписываем в 62% от полотна, по центру
    inner = int(size * 0.62)
    fg.thumbnail((inner, inner), Image.LANCZOS)
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    canvas.paste(fg, ((size - fg.width) // 2, (size - fg.height) // 2), fg)
    return canvas


def make_background(size=432):
    """Тёмно-фиолетовый градиент + лёгкое свечение по центру."""
    base = Image.new('RGB', (size, size), BG_TOP)
    d = ImageDraw.Draw(base)
    for y in range(size):
        t = y / max(size - 1, 1)
        col = tuple(int(BG_TOP[i] + (BG_BOT[i] - BG_TOP[i]) * t) for i in range(3))
        d.line([(0, y), (size, y)], fill=col)
    glow = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    cx, cy = size // 2, int(size * 0.5)
    rad = int(size * 0.34)
    gd.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=(*ACCENT, 70))
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.10))
    base = Image.alpha_composite(base.convert('RGBA'), glow)
    return base.convert('RGB')


def rounded(im, radius_ratio=0.22):
    im = im.convert('RGBA')
    size = im.size[0]
    mask = Image.new('L', im.size, 0)
    d = ImageDraw.Draw(mask)
    r = int(size * radius_ratio)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    im.putalpha(mask)
    return im


def legacy_icon(size):
    """Старые лаунчеры: щит на градиенте, в круге."""
    bg = make_background(size).convert('RGBA')
    fg = make_foreground(size)
    im = Image.alpha_composite(bg, fg)
    return im


# ---------------- запись файлов ----------------

sizes = {'mipmap-mdpi': 48, 'mipmap-hdpi': 72, 'mipmap-xhdpi': 96,
         'mipmap-xxhdpi': 144, 'mipmap-xxxhdpi': 192}

for d, s in sizes.items():
    dd = os.path.join(OUT, d)
    os.makedirs(dd, exist_ok=True)
    icon = legacy_icon(s)
    # квадратная со скруглением
    rounded(icon, 0.20).save(os.path.join(dd, 'ic_launcher.png'))
    # круглая
    im = icon.copy()
    mask = Image.new('L', (s, s), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, s - 1, s - 1], fill=255)
    im.putalpha(mask)
    im.save(os.path.join(dd, 'ic_launcher_round.png'))

    # слои adaptive (в mipmap-* кладём уже готовые нужного размера)
    fg_s = int(s * 108 / 48)
    make_foreground(fg_s).save(os.path.join(dd, 'ic_launcher_foreground.png'))
    make_background(fg_s).save(os.path.join(dd, 'ic_launcher_background.png'))

# anydpi-v26: только XML, без дублирующих PNG
a26 = os.path.join(OUT, 'mipmap-anydpi-v26')
os.makedirs(a26, exist_ok=True)
for f in os.listdir(a26):
    if f.endswith('.png'):
        os.remove(os.path.join(a26, f))

xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
       '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
       '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
       '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
       '    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />\n'
       '</adaptive-icon>\n')
for name in ('ic_launcher.xml', 'ic_launcher_round.xml'):
    with open(os.path.join(a26, name), 'w', encoding='utf-8') as f:
        f.write(xml)

# иконка для маркета 512x512
legacy_icon(512).save(os.path.join(OUT, '..', '..', 'playstore_icon_512.png'))

print('OK: иконки пересобраны')
