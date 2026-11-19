"""Per-leaf matte for a subject in front of a smooth backdrop.

1. Estimate the backdrop B from pixels outside the subject plus smooth pixels showing through gaps
   inside it (the glow behind a canopy), at a fine scale so the glow is followed closely.
2. In the canopy (above canopy_bottom), alpha ramps with the colour distance from B, so every leaf,
   twig and berry is kept and every gap between them becomes transparent.
3. Below it (trunk, rock) the original solid cutout is kept.
4. Edge colours are decontaminated so no backdrop green rims remain.
usage: refine_matte.py photo.png hardcut.png out.png canopy_bottom_y
"""
import sys, cv2, numpy as np
photo, hard_path, out, cb = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
img = cv2.imread(photo).astype(np.float32)
hard = cv2.imread(hard_path, cv2.IMREAD_UNCHANGED)[..., 3].astype(np.float32) / 255
h, w = hard.shape
L = cv2.cvtColor(img.astype(np.uint8), cv2.COLOR_BGR2LAB)[..., 0].astype(np.float32)
detail = cv2.GaussianBlur(np.abs(L - cv2.GaussianBlur(L, (0, 0), 1.5)), (0, 0), 1.0)

def fill(samples, sigmas):
    out = None
    for sig in sigmas:
        num = cv2.GaussianBlur(img*samples[..., None], (0, 0), sig); den = cv2.GaussianBlur(samples, (0, 0), sig)
        est = num/np.maximum(den[..., None], 1e-6); ok = den > .12
        if out is None: out, have = est, ok
        else: out = np.where(have[..., None], out, est); have |= ok
    return out

outside = cv2.erode((hard < .5).astype(np.uint8), np.ones((7, 7), np.uint8)).astype(np.float32)
B = fill(outside, (6, 16, 40, 100))
for _ in range(2):  # add backdrop seen through the canopy and refine
    dist = np.linalg.norm(img - B, axis=2)
    gap = ((detail < 1.1) & (dist < 14)).astype(np.uint8)
    gap = cv2.erode(gap, np.ones((3, 3), np.uint8)).astype(np.float32)
    B = fill(np.maximum(outside, gap), (4, 10, 30, 100))
dist = np.linalg.norm(img - B, axis=2)

near = cv2.dilate((hard > .5).astype(np.uint8), np.ones((11, 11), np.uint8)) > 0
canopy = np.zeros((h, w), bool); canopy[:cb] = True
soft = np.clip((dist - 7) / 13, 0, 1)                       # 7 -> transparent, 20 -> opaque
soft = np.maximum(soft, np.clip((detail - 2.5) / 3, 0, 1) * (dist > 5))   # thin dark twigs
a = hard.copy()
a[canopy] = np.where(near[canopy], soft[canopy], 0)
# feather the seam between canopy and trunk
blend = np.clip((np.arange(h) - (cb - 20)) / 40, 0, 1)[:, None]
a = np.where(near, (1 - blend) * np.where(canopy, a, soft) + blend * hard, a)
# clean: fill pinholes inside leaves, remove dust
solid = (a > .5).astype(np.uint8)
solid = cv2.morphologyEx(solid, cv2.MORPH_CLOSE, np.ones((2, 2), np.uint8))
n, lbl, st, _ = cv2.connectedComponentsWithStats(solid, connectivity=8)
dust = np.zeros((h, w), bool)
for i in range(1, n):
    if st[i, cv2.CC_STAT_AREA] < 18: dust |= lbl == i
a[dust] = 0
a = cv2.GaussianBlur(a, (0, 0), .45)
# decontaminate edge colours: I = aF + (1-a)B  ->  F = (I - (1-a)B)/a
af = a[..., None]
dec = np.clip((img - (1 - af) * B) / np.maximum(af, .04), 0, 255)
# never darken an edge below the original pixel by more than 15%, and fade the correction out as alpha rises
dec = np.maximum(dec, img * .85)
k = np.clip((.7 - af) / .7, 0, 1)
col = img * (1 - k) + dec * k
cv2.imwrite(out, np.dstack([col, a * 255]).astype(np.uint8))
print("opaque %.1f%%  soft %.1f%%" % ((a > .96).mean()*100, ((a > .04) & (a <= .96)).mean()*100))
