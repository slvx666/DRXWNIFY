import math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageOps, ImageEnhance
import shutil

SCALE = 4  # 4x supersampling for ultra-crisp anti-aliased lines

def draw_star(draw, cx, cy, rx, ry, fill, outline=None):
    """Draws a 4-pointed gothic star / diamond ornament."""
    pts = [
        (cx, cy - ry),
        (cx + rx * 0.28, cy - ry * 0.28),
        (cx + rx, cy),
        (cx + rx * 0.28, cy + ry * 0.28),
        (cx, cy + ry),
        (cx - rx * 0.28, cy + ry * 0.28),
        (cx - rx, cy),
        (cx - rx * 0.28, cy - ry * 0.28),
    ]
    draw.polygon(pts, fill=fill, outline=outline)

def generate_album_frame(out_path, size=1200):
    """
    1200x1200: Album Frame matching Reference 01
    - Double thin concentric circles
    - Subtle engraved tick marks at cardinal and diagonal positions
    - Small gothic point at top and bottom
    - Transparent center for album cover
    """
    S = size * SCALE
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)
    cx, cy = S // 2, S // 2
    
    # Outer radius: ~530 * SCALE, Inner ring radius: ~485 * SCALE, Cover radius: ~470 * SCALE
    r_outer = int(530 * SCALE)
    r_inner = int(488 * SCALE)
    
    stroke_outer = max(1, int(2.2 * SCALE))
    stroke_inner = max(1, int(2.5 * SCALE))
    
    col_main = (220, 220, 220, 230)
    col_subtle = (180, 180, 180, 160)
    
    # 1. Outer circle
    draw.ellipse([cx - r_outer, cy - r_outer, cx + r_outer, cy + r_outer], outline=col_subtle, width=stroke_outer)
    
    # 2. Inner circle
    draw.ellipse([cx - r_inner, cy - r_inner, cx + r_inner, cy + r_inner], outline=col_main, width=stroke_inner)
    
    # 3. Intermediate micro-tick markings between outer and inner circles
    for deg in range(0, 360, 15):
        rad = math.radians(deg)
        cos_a, sin_a = math.cos(rad), math.sin(rad)
        
        is_cardinal = (deg % 90 == 0)
        is_sub = (deg % 45 == 0)
        
        len_tick = (16 if is_cardinal else (10 if is_sub else 5)) * SCALE
        
        # Draw small tick extending inwards from outer ring
        p1 = (cx + (r_outer) * cos_a, cy + (r_outer) * sin_a)
        p2 = (cx + (r_outer - len_tick) * cos_a, cy + (r_outer - len_tick) * sin_a)
        w = max(1, int((2.0 if is_cardinal else 1.2) * SCALE))
        draw.line([p1, p2], fill=col_main if is_cardinal else col_subtle, width=w)
    
    # 4. Small decorative symbols at cardinal points
    # Top finial (12 o'clock)
    top_y = cy - r_outer
    draw_star(draw, cx, top_y - 12 * SCALE, 7 * SCALE, 16 * SCALE, fill=col_main)
    draw.line([(cx, top_y), (cx, top_y - 28 * SCALE)], fill=col_main, width=max(1, int(2 * SCALE)))
    
    # Bottom finial (6 o'clock)
    bot_y = cy + r_outer
    draw_star(draw, cx, bot_y + 12 * SCALE, 7 * SCALE, 16 * SCALE, fill=col_main)
    draw.line([(cx, bot_y), (cx, bot_y + 28 * SCALE)], fill=col_main, width=max(1, int(2 * SCALE)))
    
    # Left & Right points (9 and 3 o'clock)
    draw_star(draw, cx - r_outer - 8 * SCALE, cy, 12 * SCALE, 6 * SCALE, fill=col_main)
    draw_star(draw, cx + r_outer + 8 * SCALE, cy, 12 * SCALE, 6 * SCALE, fill=col_main)

    # Downsample with Lanczos
    res = im.resize((size, size), Image.Resampling.LANCZOS)
    res.save(out_path, 'PNG')
    print(f'Generated {out_path}')

