package com.ali.depthscenes;

import java.io.*;
import java.util.regex.Pattern;

/** Reads only the two named System UI window records. Unknown or partial output denies the effect. */
final class WindowProof {
    private static final Pattern HEADER = Pattern.compile("Window #[0-9]+ Window\\{[^}]+\\}:");
    static final class Record {
        boolean owner, display, surface, onScreen, visible, shown, expectedType, flags, privateFlags, flagsPresent, secure;
        String visibilityProblem(String name) {
            if(!owner || !display || !expectedType)return name+" identity not confirmed";
            if(!flagsPresent)return name+" flags unavailable";
            if(secure)return name+" marked secure";
            if(!surface || !onScreen || !visible || !shown)return name+" not visibly drawn";
            return null;
        }
        void accept(String line, boolean shade) {
            if (line.startsWith("mOwnerUid=")) owner=line.contains(" package=com.android.systemui ");
            if (line.startsWith("mDisplayId=")) display=line.startsWith("mDisplayId=0 ");
            if (line.startsWith("mAttrs=")) expectedType=line.contains(shade?" ty=NOTIFICATION_SHADE ":" ty=NAVIGATION_BAR_PANEL ");
            if (line.startsWith("mViewVisibility=")) shown=line.startsWith("mViewVisibility=0x0 ") && line.contains("mObscured=false");
            if (line.startsWith("mHasSurface=")) surface=line.startsWith("mHasSurface=true isReadyForDisplay()=true");
            if (line.startsWith("isOnScreen=")) onScreen=line.equals("isOnScreen=true");
            if (line.startsWith("isVisible=")) visible=line.equals("isVisible=true");
            if (line.startsWith("fl=")) {
                String value=line.substring(3).trim();
                flagsPresent=!value.isEmpty();
                boolean alt=false,wallpaper=false;
                for(String token:value.split("[\\s|]+")) {
                    if(token.equals("SECURE"))secure=true;
                    else if(token.equals("ALT_FOCUSABLE_IM"))alt=true;
                    else if(token.equals("SHOW_WALLPAPER"))wallpaper=true;
                    else if(token.matches("0[xX][0-9a-fA-F]{1,8}")) {
                        long bits=Long.parseLong(token.substring(2),16);
                        secure|=(bits&0x2000L)!=0;
                        alt|=(bits&0x20000L)!=0;
                        wallpaper|=(bits&0x100000L)!=0;
                    }else if(!token.matches("[A-Z][A-Z_0-9]*"))flagsPresent=false;
                }
                flags=!secure && (!shade || (alt && !wallpaper));
            }
            if (line.startsWith("pfl=")) privateFlags=(" "+line.substring(4)+" ").contains(shade?" HIDE_NON_SYSTEM_OVERLAY_WINDOWS ":" TRUSTED_OVERLAY ");
        }
    }
    static Snapshot capture(BufferedReader reader) throws IOException {
        Snapshot data=new Snapshot(); Record current=null;
        for (String raw; (raw=reader.readLine())!=null;) {
            String line=raw.trim();
            if (line.startsWith("Window #")) {
                current=null;
                if (!HEADER.matcher(line).matches()) continue;
                if (line.endsWith(" u0 NotificationShade}:")) {
                    if(data.shade!=null){data.error="Ambiguous shade window";return data;}
                    current=data.shade=new Record();
                } else if (line.endsWith(" u0 UdfpsControllerOverlay}:")) {
                    if(data.finger!=null){data.error="Ambiguous fingerprint window";return data;}
                    current=data.finger=new Record();
                }
            } else if (line.startsWith("mTopFocusedDisplayId=")) {
                data.end=line.equals("mTopFocusedDisplayId=0"); break;
            } else if(line.startsWith("#") && line.contains(" Window{") || line.endsWith("mPendingRemove:")) {
                current=null;
            } else if (current!=null) current.accept(line,current==data.shade);
        }
        return data;
    }
    static final class Snapshot {
        Record shade, finger;
        String error;
        boolean end;
        String decision(String scene){
            if(!SceneProof.isClock(scene) && !SceneProof.UNAVAILABLE.equals(scene))return scene;
            if(error!=null)return error;
            if(!end)return "Incomplete window sample or other display";
            if(shade==null)return "Shade window missing";
            String veto=shade.visibilityProblem("Shade");
            if(veto!=null)return veto;
            // After authentication Pixel can remove UDFPS while leaving the clock
            // visible for swipe. Only an explicit unentered, overlay-free scene
            // qualifies for this path. The actual shade must still be nonsecure.
            if(SceneProof.AUTHENTICATED_CLOCK.equals(scene))return scene;
            if(finger==null)return "Fingerprint window missing";
            veto=finger.visibilityProblem("Fingerprint");
            if(veto!=null)return veto;
            if(!finger.privateFlags)return "Fingerprint window trust unconfirmed";
            if(SceneProof.NORMAL.equals(scene))return SceneProof.NORMAL;
            // Compatibility with releases without the named SysUiState dump. All
            // original conditions still apply when there is no independent scene proof.
            if(!shade.flags || !shade.privateFlags || !finger.flags)
                return "System UI state unavailable; legacy shade flags differ";
            return SceneProof.NORMAL;
        }
    }
    static boolean normal(BufferedReader reader) throws IOException {
        return SceneProof.NORMAL.equals(capture(reader).decision(SceneProof.UNAVAILABLE));
    }
}
