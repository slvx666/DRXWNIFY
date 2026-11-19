import numpy as np
from PIL import Image, ImageEnhance, ImageFilter
import os

W = 1080
H = 2436

DARK_BG = 8.0        # #080808
LIGHT_BG = 212.0     # #D4D2CE

BRAIN_DIR = 'C:/Users/burger/.gemini/antigravity/brain/9a09130a-1d64-4913-a286-269fe501163a'
APP_ASSETS = 'android/app/src/main/assets/satanic/backgrounds'
ROOT_ASSETS = 'assets/satanic/backgrounds'

os.makedirs(APP_ASSETS, exist_ok=True)
os.makedirs(ROOT_ASSETS, exist_ok=True)

def save_webp_pair(dark_arr, light_arr, name):
    dark_clipped = np.clip(dark_arr, 0, 255).astype(np.uint8)
    light_clipped = np.clip(light_arr, 0, 255).astype(np.uint8)

    dark_img = Image.fromarray(dark_clipped, mode='L')
    light_img = Image.fromarray(light_clipped, mode='L')

    for folder in [APP_ASSETS, ROOT_ASSETS]:
        dark_p = os.path.join(folder, f'{name}.webp')
        light_p = os.path.join(folder, f'{name}_light.webp')
        dark_img.save(dark_p, 'WEBP', quality=95)
        light_img.save(light_p, 'WEBP', quality=95)
        print(f'Saved: {dark_p} and {light_p}')

def process_home():
    # ── Dark Mode Home ──
    # Monumental angel statue scaled to full height so artwork and gothic stone
    # cover the entire screen from top to bottom.
    src_dark_p = os.path.join(BRAIN_DIR, 'fallen_angel_gothic_engraving_1795065134653.jpg')
    src_dark = Image.open(src_dark_p).convert('L')
    scale_dark = H / src_dark.height
    scaled_w_dark = int(src_dark.width * scale_dark)
    scaled_dark = src_dark.resize((scaled_w_dark, H), Image.Resampling.LANCZOS)
    x0_dark = (scaled_w_dark - W) // 2
    dark_full = np.array(scaled_dark.crop((x0_dark, 0, x0_dark + W, H)), dtype=np.float32)

    # Dimming curve:
    # Top scrim under status bar (y: 0 to 10% H) drops to 65%
    # Angel chest and wings (y: 10% to 32% H) stay at 100%
    # Lower half (y: 32% to 48% H): smooth transition to 50% dimming (NOT 0%!)
    # Below 48%: 50% opacity remains everywhere, giving living gothic texture across all cards & lists
    y_mult_dark = np.ones((H, 1), dtype=np.float32)
    top_h = int(H * 0.10)
    y_mult_dark[:top_h, :] = np.linspace(0.65, 1.0, top_h)[:, None]

    fade_start = int(H * 0.32)
    fade_end = int(H * 0.48)
    t = np.linspace(0, 1, fade_end - fade_start)[:, None]
    curve = 1.0 - 0.50 * 0.5 * (1.0 - np.cos(np.pi * t))
    y_mult_dark[fade_start:fade_end, :] = curve
    y_mult_dark[fade_end:, :] = 0.50

    dark_home = DARK_BG + (dark_full - DARK_BG).clip(min=0) * (88.0 / 240.0) * y_mult_dark

    # ── Light Mode Home ──
    # Genuine antique architectural stone lithograph (NOT inverted negative!)
    src_light_p = os.path.join(BRAIN_DIR, 'gothic_stone_angel_lithograph_1795092574629.jpg')
    src_light = Image.open(src_light_p).convert('L')
    scale_light = H / src_light.height
    scaled_w_light = int(src_light.width * scale_light)
    scaled_light = src_light.resize((scaled_w_light, H), Image.Resampling.LANCZOS)
    x0_light = (scaled_w_light - W) // 2
    light_full = np.array(scaled_light.crop((x0_light, 0, x0_light + W, H)), dtype=np.float32)

    y_mult_light = np.ones((H, 1), dtype=np.float32)
    y_mult_light[:top_h, :] = np.linspace(0.65, 1.0, top_h)[:, None]
    curve_light = 1.0 - 0.45 * 0.5 * (1.0 - np.cos(np.pi * t))
    y_mult_light[fade_start:fade_end, :] = curve_light
    y_mult_light[fade_end:, :] = 0.55

    diff_light = LIGHT_BG - light_full
    light_home = LIGHT_BG - diff_light * y_mult_light * 0.85

    save_webp_pair(dark_home, light_home, 'home_background')

