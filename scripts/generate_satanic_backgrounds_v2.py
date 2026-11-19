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

def make_light_variant(dark_arr, dark_bg_val=8.0, light_bg_val=212.0, min_ink_val=158.0):
    """
    Creates an authentic light mode (parchment/ash stone) engraving.
    Background is light_bg_val (212).
    Engraving ink lines etch down to min_ink_val (~158), preserving crisp contrast
    for #0A0A0A text and UI controls.
    """
    diff = np.maximum(0.0, dark_arr - dark_bg_val)
    max_d = np.max(diff)
    if max_d < 1e-4:
        return np.full_like(dark_arr, light_bg_val, dtype=np.uint8)
    
    s = diff / max_d
    # Elegant tone curve for ink
    ink_curve = s ** 0.90
    light_arr = light_bg_val - ink_curve * (light_bg_val - min_ink_val)
    return np.clip(light_arr, 0, 255).astype(np.uint8)

def save_both(dark_arr, name, dark_max_target=68.0, min_ink_val=158.0):
    """
    Saves both Dark mode and Light mode WebP assets at exactly 1080x2436.
    Ensures dark mode highlights never exceed dark_max_target (~68),
    guaranteeing 100% contrast for white/silver UI controls and text.
    """
    # Scale dark array so that highest art highlight is dark_max_target
    diff = np.maximum(0.0, dark_arr - DARK_BG)
    max_d = np.max(diff)
    if max_d > 0:
        scaled_diff = diff * ((dark_max_target - DARK_BG) / max_d)
        final_dark = DARK_BG + scaled_diff
    else:
        final_dark = dark_arr

    dark_clipped = np.clip(final_dark, 0, 255).astype(np.uint8)
    dark_img = Image.fromarray(dark_clipped, mode='L')
    
    # Generate light mode variant
    light_clipped = make_light_variant(final_dark, DARK_BG, LIGHT_BG, min_ink_val)
    light_img = Image.fromarray(light_clipped, mode='L')

    for folder in [APP_ASSETS, ROOT_ASSETS]:
        dark_p = os.path.join(folder, f'{name}.webp')
        light_p = os.path.join(folder, f'{name}_light.webp')
        dark_img.save(dark_p, 'WEBP', quality=95)
        light_img.save(light_p, 'WEBP', quality=95)
        print(f'Saved: {dark_p} and {light_p}')

def process_home():
    src_p = os.path.join(BRAIN_DIR, 'fallen_angel_gothic_engraving_1795065134653.jpg')
    src = Image.open(src_p).convert('L')
    
    # Scale width to W
    scale = W / src.width
    scaled_h = int(src.height * scale)
    scaled = src.resize((W, scaled_h), Image.Resampling.LANCZOS)
    scaled_arr = np.array(scaled, dtype=np.float32)

    canvas_arr = np.full((H, W), DARK_BG, dtype=np.float32)
    use_h = min(H, scaled_h)
    
    # Position angel in top region:
    # Status bar + top bar (y: 0 to 280px = ~11.5% of H) gets a smooth scrim
    # Angel wings & torso are between y = 10% and y = 35%
    # Smooth fade out from y = 30% to y = 42% of H
    fade_start = int(H * 0.30)
    fade_end = min(use_h, int(H * 0.42))
    fade_len = fade_end - fade_start

    y_fade = np.ones((use_h, 1), dtype=np.float32)
    if fade_len > 0:
        t = np.linspace(0, 1, fade_len)[:, None]
        curve = 0.5 * (1.0 + np.cos(np.pi * t))
        y_fade[fade_start:fade_end, :] = curve
        y_fade[fade_end:, :] = 0.0

    # Top scrim under status bar and top title (y: 0 to 280px)
    top_h = int(H * 0.115)
    top_t = (np.linspace(0.15, 1.0, top_h)[:, None]) ** 1.5
    y_fade[:top_h, :] *= top_t

    angel_art = scaled_arr[:use_h, :]
    canvas_arr[:use_h, :] = DARK_BG * (1.0 - y_fade) + angel_art * y_fade

    # Dark target max ~68 (matches Reference 02 p90 ~44-55)
    save_both(canvas_arr, 'home_background', dark_max_target=68.0, min_ink_val=162.0)

