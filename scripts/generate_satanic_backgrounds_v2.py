import numpy as np
from PIL import Image, ImageEnhance, ImageFilter
import os

W = 1080
H = 2436

DARK_BG = 8.0        # #080808 (Deep obsidian black)
LIGHT_BG = 212.0     # #D4D2CE (Antique bone parchment)

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
        print(f'Saved: {dark_p} and {light_p} | dark: min={dark_clipped.min()} max={dark_clipped.max()} mean={dark_clipped.mean():.1f} | light: min={light_clipped.min()} max={light_clipped.max()} mean={light_clipped.mean():.1f}')

def process_home():
    # ── Dark Mode Home (In the style of the Library: Cathedral cloister & vaulted arches) ──
    # Cathedral vaulted stone colonnade and gothic archway matching the library atmosphere perfectly.
    cath_p = os.path.join(BRAIN_DIR, 'dark_metal_player_background_1795041273794.jpg')
    cath_src = Image.open(cath_p).convert('L')
    cath_scale = max(W / cath_src.width, H / cath_src.height)
    cath_w = int(cath_src.width * cath_scale)
    cath_h = int(cath_src.height * cath_scale)
    cath_scaled = cath_src.resize((cath_w, cath_h), Image.Resampling.LANCZOS)
    x0 = (cath_w - W) // 2
    y0 = (cath_h - H) // 2
    cath_arr = np.array(cath_scaled.crop((x0, y0, x0 + W, y0 + H)), dtype=np.float32)

    # Top has subtle vaulted ceiling; fades smoothly down through content cards
    # Peak highlight ~32 (very subtle, non-intrusive, gothic shadow)
    y_mult = np.ones((H, 1), dtype=np.float32)
    top_end = int(H * 0.18)
    y_mult[:top_end, :] = np.linspace(0.85, 0.50, top_end)[:, None]
    
    fade_end = int(H * 0.45)
    t = np.linspace(0, 1, fade_end - top_end)[:, None]
    y_mult[top_end:fade_end, :] = 0.50 - (0.50 - 0.28) * 0.5 * (1.0 - np.cos(np.pi * t))
    y_mult[fade_end:, :] = 0.28

    # Target highlight max ~32, ambient floor ~14
    dark_home = DARK_BG + (cath_arr - DARK_BG).clip(min=0) * (34.0 / 250.0) * y_mult

    # ── Light Mode Home ──
    comp_img = Image.fromarray(np.clip(cath_arr, 0, 255).astype(np.uint8))
    edges = np.array(comp_img.filter(ImageFilter.FIND_EDGES), dtype=np.float32)
    edge_norm = np.clip(edges / 90.0, 0.0, 1.0)
    shade_norm = np.clip((cath_arr - DARK_BG) / 240.0, 0.0, 1.0)
    ink = (edge_norm * 14.0 + shade_norm * 6.0) * y_mult
    light_home = LIGHT_BG - ink

    save_webp_pair(dark_home, light_home, 'home_background')

def process_library():
    # ── Dark Mode Library (The hooded figure in cathedral cloister user loves) ──
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

    comp_dark = np.maximum(cath_arr * 0.35, statue_canvas)

    # Subdued dimming everywhere:
    # Right side (statue): max multiplier 0.65 (down from 0.85) -> peak highlight ~34
    # Left side (playlists): 0.25 (down from 0.38) -> ambient floor ~14
    x_mask = np.ones((1, W), dtype=np.float32)
    x_start = int(W * 0.35)
    x_end = int(W * 0.70)
    t_x = np.linspace(0, 1, x_end - x_start)[None, :]
    curve_x = 0.25 + (0.65 - 0.25) * 0.5 * (1.0 - np.cos(np.pi * t_x))
    x_mask[:, :x_start] = 0.25
    x_mask[:, x_start:x_end] = curve_x
    x_mask[:, x_end:] = 0.65

    # Target highlight max ~35 (subtle, dark, non-intrusive)
    dark_lib = DARK_BG + (comp_dark - DARK_BG).clip(min=0) * (36.0 / 250.0) * x_mask

    # ── Light Mode Library ──
    comp_img = Image.fromarray(np.clip(comp_dark, 0, 255).astype(np.uint8))
    edges = np.array(comp_img.filter(ImageFilter.FIND_EDGES), dtype=np.float32)
    edge_norm = np.clip(edges / 90.0, 0.0, 1.0)
    shade_norm = np.clip((comp_dark - DARK_BG) / 240.0, 0.0, 1.0)

    ink = (edge_norm * 14.0 + shade_norm * 6.0) * x_mask
    light_lib = LIGHT_BG - ink

    save_webp_pair(dark_lib, light_lib, 'library_background')

