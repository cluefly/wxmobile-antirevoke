package wx.module.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public final class HookEntry implements IXposedHookLoadPackage {
    static final String WECHAT = "com.tencent.mm";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!WECHAT.equals(lpparam.packageName)) {
            return;
        }
        try {
            RevokeGuard.install(lpparam.classLoader);
            XposedBridge.log("WX: anti-revoke hooked " + lpparam.packageName);
        } catch (Throwable error) {
            XposedBridge.log("WX: anti-revoke hook failed for " + lpparam.packageName);
            XposedBridge.log(error);
        }
    }
}