def generate_play_button_frame(out_path, size=512):
    """
    512x512: Play button frame matching Reference 01
    - Clean thin circular frame
    - Small symmetric top and bottom engraved finials
    - 100% transparent center
    """
    S = size * SCALE
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)
    cx, cy = S // 2, S // 2
    
    r_main = int(196 * SCALE)
    r_outer = int(206 * SCALE)
    
    col_main = (240, 240, 240, 245)
    col_faint = (180, 180, 180, 130)
    
    # Double ring: primary strong ring, subtle outer hairline
    draw.ellipse([cx - r_main, cy - r_main, cx + r_main, cy + r_main], outline=col_main, width=max(1, int(3 * SCALE)))
    draw.ellipse([cx - r_outer, cy - r_outer, cx + r_outer, cy + r_outer], outline=col_faint, width=max(1, int(1.2 * SCALE)))
    
    # Top symmetric gothic point / finial
    top_y = cy - r_outer
    draw_star(draw, cx, top_y - 12 * SCALE, 6 * SCALE, 15 * SCALE, fill=col_main)
    draw.line([(cx, top_y), (cx, top_y - 25 * SCALE)], fill=col_main, width=max(1, int(2 * SCALE)))
    
    # Bottom symmetric gothic point / finial
    bot_y = cy + r_outer
    draw_star(draw, cx, bot_y + 12 * SCALE, 6 * SCALE, 15 * SCALE, fill=col_main)
    draw.line([(cx, bot_y), (cx, bot_y + 25 * SCALE)], fill=col_main, width=max(1, int(2 * SCALE)))
    
    # Cardinal ticks on left and right
    draw.line([(cx - r_outer - 6 * SCALE, cy), (cx - r_main + 6 * SCALE, cy)], fill=col_main, width=max(1, int(1.8 * SCALE)))
    draw.line([(cx + r_main - 6 * SCALE, cy), (cx + r_outer + 6 * SCALE, cy)], fill=col_main, width=max(1, int(1.8 * SCALE)))

    res = im.resize((size, size), Image.Resampling.LANCZOS)
    res.save(out_path, 'PNG')
    print(f'Generated {out_path}')

def generate_control_button_frame(out_path, size=384):
    """
    384x384: Universal control button frame (Prev/Next/Queue/Sleep...)
    - Thin circular contour
    - Minimal engraved detail
    - Transparent center
    """
    S = size * SCALE
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)
    cx, cy = S // 2, S // 2
    
    r = int(152 * SCALE)
    col_main = (200, 200, 200, 200)
    col_tick = (220, 220, 220, 220)
    
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col_main, width=max(1, int(2.0 * SCALE)))
    
    # 4 small micro-ticks at cardinal points
    t_len = 5 * SCALE
    draw.line([(cx, cy - r - t_len), (cx, cy - r + t_len)], fill=col_tick, width=max(1, int(1.8 * SCALE)))
    draw.line([(cx, cy + r - t_len), (cx, cy + r + t_len)], fill=col_tick, width=max(1, int(1.8 * SCALE)))
    draw.line([(cx - r - t_len, cy), (cx - r + t_len, cy)], fill=col_tick, width=max(1, int(1.8 * SCALE)))
    draw.line([(cx + r - t_len, cy), (cx + r + t_len, cy)], fill=col_tick, width=max(1, int(1.8 * SCALE)))

    res = im.resize((size, size), Image.Resampling.LANCZOS)
    res.save(out_path, 'PNG')
    print(f'Generated {out_path}')