def process_library():
    # ── Dark Mode Library ──
    # Hooded shrouded figure on right + gothic cathedral background
    src_p = os.path.join(BRAIN_DIR, 'gothic_hooded_figure_pure_monochrome_1795065078406.jpg')
    src = Image.open(src_p).convert('L')
    scale = H / src.height
    scaled_w = int(src.width * scale)
    scaled = src.resize((scaled_w, H), Image.Resampling.LANCZOS)
    scaled_arr = np.array(scaled, dtype=np.float32)

    statue_x = W - scaled_w
    statue_canvas = np.full((H, W), DARK_BG, dtype=np.float32)
    if statue_x < 0:
        start_crop = -statue_x
        statue_canvas[:, :] = scaled_arr[:, start_crop:start_crop + W]
    else:
        statue_canvas[:, statue_x:] = scaled_arr[:, :W - statue_x]

    cath_p = os.path.join(BRAIN_DIR, 'dark_metal_player_background_1795041273794.jpg')
    cath_src = Image.open(cath_p).convert('L')
    cath_scale = max(W / cath_src.width, H / cath_src.height)
    cath_w = int(cath_src.width * cath_scale)
    cath_h = int(cath_src.height * cath_scale)
    cath_scaled = cath_src.resize((cath_w, cath_h), Image.Resampling.LANCZOS)
    x0 = (cath_w - W) // 2
    y0 = (cath_h - H) // 2
    cath_arr = np.array(cath_scaled.crop((x0, y0, x0 + W, y0 + H)), dtype=np.float32)

    comp_dark = np.maximum(cath_arr * 0.45, statue_canvas)

    # Horizontal dimming mask:
    # Right side (statue, x: 0.65 to 1.0): 100%
    # Left side (x: 0.0 to 0.40): 50% dimming (NOT 0%!)
    x_mask = np.ones((1, W), dtype=np.float32)
    x_start = int(W * 0.40)
    x_end = int(W * 0.68)
    t_x = np.linspace(0, 1, x_end - x_start)[None, :]
    curve_x = 0.50 + 0.50 * 0.5 * (1.0 - np.cos(np.pi * t_x))
    x_mask[:, :x_start] = 0.50
    x_mask[:, x_start:x_end] = curve_x
    x_mask[:, x_end:] = 1.0

    dark_lib = DARK_BG + (comp_dark - DARK_BG).clip(min=0) * (84.0 / 250.0) * x_mask

    # ── Light Mode Library ──
    # True stone lithograph: linework & shading on pale parchment
    comp_img = Image.fromarray(np.clip(comp_dark, 0, 255).astype(np.uint8))
    edges = np.array(comp_img.filter(ImageFilter.FIND_EDGES), dtype=np.float32)
    edge_norm = np.clip(edges / 90.0, 0.0, 1.0)
    shade_norm = np.clip((comp_dark - DARK_BG) / 240.0, 0.0, 1.0)

    x_mask_light = np.ones((1, W), dtype=np.float32)
    x_mask_light[:, :x_start] = 0.55
    x_mask_light[:, x_start:x_end] = 0.55 + 0.45 * 0.5 * (1.0 - np.cos(np.pi * t_x))
    x_mask_light[:, x_end:] = 1.0

    ink = (edge_norm * 55.0 + shade_norm * 20.0) * x_mask_light
    light_lib = LIGHT_BG - ink

    save_webp_pair(dark_lib, light_lib, 'library_background')

