package com.ali.depthscenes;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class SceneProofTest {
    private static final long ordinary=(1L<<28)|(1L<<6)|(1L<<30);
    private static String sample(long value,String labels){
        return "SysUiState state:\n  mSysUiStateFlags="+value+"\n    "+labels+"\n    backGestureDisabled=false\n";
    }
    private static BufferedReader reader(String input){return new BufferedReader(new StringReader(input));}
    private static void check(String name,boolean passed){
        if(!passed)throw new AssertionError(name);
        System.out.println(name+" passed");
    }
    public static void main(String[] args) throws IOException {
        String normal=new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8);
        String pin=new String(Files.readAllBytes(Paths.get(args[1])),StandardCharsets.UTF_8);
        String live=sample(ordinary,"keygrd_visible|awake|notif_visible");
        String state=SceneProof.read(reader(live));
        check("Named awake keyguard",state.equals(SceneProof.NORMAL));
        String detail="SceneContainerFlag:\n  Framework availability:\n    isEnabled=true\n    isEnabledOnVariant=true\n"
            +"  Framework state:\n    isVisible=true (reason=\"on lock\")\n    currentScene=lockscreen\n    currentOverlays=\n"
            +"  Authentication state:\n    isKeyguardEnabled=true\n    isUnlocked=false\n    isDeviceEntered=false\n"
            +"  Power state:\n    isDozing=false\n    isInteractive=true\n";
        String expanded=sample(ordinary|4,"keygrd_visible|awake|notif_expanded|notif_visible");
        String resolved=SceneProof.read(reader(expanded+detail));
        check("Bare locked clock with expanded-panel flag",SceneProof.NORMAL.equals(resolved));
        check("Bare locked clock with reversed dump order",SceneProof.NORMAL.equals(SceneProof.read(reader(detail+expanded))));
        check("Recorded normal window with expanded clock",SceneProof.NORMAL.equals(WindowProof.capture(reader(normal)).decision(resolved)));
        check("Recorded PIN window with expanded clock",!SceneProof.isClock(WindowProof.capture(reader(pin)).decision(resolved)));
        String authenticated=detail.replace("isUnlocked=false","isUnlocked=true");
        String afterAuth=SceneProof.read(reader(expanded+authenticated));
        check("Open padlock on visible clock",SceneProof.AUTHENTICATED_CLOCK.equals(afterAuth));
        check("Current credential must match the sampled clock",
            SceneProof.matchesCredential(afterAuth,false) && !SceneProof.matchesCredential(afterAuth,true) &&
            SceneProof.matchesCredential(resolved,true) && !SceneProof.matchesCredential(resolved,false));
        check("Missing display flags block authenticated clock",!SceneProof.isClock(SceneProof.read(reader(authenticated))));
        String noFinger=normal.replace("UdfpsControllerOverlay","UnknownWindow");
        check("Authenticated clock after fingerprint window retired",afterAuth.equals(WindowProof.capture(reader(noFinger)).decision(afterAuth)));
        check("Retired fingerprint with ambiguous scene",!SceneProof.isClock(WindowProof.capture(reader(noFinger)).decision(SceneProof.UNAVAILABLE)));
        check("Ordinary lock still requires fingerprint window",!SceneProof.isClock(WindowProof.capture(reader(noFinger)).decision(resolved)));
        check("Recorded secure PIN wins over stale authenticated clock",!SceneProof.isClock(WindowProof.capture(reader(pin)).decision(afterAuth)));
        for(String change:new String[]{"bouncer","notificationsShade","quickSettingsShade"}){
            check("Authenticated clock with "+change,!SceneProof.isClock(SceneProof.read(reader(expanded+authenticated.replace("currentOverlays=","currentOverlays="+change)))));
        }
        for(String[] change:new String[][]{{"isDeviceEntered=false","isDeviceEntered=true"},{"currentScene=lockscreen","currentScene=gone"},
                {"isVisible=true","isVisible=false"},{"isInteractive=true","isInteractive=false"},{"isDozing=false","isDozing=true"},
                {"isKeyguardEnabled=true","isKeyguardEnabled=false"},{"currentOverlays=","missing="}}){
            check("Authenticated scene blocks "+change[1],!SceneProof.isClock(SceneProof.read(reader(expanded+authenticated.replace(change[0],change[1])))));
        }
        for(int bit:new int[]{3,9,11,18,21,27,29,31}){
            check("Authenticated scene blocks transition or overlay "+bit,!SceneProof.isClock(SceneProof.read(reader(sample(ordinary|4|(1L<<bit),"keygrd_visible|awake")+authenticated))));
        }
        for(String[] change:new String[][]{
            {"currentScene=lockscreen","currentScene=shade"},{"currentOverlays=","currentOverlays=notificationsShade"},
            {"currentOverlays=","currentOverlays=bouncer"},{"currentOverlays=","currentOverlays=quickSettingsShade"},
            {"isUnlocked=false","isUnlocked=unknown"},{"isDeviceEntered=false","isDeviceEntered=true"},
            {"isInteractive=true","isInteractive=false"},{"isDozing=false","isDozing=true"},
            {"isVisible=true","isVisible=false"},{"isKeyguardEnabled=true","isKeyguardEnabled=false"},
            {"isEnabled=true","isEnabled=false"},{"isInteractive=true",""},{"currentScene=lockscreen","currentScene=shade\ncurrentScene=lockscreen"}
        }){
            check("Suppress on changed "+change[0]+" / "+change[1],!SceneProof.isClock(SceneProof.read(reader(expanded+detail.replace(change[0],change[1])))));
        }
        check("Reject expanded flag without scene details",!SceneProof.isClock(SceneProof.read(reader(expanded))));
        check("Bouncer flag overrides normal container",!SceneProof.isClock(SceneProof.read(reader(sample(ordinary|4|8,"keygrd_visible|awake")+detail))));
        for(int bit:new int[]{2,3,9,11,15,18,21,27,29,31,35}){
            check("Reject overlay/transition bit "+bit,!SceneProof.isClock(SceneProof.read(reader(sample(ordinary|(1L<<bit),"keygrd_visible|awake")))));
        }
        check("Contradictory PIN name",!SceneProof.isClock(SceneProof.read(reader(sample(ordinary,"keygrd_visible|awake|bouncer_visible")))));
        check("Duplicate state",!SceneProof.isClock(SceneProof.read(reader(live+live))));
        check("Truncated state",!SceneProof.isClock(SceneProof.read(reader(live.substring(0,live.indexOf("backGesture"))))));
        String changed=normal.replace("ALT_FOCUSABLE_IM WATCH_OUTSIDE_TOUCH","SHOW_WALLPAPER WATCH_OUTSIDE_TOUCH").replace(" HIDE_NON_SYSTEM_OVERLAY_WINDOWS "," ");
        WindowProof.Snapshot varied=WindowProof.capture(reader(changed));
        check("Ordinary lock with changed wallpaper and focus flags",SceneProof.NORMAL.equals(varied.decision(state)));
        check("Missing scene with changed flags",!SceneProof.isClock(varied.decision(SceneProof.UNAVAILABLE)));
        check("Recorded PIN beats stale normal scene",!SceneProof.isClock(WindowProof.capture(reader(pin)).decision(state)));
        check("Explicit PIN beats normal window",!SceneProof.isClock(WindowProof.capture(reader(normal)).decision("PIN or password entry")));
        check("Hex secure bit blocks scene",!SceneProof.isClock(WindowProof.capture(reader(changed.replace("SHOW_WALLPAPER WATCH_OUTSIDE_TOUCH","0x00002000 | SHOW_WALLPAPER WATCH_OUTSIDE_TOUCH"))).decision(state)));
        check("Mixed named and vendor bits",SceneProof.NORMAL.equals(WindowProof.capture(reader(changed.replace("SHOW_WALLPAPER WATCH_OUTSIDE_TOUCH","0x00000002 | SHOW_WALLPAPER WATCH_OUTSIDE_TOUCH"))).decision(state)));
        check("Secure shade beats normal scene",!SceneProof.isClock(WindowProof.capture(reader(changed.replace("SHOW_WALLPAPER WATCH_OUTSIDE_TOUCH","SECURE SHOW_WALLPAPER WATCH_OUTSIDE_TOUCH"))).decision(state)));
    }
}
