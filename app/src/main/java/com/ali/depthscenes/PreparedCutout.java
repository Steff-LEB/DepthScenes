package com.ali.depthscenes;

import android.graphics.*;
import android.view.Surface;
import android.view.SurfaceControl;

/**
 * The whole building on a full-screen, prepainted, initially hidden layer. No window, view hierarchy,
 * or input channel. Notification cards are cut out of it so they stay visible on top of the building.
 */
final class PreparedCutout implements AutoCloseable {
    final SurfaceControl control;
    private final Surface producer;
    final int width, height;
    private final Bitmap art;
    private final Paint clear=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final java.util.List<Rect> holes=new java.util.ArrayList<>();
    private final float radius;
    private boolean drawn;
    /** zoomPermille scales the building around the screen centre to match how the lock screen shows the wallpaper. shiftPermille matches the wallpaper's vertical position. */
    PreparedCutout(Bitmap photo,int w,int h,int zoomPermille,int tonePercent,int shiftPermille,int wallZoomPermille,int scene) {
        width=w; height=h;
        control=new SurfaceControl.Builder().setName("Depth Scenes photo").setBufferSize(w,h)
            .setOpaque(false).setFormat(PixelFormat.RGBA_8888).setHidden(true).build();
        Surface pending=null;Bitmap painted=null;
        try {
            painted=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            {
                Canvas canvas=new Canvas(painted);
                RectF rect=Scenes.subjectRect(scene,w,h,photo.getWidth(),photo.getHeight(),shiftPermille,wallZoomPermille);
                float zoom=zoomPermille/1000f;
                canvas.scale(zoom,zoom,w/2f,h/2f);
                Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                float tone=tonePercent/100f;
                ColorMatrix cm=new ColorMatrix();cm.setScale(tone,tone,tone,1);
                paint.setColorFilter(new ColorMatrixColorFilter(cm));
                canvas.drawBitmap(photo,null,rect,paint);
            }
            art=painted;
            radius=w*.045f;
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            pending=new Surface(control);
            producer=pending;
            paint(java.util.Collections.emptyList());
        } catch(RuntimeException|Error failure) {
            if(pending!=null)pending.release();
            if(painted!=null)painted.recycle();
            control.release(); throw failure;
        }
    }
    /** Redraws the building with these screen areas (notification cards) cut out. Skips identical sets. */
    boolean paint(java.util.List<Rect> cut){
        if(drawn && cut.equals(holes))return false;
        Canvas canvas=producer.lockCanvas(null);
        try {
            canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
            canvas.drawBitmap(art,0,0,null);
            for(Rect r:cut)canvas.drawRoundRect(new RectF(r),radius,radius,clear);
        } finally { producer.unlockCanvasAndPost(canvas); }
        holes.clear();for(Rect r:cut)holes.add(new Rect(r));
        drawn=true;
        return true;
    }
    @Override public void close(){ producer.release();control.release();art.recycle(); }
}