def generate_progress_decoration(out_path, width=1800, height=120):
    """
    1800x120: Horizontal progress decoration
    - Thin horizontal line
    - Small 4-pointed diamond / star ornament in the center
    """
    W, H = width * SCALE, height * SCALE
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)
    cy = H // 2
    cx = W // 2
    
    col_line = (140, 140, 140, 180)
    col_star = (235, 235, 235, 245)
    
    # Line
    line_w = max(1, int(1.5 * SCALE))
    draw.line([(40 * SCALE, cy), (W - 40 * SCALE, cy)], fill=col_line, width=line_w)
    
    # Central 4-pointed star
    draw_star(draw, cx, cy, 14 * SCALE, 26 * SCALE, fill=col_star)
    # Small center dot inside star
    draw.ellipse([cx - 2 * SCALE, cy - 2 * SCALE, cx + 2 * SCALE, cy + 2 * SCALE], fill=(20, 20, 20, 255))

    res = im.resize((width, height), Image.Resampling.LANCZOS)
    res.save(out_path, 'PNG')
    print(f'Generated {out_path}')

def generate_mini_player_frame(out_path, width=1800, height=420):
    """
    1800x420: Mini player container frame matching Reference 01
    - Rounded pill / rectangle
    - Thin clean outline
    - Subtle corner engravings
    - Transparent center
    """
    W, H = width * SCALE, height * SCALE
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)
    
    pad = 20 * SCALE
    rad = int((H - pad * 2) // 2)
    col_main = (220, 220, 220, 220)
    col_subtle = (160, 160, 160, 140)
    
    # Outer rounded rectangle
    draw.rounded_rectangle([pad, pad, W - pad, H - pad], radius=rad, outline=col_main, width=max(1, int(2.8 * SCALE)))
    # Inner subtle hairline
    pad_in = pad + int(5 * SCALE)
    draw.rounded_rectangle([pad_in, pad_in, W - pad_in, H - pad_in], radius=rad - 5 * SCALE, outline=col_subtle, width=max(1, int(1.2 * SCALE)))
    
    # Delicate corner notches / tick marks on left and right cap transitions
    for x_mark in [pad + rad, W - pad - rad]:
        draw.line([(x_mark, pad - 4 * SCALE), (x_mark, pad + 8 * SCALE)], fill=col_main, width=max(1, int(2 * SCALE)))
        draw.line([(x_mark, H - pad - 8 * SCALE), (x_mark, H - pad + 4 * SCALE)], fill=col_main, width=max(1, int(2 * SCALE)))

    res = im.resize((width, height), Image.Resampling.LANCZOS)
    res.save(out_path, 'PNG')
    print(f'Generated {out_path}')

def generate_section_divider(out_path, width=1800, height=120):
    """1800x120: Section divider with fading line and central gothic star."""
    W, H = width * SCALE, height * SCALE
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)
    cx, cy = W // 2, H // 2
    
    # Fading horizontal line
    for x in range(int(100 * SCALE), W - int(100 * SCALE), int(2 * SCALE)):
        dist = abs(x - cx) / (cx - 100 * SCALE)
        alpha = int(max(0, 1.0 - dist ** 1.8) * 180)
        draw.line([(x, cy), (x + 2 * SCALE, cy)], fill=(200, 200, 200, alpha), width=max(1, int(1.5 * SCALE)))
        
    # Central star
    draw_star(draw, cx, cy, 12 * SCALE, 22 * SCALE, fill=(230, 230, 230, 240))
    
    res = im.resize((width, height), Image.Resampling.LANCZOS)
    res.save(out_path, 'PNG')
    print(f'Generated {out_path}')

