"""Pick per-scene wallpaper zoom and shift so the building covers <=25% of the large clock."""
import sys, numpy as np
from PIL import Image
D=sys.argv[1]; F=4; W,H=1344//F,2992//F; CUT=1.10
CLOCK=(0.22,0.29,0.76,0.56)   # large two-line clock, from the user's lock screen captures
KEYS=["temple","fuji","wing","tower","flatiron","airplane","orange-mountain","desert","colosseum","bonsai"]
cx0,cy0,cx1,cy1=int(CLOCK[0]*W),int(CLOCK[1]*H),int(CLOCK[2]*W),int(CLOCK[3]*H)
def wallrect(pw,ph,shift,wz):
    s=max(W/pw,H/ph)*wz/1000; dy=H*shift/1000
    s*=max(1,(H+2*abs(dy))/(ph*s)); bw,bh=pw*s,ph*s
    return (W-bw)/2,(H-bh)/2+dy,bw,bh
def render(a,shift,wz):
    ph,pw=a.shape; x,y,bw,bh=wallrect(pw,ph,shift,wz)
    # lock-screen cutout zoom about the centre
    x=W/2+(x-W/2)*CUT; y=H/2+(y-H/2)*CUT; bw*=CUT; bh*=CUT
    xs=((np.arange(W)+.5-x)/bw*pw).astype(int); ys=((np.arange(H)+.5-y)/bh*ph).astype(int)
    okx=(xs>=0)&(xs<pw); oky=(ys>=0)&(ys<ph)
    out=np.zeros((H,W),bool); sub=a[np.clip(ys,0,ph-1)][:,np.clip(xs,0,pw-1)]
    out[np.ix_(oky,okx)]=sub[np.ix_(oky,okx)]; return out, (bw*bh)/(pw*ph)
res={}
for k in KEYS:
    a=np.array(Image.open(f"{D}/{k}-front.webp").getchannel('A'))>127
    total=a.sum()
    def cov(sh,wz):
        m,area=render(a,sh,wz); return m[cy0:cy1,cx0:cx1].mean(), m.sum(), area
    c0=cov(0,1000)[0]
    best=None
    for wz in range(1000,1801,25):
        for sh in range(-300,301,5):
            c,vis,area=cov(sh,wz)
            visfrac=vis/(total*area)
            if visfrac<0.5: continue
            cost=abs(sh)/1000+(wz-1000)/1000*0.7
            if c>0.25: cost+=10+c*10   # fallback: least coverage when 25% is impossible
            if best is None or cost<best[0]: best=(cost,sh,wz,c,visfrac)
    res[k]=best; print(f"{k:16s} now {c0*100:4.0f}%  ->  shift {best[1]:+4d}  wallzoom {best[2]}  cover {best[3]*100:4.1f}%  subject visible {best[4]*100:3.0f}%" if best else f"{k} no solution (now {c0*100:.0f}%)")
import json; json.dump({k:v[1:3] for k,v in res.items() if v},open(sys.argv[2],'w'))
