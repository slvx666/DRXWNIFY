import numpy as np
from PIL import Image, ImageFilter, ImageOps
import shutil

WIDTH = 1080
HEIGHT = 2400

def generate_black_grain(out_path):
    """
    Subtle authentic analog film grain & xerox noise on deep black background.
    """
    np.random.seed(42)
    # Base luminance: #080808 (val 8)
    # Fine stochastic noise with Poisson / Gaussian distribution
    fine_noise = np.random.normal(loc=0, scale=12, size=(HEIGHT, WIDTH))
    # Coarser film clump noise
    coarse = np.random.normal(loc=0, scale=18, size=(HEIGHT // 2, WIDTH // 2))
    coarse_img = Image.fromarray(coarse).resize((WIDTH, HEIGHT), Image.Resampling.BILINEAR)
    coarse_arr = np.array(coarse_img)
    
    combined = 8.0 + fine_noise * 0.7 + coarse_arr * 0.5
    combined = np.clip(combined, 0, 255).astype(np.uint8)
    
    img = Image.fromarray(combined, mode='L')
    img.save(out_path, 'WEBP', quality=90)
    print(f'Generated {out_path}')

def generate_surface_scratches(out_path):
    """
    Subtle weathered antique etching scratches and micro-fractures.
    """
    np.random.seed(1337)
    base = np.full((HEIGHT, WIDTH), 8, dtype=np.uint8)
    img = Image.fromarray(base, mode='L')
    draw = Image.new('L', (WIDTH, HEIGHT), 0)
    import PIL.ImageDraw as ID
    d = ID.Draw(draw)
    
    # Draw scattered fine scratch streaks
    for _ in range(120):
        x1 = np.random.randint(0, WIDTH)
        y1 = np.random.randint(0, HEIGHT)
        length = np.random.randint(40, 350)
        angle = np.random.uniform(-0.4, 0.4) + (np.pi/2 if np.random.rand() > 0.5 else 0)
        x2 = int(x1 + length * np.cos(angle))
        y2 = int(y1 + length * np.sin(angle))
        opacity = np.random.randint(15, 60)
        width = np.random.choice([1, 1, 1, 2])
        d.line([(x1, y1), (x2, y2)], fill=opacity, width=width)
        
    # Micro-specks
    for _ in range(400):
        x = np.random.randint(0, WIDTH)
        y = np.random.randint(0, HEIGHT)
        r = np.random.randint(1, 3)
        d.ellipse([x - r, y - r, x + r, y + r], fill=np.random.randint(20, 75))
        
    draw = draw.filter(ImageFilter.GaussianBlur(radius=0.7))
    
    res = np.clip(np.array(img, dtype=np.int16) + np.array(draw, dtype=np.int16), 0, 255).astype(np.uint8)
    Image.fromarray(res, mode='L').save(out_path, 'WEBP', quality=90)
    print(f'Generated {out_path}')

def generate_print_distress(out_path):
    """
    Dark vintage woodblock print distress & photocopy ink press marks.
    """
    np.random.seed(666)
    # Perlin-like low frequency ink variations
    low_freq = np.random.normal(loc=0, scale=40, size=(HEIGHT // 16, WIDTH // 16))
    low_norm = (np.clip(low_freq, -60, 60) + 60) * (255.0 / 120.0)
    low_img = Image.fromarray(low_norm.astype(np.uint8), mode='L').resize((WIDTH, HEIGHT), Image.Resampling.BICUBIC)
    low_img = low_img.filter(ImageFilter.GaussianBlur(radius=15))
    low_arr = np.array(low_img, dtype=np.float32) - 128.0
    
    # High frequency xerox toner splatter
    toner = (np.random.rand(HEIGHT, WIDTH) > 0.985).astype(np.float32) * np.random.uniform(20, 90, size=(HEIGHT, WIDTH))
    
    combined = 8.0 + (low_arr - np.mean(low_arr)) * 0.25 + toner
    combined = np.clip(combined, 0, 255).astype(np.uint8)
    
    Image.fromarray(combined, mode='L').save(out_path, 'WEBP', quality=90)
    print(f'Generated {out_path}')

if __name__ == '__main__':
    tex = [
        ('android/app/src/main/assets/satanic/textures/black_grain.webp', generate_black_grain),
        ('android/app/src/main/assets/satanic/textures/surface_scratches.webp', generate_surface_scratches),
        ('android/app/src/main/assets/satanic/textures/print_distress.webp', generate_print_distress),
    ]
    for p, fn in tex:
        fn(p)
        root_p = p.replace('android/app/src/main/', '')
        shutil.copyfile(p, root_p)
        print(f'Copied to {root_p}')
