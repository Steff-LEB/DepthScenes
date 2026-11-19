package com.ali.depthscenes;

import android.content.Context;
import android.graphics.*;
import java.io.*;

final class Scenes {
    static final String[] KEYS = {"temple","fuji","wing","tower","flatiron","airplane","orange-mountain","desert","colosseum","bonsai"};
    /**
     * Default wallpaper position per scene, in 1/1000 of the screen height (positive is lower).
     * Chosen so the subject covers at most 25% of the large two-line clock on a Pixel 9 Pro XL
     * (tools/fit_defaults.py). Flatiron runs through the clock, so no move gets it under 25%.
     */
    static final int[] DEFAULT_SHIFT = {25, 0, 85, 10, 0, 0, 0, 130, 105, 125};
    /** Default size per scene in 1/1000. */
    static final int[] DEFAULT_ZOOM = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 800};
    /**
     * Scenes whose background is a plain backdrop with nothing of the subject in it. Their subject
     * moves and scales on its own over a fixed background, and may be made smaller than the screen.
     */
    static final boolean[] FREE = {false, false, false, false, false, false, false, false, false, true};
    /** Where the background image goes: fixed for FREE scenes, otherwise it moves with the subject. */
    static RectF backRect(int i, int w, int h, int pw, int ph, int shift, int zoom) {
        return FREE[i] ? wallRect(w, h, pw, ph, 0, 1000) : wallRect(w, h, pw, ph, shift, zoom);
    }
    /** Where the subject layer goes. FREE scenes: plain scale and move, no cover zoom. */
    static RectF subjectRect(int i, int w, int h, int pw, int ph, int shift, int zoom) {
        if (!FREE[i]) return wallRect(w, h, pw, ph, shift, zoom);
        float scale = Math.max((float)w/pw, (float)h/ph) * zoom/1000f;
        float bw = pw*scale, bh = ph*scale, dy = h*shift/1000f;
        return new RectF((w-bw)/2, (h-bh)/2+dy, (w+bw)/2, (h+bh)/2+dy);
    }
    static final String[] TITLES = {"Temple roof","Mount Fuji","Airplane wing","Tower in clouds","Flatiron","Between buildings","Amber ridge","Desert mountain","Colosseum","Bonsai"};
    static Bitmap load(Context c, int i, String suffix, int sample) throws IOException {
        try (InputStream input = c.getAssets().open("scenes/" + KEYS[i] + "-" + suffix)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap image = BitmapFactory.decodeStream(input, null, options);
            if (image == null) throw new IOException("Unreadable scene " + KEYS[i]);
            return image;
        }
    }
    static void cover(Canvas c, Bitmap bitmap, Paint p, float zoom, float dx, float dy) {
        float scale = Math.max((float)c.getWidth()/bitmap.getWidth(), (float)c.getHeight()/bitmap.getHeight()) * zoom;
        float width = bitmap.getWidth()*scale, height = bitmap.getHeight()*scale;
        float left = (c.getWidth()-width)*.5f + dx, top = (c.getHeight()-height)*.5f + dy;
        c.drawBitmap(bitmap, null, new RectF(left,top,left+width,top+height),p);
    }
    /**
     * Where the photo sits on a w x h screen: cover fit, moved down by shiftPermille/1000 of the
     * height, and zoomed by zoomPermille and then just enough that the move never exposes an empty edge. The wallpaper
     * (background) and the building layer both use this rectangle, so they move together.
     */
    static RectF wallRect(int w, int h, int pw, int ph, int shiftPermille, int zoomPermille) {
        float scale = Math.max((float)w/pw, (float)h/ph) * zoomPermille/1000f;
        float dy = h*shiftPermille/1000f;
        scale *= Math.max(1f, (h + 2*Math.abs(dy)) / (ph*scale));
        float bw = pw*scale, bh = ph*scale;
        return new RectF((w-bw)/2, (h-bh)/2+dy, (w+bw)/2, (h+bh)/2+dy);
    }
    /** Cutout zoom in 1/1000 (1100 = 110%). Older builds stored whole percent in "scale". */
    static int zoom(android.content.SharedPreferences p) {
        return p.contains("zoom") ? p.getInt("zoom", 1100) : p.getInt("scale", 110) * 10;
    }
    /** Vertical cutout offset in 1/1000 of the screen height; positive moves the cutout down. */
    static int shift(android.content.SharedPreferences p, int i) { return p.getInt("shift." + KEYS[i], DEFAULT_SHIFT[i]); }
    /** Wallpaper zoom per scene in 1/1000 (1000 = cover fit). Moves background and building together. */
    static int wallZoom(android.content.SharedPreferences p, int i) { return p.getInt("wallzoom." + KEYS[i], DEFAULT_ZOOM[i]); }
    private Scenes() {}
}
