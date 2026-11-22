package com.extbridge.tests;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

import com.extbridge.helper.BridgeClient;
import com.extbridge.helper.RootBootstrap;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises the real module installer and daemon scheduler, not a scheduling mock. */
final class PowerRegression {
    static void run(Instrumentation test, String mode) {
        Bundle result = new Bundle();
        try {
            if ("module".equals(mode)) {
                setModule(test, false);
                require(!RootBootstrap.isAutostartEnabled(), "module disabled");
                setModule(test, true);
                require(RootBootstrap.isAutostartEnabled(), "module enabled");
                result.putString("stream", "PASS: real Root module disable/install/enable\n");
            } else {
                BridgeClient.requestBinder(test.getTargetContext());
                long deadline = SystemClock.uptimeMillis() + 15000;
                while (BridgeClient.getService() == null && SystemClock.uptimeMillis() < deadline)
                    SystemClock.sleep(100);
                SystemClock.sleep(3000);
                State before = read();
                require(before.listener, "display callback registered");
                if ("idle".equals(mode)) {
                    require(before.ext == -1 && before.vd == -1 && !before.pending, "idle scheduler stopped");
                    SystemClock.sleep(12000);
                    State after = read();
                    require(after.ticks == before.ticks && !after.pending && after.ext == -1,
                            "no idle maintenance for 12 seconds");
                } else {
                    require(before.ext > 0 && before.vd > 0, "desktop attached");
                    SystemClock.sleep(3500);
                    require(read().ticks > before.ticks, "connected maintenance retained");
                }
                result.putString("stream", "PASS: " + mode + "; listener=" + before.listener
                        + ", ext=" + before.ext + ", vd=" + before.vd + ", ticks=" + before.ticks + "\n");
            }
            test.finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure + "\n");
            test.finish(0, result);
        }
    }

    private static void setModule(Instrumentation test, boolean enabled) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        boolean[] success = new boolean[1];
        String[] message = new String[1];
        RootBootstrap.setAutostartAsync(test.getTargetContext(), enabled, new RootBootstrap.Listener() {
            @Override public void onStep(String text) {}
            @Override public void onResult(boolean ok, String text) {
                success[0] = ok; message[0] = text; done.countDown();
            }
        });
        require(done.await(40, TimeUnit.SECONDS) && success[0], "module operation: " + message[0]);
    }

    private static State read() throws Exception {
        IBinder binder = BridgeClient.getService();
        require(binder != null, "bridge binder");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            require(binder.transact(45, data, reply, 0), "power diagnostics supported");
            State state = new State();
            state.listener = reply.readInt() == 1;
            state.ext = reply.readInt(); state.vd = reply.readInt();
            state.pending = reply.readInt() == 1; state.ticks = reply.readLong();
            return state;
        } finally { data.recycle(); reply.recycle(); }
    }

    private static final class State { boolean listener, pending; int ext, vd; long ticks; }
    private static void require(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
}
