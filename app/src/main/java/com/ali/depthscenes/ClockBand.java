package com.ali.depthscenes;

/** Portrait layout measured on Pixel 9 Pro XL. The earliest date pixels were at y/H = 0.596. */
final class ClockBand {
    static int top(int height) { return (int)(height * 0.075f); }
    static int bottom(int height) { return (int)Math.ceil(height * 0.58); }
    static boolean clockCandidate(int screenW,int screenH,int left,int top,int right,int bottom) {
        return top>=top(screenH) && bottom<=bottom(screenH) && right-left>screenW*.10f && bottom>top;
    }
    private ClockBand() {}
}
