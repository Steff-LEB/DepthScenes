package com.ali.depthscenes;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import rikka.shizuku.Shizuku;

public final class ClockDepth extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {
    static volatile String status="Clock layer off";
    static volatile boolean connected;
    private static final int LEASE=320;
    // Once the cutout is visible, a slightly older confirmation keeps it there. A slow
    // Shizuku sample used to hide the layer and restart the 25% fade, which flickered.
    private static final int HOLD=900;
    private static final int FADE_IN=450;
    /** The building rises this fraction of the screen height while it fades in. */
    private static final float SLIDE=.06f;
    /** Notification cards seen by the last System UI scan, in screen pixels. */
    private final java.util.ArrayList<Rect> notes=new java.util.ArrayList<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Runnable poll=this::check;
    private long confirmedAt;
    /**
     * While the building is visible, check the lock state every frame. Unlock events and window samples
     * arrive late, so this hides the layer the moment the lock screen starts going away or an app
     * window (the home screen during the unlock swipe) becomes visible.
     */
    private final Runnable watchdog=new Runnable(){public void run(){
        if(!shown||dead)return;
        KeyguardManager km=getSystemService(KeyguardManager.class);
        if(!km.isKeyguardLocked()){invalidateProof("Unlocking");schedule(0);return;}
        if(appWindowVisible()){invalidateProof("Leaving lock screen");schedule(0);return;}
        main.postDelayed(this,16);
    }};
    private final Runnable expire=new Runnable(){public void run(){
        // Keep the visible layer while the next sample is in flight and System UI still looks safe.
        if(shown && busy && SystemClock.elapsedRealtime()-confirmedAt<HOLD && getSystemService(KeyguardManager.class).isKeyguardLocked() && safeUi()){main.postDelayed(this,40);return;}
        suppress("Waiting for fresh lock confirmation");
    }};
    private IBinder helper;
    private boolean bound, busy, dead;
    private int generation;
    private SharedPreferences prefs;
    private Shizuku.UserServiceArgs args;
    private PreparedCutout cutout;
    private int preparation;
    private boolean preparing;
    private String uiReason="";
    private boolean inWake, appeared;
    private String lastDecision="";
    private final java.util.LinkedHashMap<String,Integer> wakeChecks=new java.util.LinkedHashMap<>();
    private long wakeStart;
    private int wakeNumber, probesStarted, probesInvalidated;
    private long firstCommitMs=-1;
    private long requestStart=-1, longestRequestMs;
    private int attachedWindow=-1;
    private Rect clock=new Rect();
    private boolean shown;
    private WindowManager windows;
    private ValueAnimator reveal;
    private int lockWindow=-1;
    private int appliedId=-1;
    private final Runnable reconnect=this::connectProbe;
    private final Shizuku.OnBinderReceivedListener received=()->main.post(reconnect);
    private final Shizuku.OnBinderDeadListener lost=()->main.post(()->{helper=null; bound=false; invalidateProof("Shizuku disconnected");});
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){if(dead)return; helper=b; schedule(0);}
        public void onServiceDisconnected(ComponentName n){helper=null;bound=false;invalidateProof("Shizuku helper offline");schedule(350);}
    };
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            String a=i.getAction();
            if(Intent.ACTION_SCREEN_OFF.equals(a)||Intent.ACTION_USER_PRESENT.equals(a)){
                touching=false;quietUntil=0;
                boolean screenOff=Intent.ACTION_SCREEN_OFF.equals(a);
                String reason=screenOff?"Screen off":"User present";
                invalidateProof(reason);
                main.removeCallbacks(poll);
                if(!screenOff && getSystemService(KeyguardManager.class).isKeyguardLocked()){
                    schedule(0);
                }else endWake(reason);
                return;
            }
            if(Intent.ACTION_WALLPAPER_CHANGED.equals(a)){invalidateProof("Checking applied wallpaper");}
            if(Intent.ACTION_SCREEN_ON.equals(a)){startWake();touching=false;quietUntil=0;invalidateProof("Checking normal lock");}
            schedule(0);
        }
    };
    @Override protected void onServiceConnected(){
        dead=false; connected=true; windows=getSystemService(WindowManager.class);
        prefs=getSharedPreferences("scene",MODE_PRIVATE);prefs.registerOnSharedPreferenceChangeListener(this);
        args=new Shizuku.UserServiceArgs(new ComponentName(this,WindowProbe.class)).daemon(false).tag("clock-proof").version(5).processNameSuffix("clockproof");
        Shizuku.addBinderReceivedListenerSticky(received); Shizuku.addBinderDeadListener(lost);
        IntentFilter f=new IntentFilter(Intent.ACTION_SCREEN_ON);f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);f.addAction(Intent.ACTION_WALLPAPER_CHANGED);
        registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);
        getSystemService(DisplayManager.class).registerDisplayListener(displayChanges,main);
        loadApplied();addTouchSpy();connectProbe();schedule(0);
    }
    private void startWake(){
        if(inWake)return;
        inWake=true;appeared=false;wakeNumber++;probesStarted=0;probesInvalidated=0;firstCommitMs=-1;requestStart=-1;longestRequestMs=0;wakeChecks.clear();lastDecision="Waiting for the display";wakeStart=SystemClock.elapsedRealtime();
    }
    private void endWake(String ended){
        if(!inWake)return;
        recordInterval();
        String detail=(appeared?"Reveal requested":"Cutout hidden")+" · "+lastDecision;
        if(appeared)detail+="\nCompositor: "+(firstCommitMs<0?"commit unconfirmed":"first commit at "+firstCommitMs+" ms")
            +" · longest reveal request "+longestRequestMs+" ms";
        String frequent="";int highest=0;
        for(java.util.Map.Entry<String,Integer> entry:wakeChecks.entrySet()){
            if(entry.getValue()>highest){frequent=entry.getKey();highest=entry.getValue();}
        }
        detail+="\nChecks started: "+probesStarted+" · superseded: "+probesInvalidated+" · ended: "+ended;
        if(highest>0)detail+="\nMost frequent check: "+frequent+" ("+highest+")";
        prefs.edit().putString("wakeReport",detail).apply();
        inWake=false;
    }
    private final DisplayManager.DisplayListener displayChanges=new DisplayManager.DisplayListener(){
        public void onDisplayAdded(int id){}
        public void onDisplayRemoved(int id){if(id==Display.DEFAULT_DISPLAY)invalidateProof("Display removed");}
        public void onDisplayChanged(int id){
            if(id!=Display.DEFAULT_DISPLAY||dead)return;
            Display d=getSystemService(DisplayManager.class).getDisplay(id);
            if(d==null||d.getState()!=Display.STATE_ON){invalidateProof("Display sleeping");return;}
            if(getSystemService(KeyguardManager.class).isKeyguardLocked()){startWake();schedule(0);}
        }
    };
    private void connectProbe(){
        if(dead||bound||args==null)return;
        try {
            if(!Shizuku.pingBinder()||Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)return;
            Shizuku.bindUserService(args,connection);bound=true;
        }catch(RuntimeException e){bound=false;}
    }
    private void loadApplied(){
        invalidateProof("Preparing photo layer");
        if(cutout!=null){cutout.close();cutout=null;}
        int version=++preparation;
        preparing=false;
        appliedId=prefs.getInt("wallpaper",-1);
        int index=prefs.getInt("index",-1);
        if(index<0||index>=Scenes.KEYS.length||appliedId<=0)return;
        Rect bounds=windows.getMaximumWindowMetrics().getBounds();
        int w=Math.min(bounds.width(),bounds.height()),h=Math.max(bounds.width(),bounds.height());
        int zoom=Scenes.zoom(prefs),tone=prefs.getInt("tone",80),shift=Scenes.shift(prefs,index),wallZoom=Scenes.wallZoom(prefs,index);
        preparing=true;
        worker.execute(()->{
            try {
                Bitmap bmp=Scenes.load(this,index,"front.webp",1);
                PreparedCutout next;
                try{next=new PreparedCutout(bmp,w,h,zoom,tone,shift,wallZoom,index);}finally{bmp.recycle();}
                main.post(()->{
                    if(dead||preparation!=version){next.close();return;}
                    preparing=false;cutout=next;schedule(0);
                });
            }catch(Exception e){
                main.post(()->{if(!dead&&preparation==version){preparing=false;suppress("Photo buffer unavailable: "+e.getClass().getSimpleName());}});
            }
        });
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){
        if("wallpaper".equals(key)||"scale".equals(key)||"zoom".equals(key)||"tone".equals(key)||(key!=null&&(key.startsWith("shift.")||key.startsWith("wallzoom."))))loadApplied();
    }
    private void schedule(long delay){if(dead)return;main.removeCallbacks(poll);main.postDelayed(poll,delay);}
    private void invalidateProof(String why){generation++;suppress(why);}
    private boolean touching;
    private long quietUntil;
    /** After a finger touches the screen, wait this long before the building may return. */
    private static final int TOUCH_QUIET=800;
    private View touchSpy;
    /**
     * A 1 px window placed off screen that never receives taps. FLAG_WATCH_OUTSIDE_TOUCH makes Android
     * report every finger-down anywhere on the display to it, so the building hides at the start of the
     * unlock swipe instead of after the keyguard reports that it is going away.
     */
    private void addTouchSpy(){
        try{
            View v=new View(this);
            v.setOnTouchListener((view,e)->{
                if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE)onScreenTouched();
                return false;
            });
            WindowManager.LayoutParams lp=new WindowManager.LayoutParams(1,1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH|
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            lp.gravity=Gravity.TOP|Gravity.START;lp.x=-40;lp.y=-40;lp.setTitle("Depth Scenes touch watch");
            windows.addView(v,lp);touchSpy=v;
        }catch(RuntimeException e){touchSpy=null;}
    }
    private void onScreenTouched(){
        if(dead)return;
        quietUntil=SystemClock.elapsedRealtime()+TOUCH_QUIET;
        if(shown){invalidateProof("Touch on lock screen");}
        else generation++;
        schedule(TOUCH_QUIET);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(dead||prefs==null)return;
        int t=event.getEventType();
        if(t==AccessibilityEvent.TYPE_TOUCH_INTERACTION_START||t==AccessibilityEvent.TYPE_VIEW_SCROLLED){
            if(t==AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)touching=true;
            quietUntil=SystemClock.elapsedRealtime()+350;
            invalidateProof("Gesture in progress");schedule(80);return;
        }
        if(t==AccessibilityEvent.TYPE_TOUCH_INTERACTION_END){
            touching=false;quietUntil=SystemClock.elapsedRealtime()+220;invalidateProof("Checking gesture destination");schedule(220);return;
        }
        if(t==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED||t==AccessibilityEvent.TYPE_WINDOWS_CHANGED||t==AccessibilityEvent.TYPE_VIEW_FOCUSED){
            if(event.getWindowChanges()==AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED)return;
            // Our own surface appearing or disappearing should not restart the reveal.
            if(event.getPackageName()!=null && getPackageName().contentEquals(event.getPackageName()))return;
            // Attaching the overlay and the clock's own animation report window changes from System UI.
            // While the same lock window still passes the UI scan, keep the layer and let the next sample decide.
            if(shown && t!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && safeUi() && lockWindow==attachedWindow){schedule(0);return;}
            invalidateProof("Window changed");schedule(0);
        }else if(event.getPackageName()!=null && "com.android.systemui".contentEquals(event.getPackageName())){
            if(shown && !safeUi()){invalidateProof(uiReason);schedule(0);}
            else if(!shown && !busy && getSystemService(KeyguardManager.class).isKeyguardLocked())schedule(0);
        }
    }
    private String gate(){
        Display display=getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        KeyguardManager km=getSystemService(KeyguardManager.class);
        Rect bounds=windows.getMaximumWindowMetrics().getBounds();
        if(display==null||display.getState()!=Display.STATE_ON||!getSystemService(PowerManager.class).isInteractive())return "Waiting for the display";
        if(!km.isKeyguardLocked())return "Unlocked";
        if(bounds.height()<=bounds.width())return "Landscape display";
        if(appliedId<=0)return "Apply a scene first";
        if(WallpaperManager.getInstance(this).getWallpaperId(WallpaperManager.FLAG_LOCK)!=appliedId)return "Lock wallpaper changed";
        return null;
    }
    private boolean scanNode(AccessibilityNodeInfo n,int depth,int[] count,Rect found,Rect screen){
        if(n==null)return false;
        if(++count[0]>650 || depth>40){uiReason="Incomplete System UI tree";return false;}
        if(n.isVisibleToUser()){
            String id=String.valueOf(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
            String cls=String.valueOf(n.getClassName()).toLowerCase(Locale.ROOT);
            Rect nodeBounds=new Rect();n.getBoundsInScreen(nodeBounds);
            if(n.isPassword()||n.isEditable()||id.contains("bouncer")||id.contains("password")||
               id.contains("pin_entry")||id.contains("pinentry")||id.contains("pin_view")||id.contains("numpad")||
               id.contains("lockpattern")||id.contains("security_view")||cls.contains("password")){
                uiReason="Credential controls visible";return false;
            }
            if(id.contains("qs_panel")||id.endsWith("/settings_button")){
                uiReason="Quick settings visible";return false;
            }
            boolean notification=cls.contains("expandablenotificationrow") || id.endsWith("/status_bar_latest_event_content") ||
                id.contains("notification_row") || id.endsWith("/notification_main_column") ||
                id.contains("notification_shelf");
            // Notification cards are cut out of the building so they stay on top and readable.
            if(notification && !nodeBounds.isEmpty() && nodeBounds.bottom>0 &&
               nodeBounds.top<screen.height() && nodeBounds.right>0 && nodeBounds.left<screen.width()){
                Rect card=new Rect(nodeBounds);card.intersect(0,0,screen.width(),screen.height());
                boolean inside=false;
                for(Rect known:notes)if(known.contains(card)){inside=true;break;}
                if(!inside){notes.removeIf(card::contains);notes.add(card);}
            }
            if((id.contains("keyguard_clock")||id.contains("lockscreen_clock")||id.endsWith("/clock_view"))&&!id.contains("status_bar")){
                if(ClockBand.clockCandidate(screen.width(),screen.height(),nodeBounds.left,nodeBounds.top,nodeBounds.right,nodeBounds.bottom))found.union(nodeBounds);
            }
        }
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=n.getChild(i);
            if(child==null || !scanNode(child,depth+1,count,found,screen))return false;
        }
        return true;
    }
    /** True when any app window is on screen: the home screen or an app is appearing over or after the lock screen. */
    private boolean appWindowVisible(){
        try{
            for(AccessibilityWindowInfo w:getWindows())if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION)return true;
        }catch(RuntimeException ignored){}
        return false;
    }
    private boolean safeUi(){
        uiReason="Normal System UI window unavailable";
        notes.clear();
        try {
            List<AccessibilityWindowInfo> list=getWindows();
            boolean confirmed=false;Rect found=new Rect();
            Rect screen=windows.getMaximumWindowMetrics().getBounds();
            for(AccessibilityWindowInfo w:list){
                int type=w.getType();
                if(type==AccessibilityWindowInfo.TYPE_INPUT_METHOD){uiReason="Keyboard window visible";return false;}
                if(type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)continue;
                if(type==AccessibilityWindowInfo.TYPE_APPLICATION){uiReason="App window visible";return false;}
                if(!w.isFocused()&&!w.isActive())continue;
                AccessibilityNodeInfo root=w.getRoot();
                if(root==null||!"com.android.systemui".contentEquals(root.getPackageName()==null?"":root.getPackageName())||
                    !scanNode(root,0,new int[]{0},found,screen))return false;
                Rect bounds=new Rect();w.getBoundsInScreen(bounds);
                if(bounds.left!=0||bounds.top!=0||bounds.width()<screen.width()*.85f || bounds.height()<screen.height()*.85f)return false;
                lockWindow=w.getId();confirmed=true;
            }
            if(confirmed){clock=found;if(shown&&cutout!=null)cutHoles();}
            return confirmed;
        }catch(RuntimeException e){return false;}
    }
    private void check(){
        if(dead)return;
        String gate=gate();
        if(gate!=null){retryGate(gate);return;}
        startWake();
        if(cutout==null){if(preparing)suppress("Preparing photo buffer");else if(!status.startsWith("Photo buffer unavailable"))suppress("Photo buffer unavailable");return;}
        Rect size=windows.getMaximumWindowMetrics().getBounds();
        if(size.width()!=cutout.width||size.height()!=cutout.height){loadApplied();return;}
        if(helper==null){suppress("Start and authorize Shizuku");connectProbe();schedule(500);return;}
        if(busy)return;
        int token=generation, sampledWake=wakeNumber;IBinder binder=helper;busy=true;probesStarted++;
        worker.execute(()->{
            boolean normal=false;long sampled=0;String reason="Shizuku query failed";
            Parcel req=Parcel.obtain(),reply=Parcel.obtain();
            try {
                req.writeInterfaceToken(WindowProbe.DESCRIPTOR);
                if(binder.transact(WindowProbe.CHECK,req,reply,0)){reply.readException();normal=reply.readInt()==1;sampled=reply.readLong();if(reply.dataAvail()>0)reason=reply.readString();}
            }catch(Exception ignored){}finally{req.recycle();reply.recycle();}
            final boolean pass=normal;final long stamp=sampled;final String result=reason;
            main.post(()->{
                busy=false;if(dead)return;
                long age=SystemClock.elapsedRealtime()-stamp;
                if(token!=generation){if(inWake && wakeNumber==sampledWake)probesInvalidated++;schedule(0);return;}
                String current=gate();if(current!=null){retryGate(current);return;}
                String checkResult=result;
                if(!pass){suppress(result);}
                else if(!SceneProof.matchesCredential(result,getSystemService(KeyguardManager.class).isDeviceLocked())){
                    checkResult="Waiting for matching clock and authentication state";suppress(checkResult);
                }
                else if(touching || stamp<quietUntil){checkResult="Gesture settling";suppress(checkResult);}
                else if(age<0||age>=(shown?HOLD:LEASE)){checkResult="Window sample expired";suppress(checkResult+" ("+age+" ms)");}
                else if(!safeUi()){checkResult=uiReason;suppress(uiReason);}
                else {long left=(shown?HOLD:LEASE)-(SystemClock.elapsedRealtime()-stamp);
                    if(left>0){show();if(shown){confirmedAt=Math.max(confirmedAt,stamp);main.removeCallbacks(expire);main.postDelayed(expire,left);}}
                    else {checkResult="Expired lock confirmation";suppress(checkResult);}
                }
                if(inWake && checkResult!=null){
                    Integer previous=wakeChecks.get(checkResult);
                    wakeChecks.put(checkResult,previous==null?1:previous+1);
                }
                schedule(90);
            });
        });
    }
    private void retryGate(String reason){
        // SCREEN_ON can precede STATE_ON or isInteractive(). Continue during this transition.
        if("Unlocked".equals(reason)){
            if(inWake && probesStarted==0 && SystemClock.elapsedRealtime()-wakeStart<1200){
                invalidateProof("Waiting for lock screen to appear");schedule(120);return;
            }
            invalidateProof(reason);endWake("Lock screen dismissed");return;
        }
        invalidateProof(reason);
        if(getSystemService(KeyguardManager.class).isKeyguardLocked() &&
           (SystemClock.elapsedRealtime()-wakeStart<2500 || getSystemService(PowerManager.class).isInteractive())) schedule(180);
    }
    private Rect region(){
        // The wallpaper is the background only, so the whole building layer is shown.
        Rect b=windows.getMaximumWindowMetrics().getBounds();
        return new Rect(0,0,b.width(),b.height());
    }
    private void recordInterval(){
        if(requestStart>=0){
            longestRequestMs=Math.max(longestRequestMs,SystemClock.elapsedRealtime()-requestStart);
            requestStart=-1;
        }
    }
    private final Rect lastRegion=new Rect();
    private void cutHoles(){
        notes.sort((a,b)->a.top!=b.top?Integer.compare(a.top,b.top):Integer.compare(a.left,b.left));
        try{cutout.paint(notes);}catch(RuntimeException ignored){}
    }
    private void show(){
        Rect r=region();
        if(r.isEmpty()||cutout==null){suppress("Clock area unavailable");return;}
        if(shown&&attachedWindow==lockWindow){
            if(!lastRegion.equals(r)){
                // Clock size changed: move the crop without hiding and refading the layer.
                try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){t.setCrop(cutout.control,r).apply();lastRegion.set(r);}
                catch(RuntimeException ignored){}
            }
            return;
        }
        suppress("Preparing reveal");
        cutHoles();
        final SurfaceControl sc=cutout.control;
        // Rise from below while fading in. With notifications on screen, only fade so the cut-outs stay on the cards.
        final float rise=notes.isEmpty()?cutout.height*SLIDE:0f;
        final int currentWake=wakeNumber;
        try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
            t.setCrop(sc,r).setPosition(sc,0,rise).setAlpha(sc,0f).setVisibility(sc,false).apply();
            attachAccessibilityOverlayToWindow(lockWindow,sc);
            t.setLayer(sc,Integer.MAX_VALUE).setAlpha(sc,0f).setVisibility(sc,true)
                .addTransactionCommittedListener(main::post,()->{
                    if(inWake && wakeNumber==currentWake && firstCommitMs<0)
                        firstCommitMs=SystemClock.elapsedRealtime()-wakeStart;
                }).apply();
        }catch(RuntimeException e){detach();suppress("Layer attach failed: "+e.getClass().getSimpleName());return;}
        confirmedAt=0;requestStart=SystemClock.elapsedRealtime();attachedWindow=lockWindow;shown=true;lastRegion.set(r);appeared=true;
        lastDecision="Normal lock confirmed";status="Clock layer active";
        main.removeCallbacks(watchdog);main.postDelayed(watchdog,16);
        // Fade in from fully transparent while sliding up. Hiding stays instant so the building never lingers over PIN entry.
        reveal=ValueAnimator.ofFloat(0f,1f);reveal.setDuration(FADE_IN);reveal.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        reveal.addUpdateListener(a->{
            if(!shown||cutout==null||cutout.control!=sc)return;
            float f=(float)a.getAnimatedValue();
            try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){t.setAlpha(sc,f).setPosition(sc,0,rise*(1-f)).apply();}
        });reveal.start();
    }
    private void detach(){
        if(cutout==null)return;
        try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
            t.setVisibility(cutout.control,false).reparent(cutout.control,null).apply();
        }catch(RuntimeException ignored){}
        attachedWindow=-1;
    }
    private void suppress(String why){
        main.removeCallbacks(expire);main.removeCallbacks(watchdog);
        if(inWake && !"Display sleeping".equals(why) && !"Window changed".equals(why) &&
           !"Screen off".equals(why) && !"User present".equals(why) && !"Unlocked".equals(why))lastDecision=why;
        status=why;
        if(reveal!=null){reveal.cancel();reveal=null;}
        if(shown){recordInterval();detach();}
        shown=false;
    }
    @Override public void onInterrupt(){invalidateProof("Accessibility interrupted");}
    @Override public void onDestroy(){
        if(touchSpy!=null){try{windows.removeView(touchSpy);}catch(RuntimeException ignored){}touchSpy=null;}
        dead=true;connected=false;invalidateProof("Clock layer off");main.removeCallbacksAndMessages(null);worker.shutdownNow();
        endWake("Service stopped");if(cutout!=null){cutout.close();cutout=null;}
        if(prefs!=null){getSystemService(DisplayManager.class).unregisterDisplayListener(displayChanges);prefs.unregisterOnSharedPreferenceChangeListener(this);unregisterReceiver(receiver);}
        Shizuku.removeBinderReceivedListener(received);Shizuku.removeBinderDeadListener(lost);
        if(bound)try{Shizuku.unbindUserService(args,connection,true);}catch(RuntimeException ignored){}
        super.onDestroy();
    }
}
