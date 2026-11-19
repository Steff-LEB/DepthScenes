package com.ali.depthscenes;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Only current values from the two explicitly requested System UI dumps. */
final class SceneProof {
    static final String NORMAL="Normal lock";
    static final String AUTHENTICATED_CLOCK="Authenticated clock awaiting swipe";
    static boolean isClock(String state){
        return NORMAL.equals(state)||AUTHENTICATED_CLOCK.equals(state);
    }
    static boolean matchesCredential(String state,boolean deviceLocked){
        return deviceLocked?NORMAL.equals(state):AUTHENTICATED_CLOCK.equals(state);
    }
    static final String UNAVAILABLE="Display-0 System UI state unavailable";
    private static final long PANEL=1L<<2, KEYGUARD=1L<<6, AWAKE=1L<<28;
    private static final int[] BLOCK_BITS={3,9,11,18,21,27,29,31,35,0,14,15,23,25};
    private static final String[] BLOCK_NAMES={"bouncer_visible","keygrd_occluded","qs_visible","ime_visible","device_dozing","device_dreaming","wakefulness_transition","keygrd_going_away","communal_hub_showing","screen_pinned","bubbles_expanded","dialog_showing","bubbles_mange_menu_expanded","vis_win_showing"};
    private static final String[] BLOCK_REASONS={"PIN or password entry","Clock covered by another screen","Quick settings open","Keyboard visible","Always-on display","Dream screen","Display waking or sleeping","Unlock in progress","Hub visible","Screen pinned","Bubble expanded","System dialog","Bubble menu open","Voice interaction"};

    private static final class Container {
        String section="";
        boolean present,duplicate;
        final Map<String,String> fields=new HashMap<>();
        void accept(String line){
            if(line.equals("SceneContainerFlag:")){
                if(present)duplicate=true;
                present=true;section="";return;
            }
            if(!present)return;
            if(line.endsWith(":")){section=line;return;}
            int split=line.indexOf('=');
            if(split<1)return;
            String key=line.substring(0,split);
            boolean wanted;
            switch(section){
                case "Framework availability:": wanted=key.equals("isEnabled");break;
                case "Framework state:": wanted=key.equals("isVisible")||key.equals("currentScene")||key.equals("currentOverlays");break;
                case "Authentication state:": wanted=key.equals("isUnlocked")||key.equals("isKeyguardEnabled")||key.equals("isDeviceEntered");break;
                case "Power state:": wanted=key.equals("isDozing")||key.equals("isInteractive");break;
                default:return;
            }
            if(wanted && fields.put(key,line.substring(split+1).trim())!=null)duplicate=true;
        }
        String decision(){
            if(!present)return UNAVAILABLE;
            if(duplicate)return "Ambiguous scene details";
            if("false".equals(fields.get("isEnabled")))return UNAVAILABLE;
            if(fields.size()!=9 || !"true".equals(fields.get("isEnabled")))return "Incomplete scene details";
            String name=fields.get("currentScene");
            if(!"Lockscreen".equalsIgnoreCase(name))return "Other scene: "+knownScene(name);
            String overlays=fields.get("currentOverlays");
            if(!overlays.isEmpty()){
                if(overlays.toLowerCase(java.util.Locale.ROOT).contains("bouncer"))return "PIN or password overlay";
                return "Overlay on clock: "+knownScene(overlays);
            }
            String visible=fields.get("isVisible");
            if(!(visible.equals("true")||visible.startsWith("true (reason=")))return "Clock scene hidden";
            if(!"true".equals(fields.get("isKeyguardEnabled"))||!"false".equals(fields.get("isDeviceEntered")))
                return "Lock screen dismissed or disabled";
            if(!"false".equals(fields.get("isDozing"))||!"true".equals(fields.get("isInteractive")))return "Display inactive";
            String unlocked=fields.get("isUnlocked");
            if("true".equals(unlocked))return AUTHENTICATED_CLOCK;
            if("false".equals(unlocked))return NORMAL;
            return "Unknown authentication state";
        }
        private String knownScene(String value){
            return value!=null && value.matches("[A-Za-z][A-Za-z ,]{0,60}")?value:"unrecognized";
        }
    }
    static String read(BufferedReader in) throws IOException {
        Container container=new Container();
        long flags=0;
        String names=null, line;
        while((line=in.readLine())!=null){
            line=line.trim();
            if(!line.equals("SysUiState state:")){container.accept(line);continue;}
            if(names!=null)return "Ambiguous System UI state";
            String number=in.readLine(), labels=in.readLine(), tail=in.readLine();
            if(number==null||labels==null||tail==null||!number.trim().startsWith("mSysUiStateFlags=")||
               !tail.trim().matches("backGestureDisabled=(true|false)"))return "Incomplete System UI state";
            try{flags=Long.parseLong(number.trim().substring("mSysUiStateFlags=".length()));}
            catch(NumberFormatException e){return "Unreadable System UI flags";}
            names="|"+labels.trim()+"|";
        }
        String scene=container.decision();
        if(names==null)return isClock(scene)? "Display-0 flags missing" : scene;
        for(int i=0;i<BLOCK_BITS.length;i++){
            if((flags&(1L<<BLOCK_BITS[i]))!=0 || names.contains("|"+BLOCK_NAMES[i]+"|"))return BLOCK_REASONS[i];
        }
        if((flags&(KEYGUARD|AWAKE))!=(KEYGUARD|AWAKE))return "System UI clock screen inactive";
        if(!names.contains("|keygrd_visible|")||!names.contains("|awake|"))return "Inconsistent System UI state";
        if(!scene.equals(UNAVAILABLE))return scene;
        // The expanded flag also describes the bare lock screen. A fresh explicit
        // Lockscreen scene with no overlays must have been confirmed to allow it.
        if((flags&PANEL)!=0 || names.contains("|notif_expanded|"))return "Expanded panel; scene details unavailable";
        return NORMAL;
    }
    private SceneProof(){}
}