def process_player():
    # ── Dark Mode Player (Subtle gothic cross / cloister background) ──
    cath_p = os.path.join(BRAIN_DIR, 'dark_metal_player_background_1795041273794.jpg')
    src = Image.open(cath_p).convert('L')
    scale = max(W / src.width, H / src.height)
    new_w = int(src.width * scale)
    new_h = int(src.height * scale)
    scaled = src.resize((new_w, new_h), Image.Resampling.LANCZOS)
    x0 = (new_w - W) // 2
    y0 = (new_h - H) // 2
    p_arr = np.array(scaled.crop((x0, y0, x0 + W, y0 + H)), dtype=np.float32)

    # Radial cutout for cover area (clears space behind thumbnail)
    cx, cy = W // 2, int(H * 0.34)
    yy, xx = np.ogrid[:H, :W]
    dist = np.sqrt((xx - cx)**2 + ((yy - cy) * 0.85)**2)
    r_inner = 340
    r_outer = 650
    radial_mask = np.clip((dist - r_inner) / (r_outer - r_inner), 0.0, 1.0)
    radial_curve = 0.5 * (1.0 - np.cos(np.pi * radial_mask))

    # Controls dimming below cover
    bot_start = int(H * 0.52)
    bot_fade = np.ones((H, 1), dtype=np.float32)
    bot_len = H - bot_start
    t_bot = np.linspace(0, 1, bot_len)[:, None]
    bot_fade[bot_start:, :] = 1.0 - (1.0 - 0.28) * 0.5 * (1.0 - np.cos(np.pi * t_bot))

    final_mask_dark = radial_curve * bot_fade
    # Target highlight max ~32 (down from 62)
    dark_player = DARK_BG + (p_arr - DARK_BG).clip(min=0) * (34.0 / 255.0) * final_mask_dark

    # ── Light Mode Player ──
    p_img = Image.fromarray(np.clip(p_arr, 0, 255).astype(np.uint8))
    edges = np.array(p_img.filter(ImageFilter.FIND_EDGES), dtype=np.float32)
    edge_norm = np.clip(edges / 95.0, 0.0, 1.0)
    shade_norm = np.clip((p_arr - DARK_BG) / 240.0, 0.0, 1.0)
    ink = (edge_norm * 14.0 + shade_norm * 5.0) * final_mask_dark
    light_player = LIGHT_BG - ink

    save_webp_pair(dark_player, light_player, 'player_background')

def process_chat():
    # ── Dark Mode Chat (Soft gothic arches at top, ambient shadow below) ──
    cath_p = os.path.join(BRAIN_DIR, 'dark_metal_player_background_1795041273794.jpg')
    src = Image.open(cath_p).convert('L')
    scale = max(W / src.width, H / src.height)
    new_w = int(src.width * scale)
    new_h = int(src.height * scale)
    scaled = src.resize((new_w, new_h), Image.Resampling.LANCZOS)
    x0 = (new_w - W) // 2
    y0 = (new_h - H) // 2
    c_arr = np.array(scaled.crop((x0, y0, x0 + W, y0 + H)), dtype=np.float32)

    fade_start = int(H * 0.10)
    fade_end = int(H * 0.35)
    fade_len = fade_end - fade_start
    y_fade = np.ones((H, 1), dtype=np.float32)
    t = np.linspace(0, 1, fade_len)[:, None]
    y_fade[fade_start:fade_end, :] = 1.0 - (1.0 - 0.25) * 0.5 * (1.0 - np.cos(np.pi * t))
    y_fade[fade_end:, :] = 0.25

    dark_chat = DARK_BG + (c_arr - DARK_BG).clip(min=0) * (30.0 / 255.0) * y_fade

    # ── Light Mode Chat ──
    c_img = Image.fromarray(np.clip(c_arr, 0, 255).astype(np.uint8))
    edges = np.array(c_img.filter(ImageFilter.FIND_EDGES), dtype=np.float32)
    edge_norm = np.clip(edges / 95.0, 0.0, 1.0)
    shade_norm = np.clip((c_arr - DARK_BG) / 240.0, 0.0, 1.0)
    ink = (edge_norm * 12.0 + shade_norm * 4.0) * y_fade
    light_chat = LIGHT_BG - ink

    save_webp_pair(dark_chat, light_chat, 'chat_background')

if __name__ == '__main__':
    print('Generating subdued, atmospheric, gothic Satanic backgrounds...')
    process_home()
    process_library()
    process_player()
    process_chat()
    print('ALL BACKGROUNDS SUCCESSFULLY GENERATED!')
