package wx.module;

import android.content.Context;
import android.content.SharedPreferences;

public final class ConfigStore {
    public static final String PREF = "wx_antirevoke";
    private static final String KEY_ANTI_REVOKE = "antiRevoke";

    public static final class Config {
        /** When true, WeChat's message table keeps a row that a recall would replace. */
        public final boolean antiRevoke;

        public Config(boolean antiRevoke) {
            this.antiRevoke = antiRevoke;
        }
    }

    private ConfigStore() {}

    public static Config get(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return new Config(prefs.getBoolean(KEY_ANTI_REVOKE, true));
    }

    public static void putAntiRevoke(Context context, boolean enabled) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ANTI_REVOKE, enabled)
                .commit();
    }
}
