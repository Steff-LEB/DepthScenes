package com.ali.depthscenes;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;
import rikka.shizuku.Shizuku;

public final class Studio extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Pixel px;
    private LinearLayout body, strip;
    private TextView title, state;
    private Pixel.Morph apply;
    private Hero hero;
    private int selected, request;
    private SharedPreferences prefs;
    private boolean applying;
    private final Shizuku.OnRequestPermissionResultListener granted=(code,result)->runOnUiThread(this::refresh);
    private final Runnable refreshState=new Runnable(){public void run(){refresh();main.postDelayed(this,1200);}};
    @Override public void onCreate(Bundle b){
        super.onCreate(b);prefs=getSharedPreferences("scene",MODE_PRIVATE);selected=b==null?prefs.getInt("index",4):b.getInt("selected",4);
        if(selected<0||selected>=Scenes.KEYS.length)selected=0;
        px=new Pixel(this);
        Shizuku.addRequestPermissionResultListener(granted);
        // Edge to edge, like Pixel's own apps.
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if(!px.dark)getWindow().getInsetsController().setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(px.bg);scroll.setClipToPadding(false);
        body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(16),dp(16),dp(16),dp(28));scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((v,in)->{android.graphics.Insets bar=in.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bar.left,bar.top,bar.right,bar.bottom);return in;});

        TextView head=px.text(this,"Depth Scenes",36,px.onSurface,false);head.setPadding(dp(8),dp(28),dp(8),dp(2));body.addView(head);
        title=px.text(this,"",16,px.variant,false);title.setPadding(dp(8),0,dp(8),dp(16));body.addView(title);

        hero=new Hero();LinearLayout.LayoutParams heroBox=new LinearLayout.LayoutParams(-1,dp(360));body.addView(hero,heroBox);

        HorizontalScrollView rail=new HorizontalScrollView(this);rail.setHorizontalScrollBarEnabled(false);rail.setClipToPadding(false);
        rail.setPadding(dp(4),dp(14),dp(4),dp(14));
        strip=new LinearLayout(this);rail.addView(strip);body.addView(rail);
        for(int i=0;i<Scenes.KEYS.length;i++){
            final int idx=i;Pixel.Tile tile=new Pixel.Tile(this,px);tile.setContentDescription(Scenes.TITLES[i]);
            try{tile.setImageBitmap(Scenes.load(this,i,"thumb.jpg",2));}catch(Exception ignored){}
            tile.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);select(idx);});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(68),dp(104));lp.setMarginEnd(dp(6));strip.addView(tile,lp);
        }

        apply=new Pixel.Morph(this,px,"Apply to lock screen",px.primary,px.onPrimary,56);apply.setOnClickListener(v->applyPhoto());
        body.addView(apply,spaced(-1,0,8));
        Pixel.Morph align=new Pixel.Morph(this,px,"Adjust alignment",px.tonal,px.onTonal,56);align.setOnClickListener(v->calibrate());
        body.addView(align,spaced(-1,10,20));

        // Clock layer card
        LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setBackground(px.surface(px.card,28));card.setPadding(dp(20),dp(20),dp(20),dp(20));
        card.addView(px.text(this,"Clock layer",22,px.onSurface,false));
        state=px.text(this,"",14,px.variant,false);state.setPadding(0,dp(6),0,dp(14));card.addView(state);
        LinearLayout row=new LinearLayout(this);row.setOrientation(0);
        Pixel.Morph connect=new Pixel.Morph(this,px,"Shizuku",px.tonal,px.onTonal,48);connect.setOnClickListener(v->authorize());
        Pixel.Morph access=new Pixel.Morph(this,px,"Accessibility",px.tonal,px.onTonal,48);
        access.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,-2,1);half.setMarginEnd(dp(8));row.addView(connect,half);
        row.addView(access,new LinearLayout.LayoutParams(0,-2,1));card.addView(row);
        TextView hint=px.text(this,"Turn on Depth Scenes clock layer in Accessibility, and turn off Luma Gallery’s old depth service.",13,px.variant,false);
        hint.setPadding(0,dp(14),0,0);card.addView(hint);
        body.addView(card);

        setContentView(scroll);select(selected);
        // Gentle entrance: content rises into place.
        body.setAlpha(0f);body.setTranslationY(dp(24));
        body.animate().alpha(1f).translationY(0).setDuration(500).setInterpolator(Pixel.EMPHASIZED).start();
    }
    private LinearLayout.LayoutParams spaced(int w,int top,int bottom){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(w,-2);lp.topMargin=dp(top);lp.bottomMargin=dp(bottom);return lp;}
    private int dp(float n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private TextView label(String s,int size,int color){TextView t=px.text(this,s,size,color,false);t.setPadding(0,dp(4),0,dp(4));return t;}
    private void select(int i){
        boolean first=hero.base==null;
        selected=i;title.setText(Scenes.TITLES[i]);
        for(int k=0;k<strip.getChildCount();k++)((Pixel.Tile)strip.getChildAt(k)).choose(k==i,!first);
        int token=++request;
        io.execute(()->{try{Bitmap photo=Scenes.load(this,i,"plate.jpg",2),front=Scenes.load(this,i,"front.webp",2);
            main.post(()->{if(token==request&&!isDestroyed()){
                hero.base=photo;hero.cut=front;hero.invalidate();
                // Crossfade the preview the way Pixel's wallpaper picker does.
                hero.setAlpha(0f);hero.setScaleX(.96f);hero.setScaleY(.96f);
                hero.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(Pixel.EMPHASIZED).start();
            }});
        }catch(Exception ignored){}});
    }
    private final class Hero extends View{
        Bitmap base,cut;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        Hero(){super(Studio.this);setContentDescription("Illustrated clock preview");}
        @Override protected void onDraw(Canvas c){
            if(base==null||cut==null)return;
            // The frame preserves the Pixel's phone shape at any picker width.
            float h=getHeight(),w=Math.min(getWidth(),h*1344/2992f),left=(getWidth()-w)/2;
            c.save();c.translate(left,0);Path rounded=new Path();rounded.addRoundRect(0,0,w,h,dp(28),dp(28),Path.Direction.CW);c.clipPath(rounded);
            // Same geometry as the lock screen: background and building move together.
            int sh=Scenes.shift(prefs,selected),wz=Scenes.wallZoom(prefs,selected),iw=Math.round(w),ih=Math.round(h);
            RectF rect=Scenes.backRect(selected,iw,ih,base.getWidth(),base.getHeight(),sh,wz);
            RectF front=Scenes.subjectRect(selected,iw,ih,cut.getWidth(),cut.getHeight(),sh,wz);
            paint.setColor(-1);c.drawBitmap(base,null,rect,paint);paint.setTypeface(Pixel.font(false));paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(w*.45f);c.drawText("10",w*.5f,h*.418f,paint);c.drawText("28",w*.5f,h*.557f,paint);
            c.drawBitmap(cut,null,front,paint);c.restore();
        }
    }
    private void authorize(){
        try{if(!Shizuku.pingBinder()){message("Start Shizuku on this phone, then tap Connect Shizuku.");return;}
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)Shizuku.requestPermission(72);
            else message("Shizuku is connected.");
        }catch(RuntimeException e){message("Shizuku is unavailable. Start it and try again.");}
        refresh();
    }
    private void refresh(){
        if(state==null)return;String access="offline";
        try{if(Shizuku.pingBinder())access=Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED?"authorized":"permission needed";}catch(RuntimeException ignored){}
        String active=prefs.getInt("index",-1)>=0?Scenes.TITLES[prefs.getInt("index",0)]:"None applied";
        state.setText(active+"  ·  Shizuku "+access+"\n"+ClockDepth.status+"\nLast wake: "+prefs.getString("wakeReport","no completed wake yet"));
    }
    private void applyPhoto(){
        applyScene(selected);
    }
    /** Sets the background-only image as the lock wallpaper. The building is drawn by the clock layer. */
    private void applyScene(int index){
        if(applying)return;applying=true;apply.setEnabled(false);apply.setText("Applying…");
        final int shift=Scenes.shift(prefs,index),wallZoom=Scenes.wallZoom(prefs,index);Rect bounds=getWindowManager().getMaximumWindowMetrics().getBounds();
        int w=Math.min(bounds.width(),bounds.height()),h=Math.max(bounds.width(),bounds.height());
        io.execute(()->{String result;
            try{
                WallpaperManager wm=WallpaperManager.getInstance(this);
                if(!wm.isSetWallpaperAllowed()||!wm.isWallpaperSupported())throw new IllegalStateException();
                Bitmap src=Scenes.load(this,index,"plate.jpg",1);
                Bitmap base=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                Canvas canvas=new Canvas(base);
                canvas.drawBitmap(src,null,Scenes.backRect(index,w,h,src.getWidth(),src.getHeight(),shift,wallZoom),new Paint(Paint.FILTER_BITMAP_FLAG));
                int id=wm.setBitmap(base,null,false,WallpaperManager.FLAG_LOCK);
                if(id<=0)throw new IllegalStateException();
                prefs.edit().putInt("index",index).putInt("wallpaper",id).commit();
                src.recycle();base.recycle();result="Applied "+Scenes.TITLES[index]+" to your lock screen.";
            }catch(Exception e){result="Android could not apply this scene. Try again.";}
            final String feedback=result;main.post(()->{if(!isDestroyed()){applying=false;apply.setEnabled(true);apply.setText("Apply to lock screen");message(feedback);refresh();
                apply.setScaleX(.9f);apply.setScaleY(.9f);apply.animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();}});
        });
    }
    private void calibrate(){
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(1);panel.setPadding(dp(20),dp(8),dp(20),0);
        int screenH=Math.max(getWindowManager().getMaximumWindowMetrics().getBounds().width(),getWindowManager().getMaximumWindowMetrics().getBounds().height());
        // Scale: 80.0% to 140.0% in 0.5% steps (stored as 1/1000).
        SeekBar scale=slider(panel,"Cutout scale",0,120,(Scenes.zoom(prefs)-800)/5,
            v->String.format(java.util.Locale.ROOT,"%.1f%%",(800+v*5)/10f));
        final int scene=selected;
        // Whole wallpaper (background and building) for this scene: 100-160% in 1% steps.
        boolean free=Scenes.FREE[scene];final int minSize=free?50:100;
        SeekBar wall=slider(panel,free?"Subject size":"Wallpaper scale",minSize,160,Scenes.wallZoom(prefs,scene)/10,v->v+"%");
        // Height of the whole wallpaper: 30% of the screen up or down in 0.1% steps (1/1000 of screen height).
        SeekBar shift=slider(panel,free?"Subject position":"Wallpaper position",-300,300,Scenes.shift(prefs,scene),
            v->v==0?"centered":String.format(java.util.Locale.ROOT,"%.1f%% · %d px %s",Math.abs(v)/10f,Math.round(Math.abs(v)*screenH/1000f),v>0?"lower":"higher"));
        SeekBar tone=slider(panel,"Cutout brightness",50,105,prefs.getInt("tone",80),v->v+"%");
        TextView tip=label(free?"Subject size and position are saved for "+Scenes.TITLES[scene]+" and move only the subject; the backdrop stays still. Cutout scale and brightness change the subject for every scene. Reset restores this scene's default, set so the subject covers at most a quarter of the clock.":"Wallpaper scale and position are saved for "+Scenes.TITLES[scene]+" and move the background and building together. Cutout scale and brightness only change the building. Reset restores this scene's default, set so the building covers at most a quarter of the clock.",13,px.variant);
        panel.addView(tip);
        ScrollView wrap=new ScrollView(this);wrap.addView(panel);
        new AlertDialog.Builder(this).setTitle("Match the wallpaper · "+Scenes.TITLES[scene]).setView(wrap).setNegativeButton("Cancel",null)
          .setNeutralButton("Reset",(d,w)->saveAlignment(scene,1100,Scenes.DEFAULT_SHIFT[scene],Scenes.DEFAULT_ZOOM[scene],80))
          .setPositiveButton("Save",(d,w)->saveAlignment(scene,800+scale.getProgress()*5,shift.getProgress()-300,(minSize+wall.getProgress())*10,50+tone.getProgress())).show();
    }
    private void saveAlignment(int scene,int zoom,int shift,int wallZoom,int tone){
        boolean moved=shift!=Scenes.shift(prefs,scene)||wallZoom!=Scenes.wallZoom(prefs,scene);
        prefs.edit().remove("scale").remove("shift").putInt("zoom",zoom).putInt("tone",tone)
            .putInt("shift."+Scenes.KEYS[scene],shift).putInt("wallzoom."+Scenes.KEYS[scene],wallZoom).commit();
        hero.invalidate();
        // The wallpaper itself moved, so set it again if this scene is on the lock screen.
        if(moved && !Scenes.FREE[scene] && prefs.getInt("index",-1)==scene)applyScene(scene);
    }
    private interface Format{String of(int value);}
    /** A slider with fine - and + buttons. Progress is value-min. */
    private SeekBar slider(LinearLayout p,String name,int min,int max,int value,Format f){
        value=Math.max(min,Math.min(max,value));
        TextView t=label(name+" · "+f.of(value),15,px.onSurface);p.addView(t);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        SeekBar s=new SeekBar(this);s.setMax(max-min);s.setProgress(value-min);
        Pixel.Morph less=new Pixel.Morph(this,px,"−",px.tonal,px.onTonal,44);less.setPadding(0,0,0,0);
        less.setOnClickListener(v->s.setProgress(Math.max(0,s.getProgress()-1)));
        Pixel.Morph more=new Pixel.Morph(this,px,"+",px.tonal,px.onTonal,44);more.setPadding(0,0,0,0);
        more.setOnClickListener(v->s.setProgress(Math.min(s.getMax(),s.getProgress()+1)));
        row.addView(less,new LinearLayout.LayoutParams(dp(44),dp(44)));
        row.addView(s,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(more,new LinearLayout.LayoutParams(dp(44),dp(44)));
        p.addView(row);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar v,int a,boolean u){t.setText(name+" · "+f.of(a+min));}public void onStartTrackingTouch(SeekBar v){}public void onStopTrackingTouch(SeekBar v){}});
        return s;
    }
    private void message(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override public void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("selected",selected);}
    @Override public void onResume(){super.onResume();main.post(refreshState);}
    @Override public void onPause(){main.removeCallbacks(refreshState);super.onPause();}
    @Override public void onDestroy(){Shizuku.removeRequestPermissionResultListener(granted);main.removeCallbacksAndMessages(null);io.shutdown();super.onDestroy();}
}
