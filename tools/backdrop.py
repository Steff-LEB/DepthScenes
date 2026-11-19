"""Clean backdrop for a scene whose cutout comes from a different render than the photo.
Fills the union of the photo's own subject and the new cutout (generously grown) with the
surrounding backdrop, then composes photo and thumbnail from the new cutout over it.
usage: backdrop.py photo.png old_cut.png new_cut.png assets_dir key"""
import sys, os, numpy as np
from PIL import Image, ImageFilter
sys.path.insert(0, os.path.dirname(__file__))
photo_p, old_p, new_p, assets, key = sys.argv[1:6]
photo = Image.open(photo_p).convert('RGB'); W, H = photo.size
old = Image.open(old_p).getchannel('A'); new = Image.open(new_p).convert('RGBA')
hole = np.maximum(np.array(old), np.array(new.getchannel('A')))
hole = np.array(Image.fromarray(np.where(hole > 8, 255, 0).astype(np.uint8)).filter(ImageFilter.MaxFilter(31))) > 0
# dark rock shadow in the photo fades into the vignette: also fill a band below the old subject
from plate_one import fill
out = fill(np.array(photo).astype(np.float64), ~hole)
out = np.array(Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0))).astype(np.float64)
rng = np.random.default_rng(3); out[hole] += rng.normal(0, 1.4, (hole.sum(), 3))
plate = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))
plate.save(os.path.join(assets, key + '-plate.jpg'), quality=93)
new.save(os.path.join(assets, key + '-front.webp'), lossless=True, method=6)
comp = plate.convert('RGBA'); comp.alpha_composite(new); comp = comp.convert('RGB')
comp.save(os.path.join(assets, key + '-photo.jpg'), quality=93)
comp.resize((round(420*W/H), 420), Image.LANCZOS).save(os.path.join(assets, key + '-thumb.jpg'), quality=88)
print("filled %.0f%%" % (hole.mean()*100))
