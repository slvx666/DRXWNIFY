import numpy as np
from PIL import Image, ImageEnhance, ImageFilter
import shutil

W, H = 1080, 2400

def create_home_bg(out_path):
    h_src = Image.open('C:/Users/burger/.gemini/antigravity/brain/9a09130a-1d64-4913-a286-269fe501163a/black_metal_home_bg_1795041249968.jpg').convert('L')
    w_s, h_s = h_src.size
    border_x = int(w_s * 0.07)
    border_y = int(h_s * 0.07)
    cropped = h_src.crop((border_x, border_y, w_s - border_x, h_s - border_y))

    scale_w = W / cropped.width
    scaled_h = int(cropped.height * scale_w)
    scaled = cropped.resize((W, scaled_h), Image.Resampling.LANCZOS)

    canvas_arr = np.full((H, W), 8, dtype=np.float32)
    use_h = min(H, scaled_h)
    scaled_arr = np.array(scaled, dtype=np.float32)[:use_h, :]

    fade_start = int(H * 0.38)
    fade_end = min(use_h, int(H * 0.62))
    fade_len = fade_end - fade_start

    y_fade = np.ones((use_h, 1), dtype=np.float32)
    if fade_len > 0:
        t = np.linspace(0, 1, fade_len)
        fade_curve = 0.5 * (1 + np.cos(np.pi * t))
        y_fade[fade_start:fade_end, 0] = fade_curve
        y_fade[fade_end:, 0] = 0.0

    canvas_arr[:use_h, :] = 8.0 * (1.0 - y_fade) + scaled_arr * y_fade

    top_fade_len = int(H * 0.06)
    top_t = np.linspace(0.4, 1.0, top_fade_len)[:, None]
    canvas_arr[:top_fade_len, :] = 8.0 * (1.0 - top_t) + canvas_arr[:top_fade_len, :] * top_t

    res = Image.fromarray(np.clip(canvas_arr, 0, 255).astype(np.uint8), mode='L')
    res = ImageEnhance.Contrast(res).enhance(1.2)
    res = res.filter(ImageFilter.UnsharpMask(radius=2, percent=120, threshold=3))

    res.save(out_path, 'WEBP', quality=90)
    print(f'Saved {out_path}')

def create_library_bg(out_path):
    lib_ref = Image.open('C:/Users/burger/.gemini/antigravity/brain/9a09130a-1d64-4913-a286-269fe501163a/lib_ref.png').convert('L')
    statue = lib_ref.crop((170, 0, lib_ref.width, lib_ref.height))
    statue = statue.resize((int(statue.width * (H / statue.height)), H), Image.Resampling.LANCZOS)

    canvas = Image.new('L', (W, H), 8)
    statue_x = W - statue.width
    canvas.paste(statue, (statue_x, 0))

    mask = np.ones((H, statue.width), dtype=np.float32)
    fade_w = int(statue.width * 0.45)
    for x in range(fade_w):
        mask[:, x] = (x / fade_w) ** 1.5

    canvas_arr = np.full((H, W), 8, dtype=np.float32)
    statue_arr = np.array(statue, dtype=np.float32)

    canvas_arr[:, statue_x:] = 8.0 * (1.0 - mask) + statue_arr * mask

    top_fade = np.linspace(0.4, 1.0, int(H * 0.08))[:, None]
    canvas_arr[:len(top_fade), :] = 8.0 * (1.0 - top_fade) + canvas_arr[:len(top_fade), :] * top_fade

    bot_fade = np.linspace(1.0, 0.2, int(H * 0.15))[:, None]
    canvas_arr[-len(bot_fade):, :] = 8.0 * (1.0 - bot_fade) + canvas_arr[-len(bot_fade):, :] * bot_fade

    res = Image.fromarray(np.clip(canvas_arr, 0, 255).astype(np.uint8), mode='L')
    res = ImageEnhance.Contrast(res).enhance(1.25)
    res = res.filter(ImageFilter.UnsharpMask(radius=2, percent=130, threshold=3))

    res.save(out_path, 'WEBP', quality=90)
    print(f'Saved {out_path}')

def create_player_bg(out_path):
    p_src = Image.open('C:/Users/burger/.gemini/antigravity/brain/9a09130a-1d64-4913-a286-269fe501163a/dark_metal_player_background_1795041273794.jpg').convert('L')
    w_s, h_s = p_src.size
    p_crop = p_src.crop((int(w_s * 0.04), int(h_s * 0.04), int(w_s * 0.96), int(h_s * 0.96)))
    p_scaled = p_crop.resize((W, H), Image.Resampling.LANCZOS)
    p_arr = np.array(p_scaled, dtype=np.float32)

    cx, cy = 540, 850
    yy, xx = np.ogrid[:H, :W]
    dist = np.sqrt((xx - cx)**2 + ((yy - cy) * 0.75)**2)

    r_inner = 380
    r_outer = 680
    fade = np.clip((dist - r_inner) / (r_outer - r_inner), 0.0, 1.0)
    fade = 0.5 * (1 - np.cos(np.pi * fade))

    canvas_arr = 8.0 * (1.0 - fade) + p_arr * fade

    bot_fade_start = int(H * 0.82)
    bot_t = np.linspace(1.0, 0.4, H - bot_fade_start)[:, None]
    canvas_arr[bot_fade_start:, :] = 8.0 * (1.0 - bot_t) + canvas_arr[bot_fade_start:, :] * bot_t

    res = Image.fromarray(np.clip(canvas_arr, 0, 255).astype(np.uint8), mode='L')
    res = ImageEnhance.Contrast(res).enhance(1.2)
    res = res.filter(ImageFilter.UnsharpMask(radius=2, percent=120, threshold=3))

    res.save(out_path, 'WEBP', quality=90)
    print(f'Saved {out_path}')

if __name__ == '__main__':
    bgs = [
        ('android/app/src/main/assets/satanic/backgrounds/home_background.webp', create_home_bg),
        ('android/app/src/main/assets/satanic/backgrounds/library_background.webp', create_library_bg),
        ('android/app/src/main/assets/satanic/backgrounds/player_background.webp', create_player_bg),
    ]
    for p, fn in bgs:
        fn(p)
        root_p = p.replace('android/app/src/main/', '')
        shutil.copyfile(p, root_p)
        print(f'Copied to {root_p}')
