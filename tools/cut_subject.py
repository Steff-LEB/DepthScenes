"""Cut the tree and rock out of the bonsai photo: texture-seeded background model, then GrabCut."""
import sys, cv2, numpy as np
src, out = sys.argv[1], sys.argv[2]
img = cv2.imread(src); h, w = img.shape[:2]
lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB).astype(np.float32)
L = lab[..., 0]
# Local detail: leaves, bark, moss and rock are textured; the glowing backdrop is smooth.
detail = np.abs(L - cv2.GaussianBlur(L, (0, 0), 3))
detail = cv2.GaussianBlur(detail, (0, 0), 3)
textured = detail > 2.2
textured = cv2.dilate(textured.astype(np.uint8), np.ones((9, 9), np.uint8)) > 0
# Smooth backdrop estimate from untextured pixels only (normalized convolution at 1/4 scale).
f = 4; sm = (~textured).astype(np.float32)
lab_s = cv2.resize(lab, (w//f, h//f)); m_s = cv2.resize(sm, (w//f, h//f))
for sig in (6, 14, 30):
    num = cv2.GaussianBlur(lab_s*m_s[..., None], (0, 0), sig); den = cv2.GaussianBlur(m_s, (0, 0), sig)
    est = num/np.maximum(den[..., None], 1e-4)
    if sig == 6: bg_s = est; have = den > .05
    else: bg_s = np.where(have[..., None], bg_s, est); have |= den > .05
bg = cv2.resize(bg_s, (w, h))
d = np.linalg.norm(lab - bg, axis=2)
x, y, rw, rh = 150, 660, 560, 900
mask = np.full((h, w), cv2.GC_BGD, np.uint8)
roi = np.zeros((h, w), bool); roi[y:y+rh, x:x+rw] = True
mask[roi] = cv2.GC_PR_BGD
mask[roi & (d > 9)] = cv2.GC_PR_FGD
mask[roi & (d > 20) & (detail > 3)] = cv2.GC_FGD
mask[roi & (d < 4) & ~textured] = cv2.GC_BGD
bgd = np.zeros((1, 65)); fgd = np.zeros((1, 65))
cv2.grabCut(img, mask, None, bgd, fgd, 6, cv2.GC_INIT_WITH_MASK)
fg = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255, 0).astype(np.uint8)
# Backdrop showing through gaps in the canopy: smooth and close to the backdrop colour.
fine = cv2.dilate((cv2.GaussianBlur(np.abs(L - cv2.GaussianBlur(L, (0, 0), 2)), (0, 0), 1.5) > 2.0).astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
gap = (~fine) & (d < 10)
gap = cv2.morphologyEx(gap.astype(np.uint8), cv2.MORPH_OPEN, np.ones((5, 5), np.uint8)) > 0
fg[gap] = 0
n, lbl, st, _ = cv2.connectedComponentsWithStats(fg)
keep = np.zeros_like(fg)
for i in range(1, n):
    if st[i, cv2.CC_STAT_AREA] > 400: keep[lbl == i] = 255
alpha = cv2.GaussianBlur(keep, (0, 0), 0.9)
rgba = cv2.cvtColor(img, cv2.COLOR_BGR2BGRA); rgba[..., 3] = alpha
cv2.imwrite(out, rgba)
print("subject", round((keep > 0).mean()*100, 1), "%")
