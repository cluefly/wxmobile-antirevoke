package wx.module;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

public final class ConfigProvider extends ContentProvider {
    public static final String AUTH = "wx.module.config";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (getContext() == null || method == null) {
            return null;
        }
        if ("getConfig".equals(method)) {
            if (!canRead()) {
                return null;
            }
            ConfigStore.Config config = ConfigStore.get(getContext());
            Bundle out = new Bundle();
            out.putBoolean("antiRevoke", config.antiRevoke);
            return out;
        }
        if ("setConfig".equals(method)) {
            if (!canWrite()) {
                return null;
            }
            if (extras != null && extras.containsKey("antiRevoke")) {
                boolean antiRevoke = extras.getBoolean("antiRevoke", false);
                ConfigStore.putAntiRevoke(getContext(), antiRevoke);
            }
            Bundle out = new Bundle();
            out.putBoolean("antiRevoke", ConfigStore.get(getContext()).antiRevoke);
            return out;
        }
        return null;
    }

    /** Hooked packages may read. Other apps may not. */
    private boolean canRead() {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid() || uid == 0 || uid == 2000
                || uid == android.os.Process.SYSTEM_UID) {
            return true;
        }
        return uidHasPackage(uid, "com.tencent.mm");
    }

    /** The hook reads config as whatever app it is injected into. Writes stay local. */
    private boolean canWrite() {
        int uid = Binder.getCallingUid();
        return uid == android.os.Process.myUid() || uid == 0 || uid == 2000;
    }

    private boolean uidHasPackage(int uid, String... names) {
        if (getContext() == null) {
            return false;
        }
        String[] pkgs = getContext().getPackageManager().getPackagesForUid(uid);
        if (pkgs == null) {
            return false;
        }
        for (int i = 0; i < pkgs.length; i++) {
            for (int j = 0; j < names.length; j++) {
                if (names[j].equals(pkgs[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
