package com.ali.depthscenes;

import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Two fixed read-only queries. Only a decision and its sampling time cross Binder. */
public final class WindowProbe extends Binder {
    static final String DESCRIPTOR="com.ali.depthscenes.WindowProbe";
    static final int CHECK=IBinder.FIRST_CALL_TRANSACTION;
    private final ExecutorService sceneWorker=Executors.newSingleThreadExecutor();
    public WindowProbe(){attachInterface(null,DESCRIPTOR);}

    private String sceneState() throws IOException {
        java.lang.Process query=new ProcessBuilder("/system/bin/dumpsys","-t","1","activity","service",
            "com.android.systemui/.SystemUIService","SysUiStateImpl-ForDisplay=0","SceneContainerStartable").redirectErrorStream(true).start();
        try(BufferedReader in=output(query)) {
            String state=SceneProof.read(in);
            if(!query.waitFor(150,TimeUnit.MILLISECONDS) || query.exitValue()!=0)return "System UI query failed";
            return state;
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();return "System UI query interrupted";}
        finally{query.destroyForcibly();}
    }
    private static BufferedReader output(java.lang.Process process) {
        return new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
    }
    @Override protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if(code==16777115){android.os.Process.killProcess(android.os.Process.myPid());return true;}
        if(code!=CHECK)return super.onTransact(code,data,reply,flags);
        data.enforceInterface(DESCRIPTOR);
        long start=SystemClock.elapsedRealtime();
        Future<String> scene=sceneWorker.submit(this::sceneState);
        java.lang.Process windows=null;
        String reason;
        try {
            windows=new ProcessBuilder("/system/bin/dumpsys","-t","1","window","windows").redirectErrorStream(true).start();
            // Read the window records while System UI produces its small display-0 dump.
            // The two timestamps share the conservative pre-query origin.
            try(BufferedReader in=output(windows)){
                WindowProof.Snapshot snapshot=WindowProof.capture(in);
                String state=scene.get(1,TimeUnit.SECONDS);
                reason=snapshot.decision(state);
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();reason="Screen checks interrupted";}
        catch(ExecutionException|TimeoutException|IOException e){reason="Screen checks unavailable";}
        finally{if(windows!=null)windows.destroyForcibly();scene.cancel(true);}
        reply.writeNoException();reply.writeInt(SceneProof.isClock(reason)?1:0);
        reply.writeLong(start);reply.writeString(reason);
        return true;
    }
}