def process_player():
    # ── Dark Mode Player ──
    cath_p = os.path.join(BRAIN_DIR, 'dark_metal_player_background_1795041273794.jpg')
    src = Image.open(cath_p).convert('L')
    scale = max(W / src.width, H / src.height)
    new_w = int(src.width * scale)
    new_h = int(src.height * scale)
    scaled = src.resize((new_w, new_h), Image.Resampling.LANCZOS)
    x0 = (new_w - W) // 2
    y0 = (new_h - H) // 2
    p_arr = np.array(scaled.crop((x0, y0, x0 + W, y0 + H)), dtype=np.float32)

    # Radial cutout for circular cover
    cx, cy = W // 2, int(H * 0.36)
    yy, xx = np.ogrid[:H, :W]
    dist = np.sqrt((xx - cx)**2 + ((yy - cy) * 0.82)**2)
    r_inner = 380
    r_outer = 660
    radial_mask = np.clip((dist - r_inner) / (r_outer - r_inner), 0.0, 1.0)
    radial_curve = 0.5 * (1.0 - np.cos(np.pi * radial_mask))

    # Bottom controls: smoothly dim to 50% (NOT 0%!)
    bot_start = int(H * 0.58)
    bot_fade = np.ones((H, 1), dtype=np.float32)
    bot_len = H - bot_start
    t_bot = np.linspace(0, 1, bot_len)[:, None]
    bot_fade[bot_start:, :] = 1.0 - 0.50 * 0.5 * (1.0 - np.cos(np.pi * t_bot))

    final_mask_dark = radial_curve * bot_fade
    dark_player = DARK_BG + (p_arr - DARK_BG).clip(min=0) * (78.0 / 255.0) * final_mask_dark

    # ── Light Mode Player ──
    p_img = Image.fromarray(np.clip(p_arr, 0, 255).astype(np.uint8))
    edges = np.array(p_img.filter(ImageFilter.FIND_EDGES), dtype=np.float32)
    edge_norm = np.clip(edges / 95.0, 0.0, 1.0)
    shade_norm = np.clip((p_arr - DARK_BG) / 240.0, 0.0, 1.0)
    ink = (edge_norm * 52.0 + shade_norm * 18.0) * final_mask_dark
    light_player = LIGHT_BG - ink

    save_webp_pair(dark_player, light_player, 'player_background')

def process_chat():
    # ── Dark Mode Chat ──
    cath_p = os.path.join(BRAIN_DIR, 'dark_metal_player_background_1795041273794.jpg')
    src = Image.open(cath_p).convert('L')
    scale = max(W / src.width, H / src.height)
    new_w = int(src.width * scale)
    new_h = int(src.height * scale)
    scaled = src.resize((new_w, new_h), Image.Resampling.LANCZOS)
    x0 = (new_w - W) // 2
    y0 = (new_h - H) // 2
    c_arr = np.array(scaled.crop((x0, y0, x0 + W, y0 + H)), dtype=np.float32)

    # Fade from top (arches) to 50% dimming throughout the rest of the chat (NOT 0%!)
    fade_start = int(H * 0.12)
    fade_end = int(H * 0.38)
    fade_len = fade_end - fade_start
    y_fade = np.ones((H, 1), dtype=np.float32)
    t = np.linspace(0, 1, fade_len)[:, None]
    y_fade[fade_start:fade_end, :] = 1.0 - 0.50 * 0.5 * (1.0 - np.cos(np.pi * t))
    y_fade[fade_end:, :] = 0.50

    dark_chat = DARK_BG + (c_arr - DARK_BG).clip(min=0) * (68.0 / 255.0) * y_fade

    # ── Light Mode Chat ──
    c_img = Image.fromarray(np.clip(c_arr, 0, 255).astype(np.uint8))
    edges = np.array(c_img.filter(ImageFilter.FIND_EDGES), dtype=np.float32)
    edge_norm = np.clip(edges / 95.0, 0.0, 1.0)
    shade_norm = np.clip((c_arr - DARK_BG) / 240.0, 0.0, 1.0)
    ink = (edge_norm * 48.0 + shade_norm * 16.0) * y_fade
    light_chat = LIGHT_BG - ink

    save_webp_pair(dark_chat, light_chat, 'chat_background')

if __name__ == '__main__':
    print('Generating Home backgrounds with 50% continuous atmospheric dimming...')
    process_home()
    print('Generating Library backgrounds with 50% continuous atmospheric dimming...')
    process_library()
    print('Generating Player backgrounds with 50% continuous atmospheric dimming...')
    process_player()
    print('Generating Chat backgrounds with 50% continuous atmospheric dimming...')
    process_chat()
    print('ALL BACKGROUNDS SUCCESSFULLY GENERATED!')