def generate_header_decoration(out_path, size=128):
    """128x128: Small engraved cross / ornament for headers (e.g. 'Главная +', 'Deathcore +')."""
    S = size * SCALE
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)
    cx, cy = S // 2, S // 2
    
    # Inverted cross / gothic cross with ornamental ends
    col = (230, 230, 230, 240)
    w = max(1, int(2.5 * SCALE))
    
    # Vertical shaft: extends downwards
    draw.line([(cx, cy - 35 * SCALE), (cx, cy + 42 * SCALE)], fill=col, width=w)
    # Horizontal bar: crossbar positioned lower for inverted cross (satanic), or center-high
    bar_y = cy - 8 * SCALE
    draw.line([(cx - 24 * SCALE, bar_y), (cx + 24 * SCALE, bar_y)], fill=col, width=w)
    
    # End finials
    for pt in [(cx, cy - 35 * SCALE), (cx, cy + 42 * SCALE), (cx - 24 * SCALE, bar_y), (cx + 24 * SCALE, bar_y)]:
        draw.ellipse([pt[0] - 2.5 * SCALE, pt[1] - 2.5 * SCALE, pt[0] + 2.5 * SCALE, pt[1] + 2.5 * SCALE], fill=col)

    res = im.resize((size, size), Image.Resampling.LANCZOS)
    res.save(out_path, 'PNG')
    print(f'Generated {out_path}')

def generate_active_tab_decoration(out_path, width=512, height=192):
    """512x192: Active tab decoration pill frame with delicate etched corners."""
    W, H = width * SCALE, height * SCALE
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)
    
    pad = 12 * SCALE
    rad = (H - pad * 2) // 2
    col_main = (220, 220, 220, 220)
    col_faint = (150, 150, 150, 130)
    
    draw.rounded_rectangle([pad, pad, W - pad, H - pad], radius=rad, outline=col_main, width=max(1, int(2.2 * SCALE)))
    # Subtle inner line
    pad_in = pad + 4 * SCALE
    draw.rounded_rectangle([pad_in, pad_in, W - pad_in, H - pad_in], radius=rad - 4 * SCALE, outline=col_faint, width=max(1, int(1.2 * SCALE)))
    
    # Micro gothic marks at the two ends
    draw_star(draw, pad + 16 * SCALE, H // 2, 5 * SCALE, 10 * SCALE, fill=col_main)
    draw_star(draw, W - pad - 16 * SCALE, H // 2, 5 * SCALE, 10 * SCALE, fill=col_main)

    res = im.resize((width, height), Image.Resampling.LANCZOS)
    res.save(out_path, 'PNG')
    print(f'Generated {out_path}')

def generate_navigation_underline(out_path, width=256, height=64):
    """256x64: Navigation underline with central diamond ornament for active nav tab."""
    W, H = width * SCALE, height * SCALE
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)
    cx, cy = W // 2, H // 2
    
    col = (230, 230, 230, 230)
    draw.line([(15 * SCALE, cy), (W - 15 * SCALE, cy)], fill=col, width=max(1, int(2.0 * SCALE)))
    draw_star(draw, cx, cy, 6 * SCALE, 12 * SCALE, fill=col)

    res = im.resize((width, height), Image.Resampling.LANCZOS)
    res.save(out_path, 'PNG')
    print(f'Generated {out_path}')

if __name__ == '__main__':
    frames = [
        ('android/app/src/main/assets/satanic/player/album_frame.png', generate_album_frame),
        ('android/app/src/main/assets/satanic/player/play_button_frame.png', generate_play_button_frame),
        ('android/app/src/main/assets/satanic/player/control_button_frame.png', generate_control_button_frame),
        ('android/app/src/main/assets/satanic/player/progress_decoration.png', generate_progress_decoration),
        ('android/app/src/main/assets/satanic/components/mini_player_frame.png', generate_mini_player_frame),
        ('android/app/src/main/assets/satanic/components/section_divider.png', generate_section_divider),
        ('android/app/src/main/assets/satanic/components/header_decoration.png', generate_header_decoration),
        ('android/app/src/main/assets/satanic/components/active_tab_decoration.png', generate_active_tab_decoration),
        ('android/app/src/main/assets/satanic/components/navigation_underline.png', generate_navigation_underline),
    ]
    for p, fn in frames:
        fn(p)
        # copy to root assets
        root_p = p.replace('android/app/src/main/', '')
        shutil.copyfile(p, root_p)
        print(f'Copied to {root_p}')
