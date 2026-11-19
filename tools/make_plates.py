"""Make <key>-plate.jpg: the photo with the cutout subject removed (pull-push fill)."""
import sys, glob, os
import numpy as np
from PIL import Image, ImageFilter

def fill(rgb, known):
    # Multi-resolution pull-push: average known pixels downward, then fill holes from coarser levels.
    levels=[(rgb*known[...,None], known.astype(np.float64))]
    while min(levels[-1][1].shape)>2:
        c,w=levels[-1]; h2,w2=(c.shape[0]+1)//2*2,(c.shape[1]+1)//2*2
        c=np.pad(c,((0,h2-c.shape[0]),(0,w2-c.shape[1]),(0,0)));w=np.pad(w,((0,h2-w.shape[0]),(0,w2-w.shape[1])))
        c=c.reshape(h2//2,2,w2//2,2,3).sum((1,3));w=w.reshape(h2//2,2,w2//2,2).sum((1,3))
        levels.append((c,w))
    col=levels[-1][0]/np.maximum(levels[-1][1],1e-9)[...,None]
    for c,w in reversed(levels[:-1]):
        up=np.repeat(np.repeat(col,2,0),2,1)[:c.shape[0],:c.shape[1]]
        up=np.array(Image.fromarray(np.clip(up,0,255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2))).astype(np.float64)
        a=np.clip(w,0,1)[...,None]
        col=(c/np.maximum(w,1e-9)[...,None])*a+up*(1-a)
    return col

if __name__=='__main__':
    src=sys.argv[1]
    for front in sorted(glob.glob(os.path.join(src,(sys.argv[2] if len(sys.argv)>2 else '*')+'-front.webp'))):
        key=os.path.basename(front)[:-len('-front.webp')]
        photo=np.array(Image.open(os.path.join(src,key+'-photo.jpg')).convert('RGB')).astype(np.float64)
        alpha=Image.open(front).getchannel('A')
        # Grow the subject mask so no edge fringe of the building stays in the background.
        hole=np.array(alpha.point(lambda v:255 if v>8 else 0).filter(ImageFilter.MaxFilter(13)))>0
        out=fill(photo,~hole)
        rng=np.random.default_rng(7);out[hole]+=rng.normal(0,1.6,(hole.sum(),3))  # match photo grain
        Image.fromarray(np.clip(out,0,255).astype(np.uint8)).save(os.path.join(src,key+'-plate.jpg'),quality=92)
        print(key, f"{hole.mean()*100:.0f}% filled")
