package com.extbridge.helper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

public class BridgeProvider extends ContentProvider {
    private static final Binder sProcToken = new Binder();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("getBinder".equals(method)) {
            int uid = Binder.getCallingUid();
            if (uid != 1000 && uid != 0 && uid != 2000 && uid != android.os.Process.myUid())
                throw new SecurityException("Untrusted window controller");
            Bundle result = new Bundle();
            result.putBinder("cb", BridgeClient.getService());
            return result;
        }
        if ("setBinder".equals(method) && extras != null) {
            int uid = Binder.getCallingUid();
            if (uid != 0 && uid != 1000 && uid != 2000 && uid != android.os.Process.myUid()) {
                throw new SecurityException("Untrusted bridge publisher");
            }
            IBinder b = extras.getBinder("cb");
            if (b != null) {
                BridgeClient.setBinder(b);
                BridgeService.ensureStarted(getContext());
                BridgeService.refreshFromReceiver(getContext());
                Bundle ok = new Bundle();
                ok.putBoolean("ok", true);
                ok.putBinder("proc", sProcToken);
                return ok;
            }
        }
        return new Bundle();
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] sa, String so) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] sa) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] sa) { return 0; }
}