def process_library():
    src_p = os.path.join(BRAIN_DIR, 'gothic_hooded_figure_pure_monochrome_1795065078406.jpg')
    src = Image.open(src_p).convert('L')

    # Scale vertically to H
    scale = H / src.height
    scaled_w = int(src.width * scale)
    scaled = src.resize((scaled_w, H), Image.Resampling.LANCZOS)
    scaled_arr = np.array(scaled, dtype=np.float32)

    # Place hooded figure on the right
    statue_x = W - scaled_w
    if statue_x < 0:
        start_crop = -statue_x
        statue_part = scaled_arr[:, start_crop:start_crop + W]
        statue_w = W
        statue_left = 0
    else:
        statue_part = scaled_arr
        statue_w = scaled_w
        statue_left = statue_x

    # Left 55% of the screen is solid dark (no statue)
    # Smooth blend between x = 55% and x = 75%
    mask = np.zeros((H, W), dtype=np.float32)
    x_start = int(W * 0.55)
    x_end = int(W * 0.78)
    fade_len = x_end - x_start

    t = np.linspace(0, 1, fade_len)[None, :]
    curve = 0.5 * (1.0 - np.cos(np.pi * t))
    mask[:, x_start:x_end] = curve
    mask[:, x_end:] = 1.0

    # Top fade (status bar + title) and bottom fade (mini player + bottom bar)
    top_len = int(H * 0.10)
    mask[:top_len, :] *= np.linspace(0.1, 1.0, top_len)[:, None]
    bot_len = int(H * 0.16)
    mask[-bot_len:, :] *= np.linspace(1.0, 0.0, bot_len)[:, None]

    statue_canvas = np.full((H, W), DARK_BG, dtype=np.float32)
    statue_canvas[:, statue_left:statue_left + statue_w] = statue_part[:, :W - statue_left]
    
    canvas_arr = DARK_BG * (1.0 - mask) + statue_canvas * mask

    # Dark target max ~62 (matches Reference 02 right column p90 ~32)
    save_both(canvas_arr, 'library_background', dark_max_target=62.0, min_ink_val=165.0)

def process_player():
    src_p = os.path.join(BRAIN_DIR, 'dark_metal_player_background_1795041273794.jpg')
    src = Image.open(src_p).convert('L')
    
    # Scale to cover full screen
    scale = max(W / src.width, H / src.height)
    new_w = int(src.width * scale)
    new_h = int(src.height * scale)
    scaled = src.resize((new_w, new_h), Image.Resampling.LANCZOS)
    
    # Center crop to W, H
    x0 = (new_w - W) // 2
    y0 = (new_h - H) // 2
    cropped = scaled.crop((x0, y0, x0 + W, y0 + H))
    p_arr = np.array(cropped, dtype=np.float32)

    # Circular mask for album art + controls
    cx, cy = W // 2, int(H * 0.36)
    yy, xx = np.ogrid[:H, :W]
    dist = np.sqrt((xx - cx)**2 + ((yy - cy) * 0.82)**2)

    r_inner = 380
    r_outer = 660
    radial_mask = np.clip((dist - r_inner) / (r_outer - r_inner), 0.0, 1.0)
    radial_curve = 0.5 * (1.0 - np.cos(np.pi * radial_mask))

    # Bottom controls fade out completely below y = 0.62 * H
    bot_start = int(H * 0.60)
    bot_fade = np.ones((H, 1), dtype=np.float32)
    bot_len = H - bot_start
    t_bot = np.linspace(0, 1, bot_len)[:, None]
    bot_fade[bot_start:, :] = 0.5 * (1.0 + np.cos(np.pi * t_bot))

    final_mask = radial_curve * bot_fade

    canvas_arr = DARK_BG * (1.0 - final_mask) + p_arr * final_mask

    save_both(canvas_arr, 'player_background', dark_max_target=65.0, min_ink_val=165.0)

def process_chat():
    src_p = os.path.join(BRAIN_DIR, 'dark_metal_player_background_1795041273794.jpg')
    src = Image.open(src_p).convert('L')
    scale = max(W / src.width, H / src.height)
    new_w = int(src.width * scale)
    new_h = int(src.height * scale)
    scaled = src.resize((new_w, new_h), Image.Resampling.LANCZOS)
    x0 = (new_w - W) // 2
    y0 = (new_h - H) // 2
    cropped = scaled.crop((x0, y0, x0 + W, y0 + H))
    c_arr = np.array(cropped, dtype=np.float32)

    # Fade from y = 0.10 * H to y = 0.32 * H to solid DARK_BG
    fade_start = int(H * 0.10)
    fade_end = int(H * 0.32)
    fade_len = fade_end - fade_start
    y_fade = np.ones((H, 1), dtype=np.float32)
    t = np.linspace(0, 1, fade_len)[:, None]
    y_fade[fade_start:fade_end, :] = 0.5 * (1.0 + np.cos(np.pi * t))
    y_fade[fade_end:, :] = 0.0

    canvas_arr = DARK_BG * (1.0 - y_fade) + c_arr * y_fade

    save_both(canvas_arr, 'chat_background', dark_max_target=55.0, min_ink_val=170.0)

if __name__ == '__main__':
    print('Generating Home backgrounds...')
    process_home()
    print('Generating Library backgrounds...')
    process_library()
    print('Generating Player backgrounds...')
    process_player()
    print('Generating Chat backgrounds...')
    process_chat()
    print('ALL BACKGROUNDS SUCCESSFULLY GENERATED!')
