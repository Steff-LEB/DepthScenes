package com.ali.depthscenes;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class WindowProofTest {
    static void expect(boolean expected,String sample,String name) throws IOException {
        boolean result=WindowProof.normal(new BufferedReader(new StringReader(sample)));
        if(result!=expected)throw new AssertionError(name);
        System.out.println(name+" passed");
    }
    public static void main(String[] args) throws IOException {
        String normal=new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8);
        String pin=new String(Files.readAllBytes(Paths.get(args[1])),StandardCharsets.UTF_8);
        expect(true,normal,"Captured normal lock");
        expect(false,pin,"Captured PIN keypad");
        expect(false,normal.replace("ALT_FOCUSABLE_IM WATCH_OUTSIDE_TOUCH","SECURE ALT_FOCUSABLE_IM WATCH_OUTSIDE_TOUCH"),"Secure shade while fingerprint lingers");
        expect(false,normal.replace("UdfpsControllerOverlay","UnknownWindow"),"Fingerprint window unavailable");
        expect(false,normal.replace("mTopFocusedDisplayId=0","mTopFocusedDisplayId=1"),"Other display");
        expect(false,normal.substring(0,normal.indexOf("mTopFocusedDisplayId")),"Truncated snapshot");
        expect(false,normal.replace("package=com.android.systemui ","package=another.app "),"Unexpected window owner");
        expect(false,"","No data");
    }
}
