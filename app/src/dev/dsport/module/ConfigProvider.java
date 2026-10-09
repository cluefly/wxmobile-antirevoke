package dev.dsport.module;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

public final class ConfigProvider extends ContentProvider {
    public static final String AUTH = "dev.dsport.module.config";

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
            out.putBoolean("enabled", config.enabled);
            out.putLong("offset", config.goal);
            out.putLong("credited", config.credited);
            out.putLong("base", config.base);
            out.putLong("addition", config.addition);
            out.putLong("perMinute", config.perMinute);
            out.putLong("perFive", config.perFive);
            out.putLong("stepCap", config.stepCap);
            out.putBoolean("antiRevoke", config.antiRevoke);
            return out;
        }
        if ("credit".equals(method)) {
            if (!canCredit()) {
                return null;
            }
            long requested = extras == null ? 0L : extras.getLong("credited", 0L);
            if (requested < 0L) {
                return null;
            }
            long credited = ConfigStore.creditAtLeast(getContext(), requested);
            Bundle out = new Bundle();
            out.putLong("credited", credited);
            return out;
        }
        if ("setConfig".equals(method)) {
            if (!canWrite()) {
                return null;
            }
            ConfigStore.Config current = ConfigStore.get(getContext());
            boolean enabled = extras != null && extras.getBoolean("enabled", false);
            long offset = extras == null ? 0L : extras.getLong("offset", 0L);
            if (offset < 0L) {
                return null;
            }
            long perMinute = current.perMinute;
            long perFive = current.perFive;
            if (extras != null && extras.containsKey("perMinute")) {
                perMinute = extras.getLong("perMinute", perMinute);
            }
            if (extras != null && extras.containsKey("perFive")) {
                perFive = extras.getLong("perFive", perFive);
            }
            long stepCap = current.stepCap;
            if (extras != null && extras.containsKey("stepCap")) {
                stepCap = extras.getLong("stepCap", stepCap);
            }
            if (enabled) {
                long stored = ConfigStore.putAbsolute(getContext(), offset, perMinute, perFive, stepCap);
                if (stored < 0L) {
                    return null;
                }
                offset = stored;
            } else {
                ConfigStore.Config fresh = ConfigStore.get(getContext());
                StepMath.AdditionGoal addition = StepMath.additionGoal(
                        fresh.credited, fresh.base, fresh.goal, fresh.enabled, offset);
                ConfigStore.putAddition(getContext(), addition.base, addition.goal - addition.base,
                        addition.goal, perMinute, perFive, stepCap);
            }
            Bundle out = new Bundle();
            out.putBoolean("enabled", enabled);
            out.putLong("offset", offset);
            out.putLong("perMinute", perMinute);
            out.putLong("perFive", perFive);
            return out;
        }
        if ("report".equals(method)) {
            if (!canRead()) {
                return null;
            }
            HookStatus.update(extras);
            return new Bundle();
        }
        if ("getStatus".equals(method)) {
            if (!canRead()) {
                return null;
            }
            return HookStatus.snapshot();
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
        return uidHasPackage(uid, "com.oplus.exsystemservice", "com.oplus.healthservice",
                "com.tencent.mm");
    }

    /** The hook reads config as whatever app it is injected into. Writes stay local. */
    private boolean canWrite() {
        int uid = Binder.getCallingUid();
        return uid == android.os.Process.myUid() || uid == 0 || uid == 2000;
    }

    /** The step service runs as system uid and has to record how much was dripped. */
    private boolean canCredit() {
        int uid = Binder.getCallingUid();
        return canWrite() || uid == android.os.Process.SYSTEM_UID
                || uidHasPackage(uid, "com.oplus.exsystemservice", "com.oplus.healthservice");
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
