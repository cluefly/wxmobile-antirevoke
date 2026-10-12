package wx.module.hook;

import android.content.Context;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Keeps a WeChat 8.0.78 message that is already stored on this phone when
 * someone else recalls it. WeChat loads the live code from a Tinker class
 * loader, so the same class name can exist twice. Hooks attach again when
 * that second class is loaded.
 * Recalls sent from this phone ({@code modelsimple.d1} and the chat UI) and
 * official accounts stay on WeChat's own path. A kept message also gets one
 * red system line under it, using WeChat's own type-10000 insert.
 */
public final class RevokeGuard {
    private static final Uri CONFIG = Uri.parse("content://wx.module.config");
    private static final long CONFIG_TTL_MS = 1000L;
    private static final long HELD_MS = 10_000L;
    private static final int TYPE_REVOKE = 0x10002710;
    private static final int TYPE_REVOKE_APP = 0x10002712;
    private static final int TYPE_REVOKE_MEDIA = 0x11002712;
    private static final String RECALL = "\u64a4\u56de\u4e86\u4e00\u6761\u6d88\u606f";
    private static final int TYPE_SYSTEM = 10000;
    private static final String TIP_MARK = "\u3010\u5df2\u64a4\u56de\u3011";
    private static final int TIP_RED = 0xFFD32F2F;
    private static final int TIP_RED_NIGHT = 0xFFFF6B6B;

    private static final String[] WATCH = {
            "b41.t",
            "im.c8",
            "com.tencent.mm.storage.e9",
            "com.tencent.mm.storage.f9",
            "com.tencent.mm.sdk.event.IEvent",
            "com.tencent.mm.kiss.widget.textview.SysTextView",
            "com.tencent.mm.kiss.widget.textview.PLSysTextView"
    };
    private static final Set<Class<?>> hooked = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<String> loggedMiss = ConcurrentHashMap.newKeySet();
    private static final Map<Long, String> goodText = new ConcurrentHashMap<>();
    private static final Map<Long, Long> heldAt = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> busy = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> inserting = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> painting = new ThreadLocal<>();
    private static final Map<TextView, Integer> tipColors =
            Collections.synchronizedMap(new WeakHashMap<TextView, Integer>());
    private static final AtomicInteger notes = new AtomicInteger();
    private static final AtomicInteger tipNotes = new AtomicInteger();

    private static volatile boolean watching;
    private static volatile boolean wantOn;
    private static volatile boolean versionLogged;
    private static volatile long configReadAt;

    private RevokeGuard() {}

    static void install(ClassLoader classLoader) {
        watchLoaders();
        hookNames(classLoader);
        retryLater();
    }

    /** New Tinker dex loaders only. ClassLoader.loadClass stays unhooked. */
    private static void watchLoaders() {
        if (watching) {
            return;
        }
        watching = true;
        try {
            Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
            Constructor<?>[] ctors = base.getDeclaredConstructors();
            for (int i = 0; i < ctors.length; i++) {
                XposedBridge.hookMethod(ctors[i], new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof ClassLoader) || !tinkerLoader(param)) {
                            return;
                        }
                        hookNames((ClassLoader) param.thisObject);
                    }
                });
            }
        } catch (Throwable error) {
            XposedBridge.log("WX: anti-revoke skip loader " + error.getClass().getSimpleName());
        }
    }

    private static boolean tinkerLoader(XC_MethodHook.MethodHookParam param) {
        if (param.args != null) {
            for (int i = 0; i < param.args.length; i++) {
                if (param.args[i] instanceof String
                        && ((String) param.args[i]).toLowerCase(Locale.US).contains("tinker")) {
                    return true;
                }
            }
        }
        String text = param.thisObject.toString().toLowerCase(Locale.US);
        return text.contains("tinker");
    }

    private static void retryLater() {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(2000L);
                logVersion();
                hookNames(appLoader());
                Thread.sleep(6000L);
                hookNames(appLoader());
                Thread.sleep(7000L);
                hookNames(appLoader());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "wx-revoke");
        thread.setDaemon(true);
        thread.start();
    }

    private static void logVersion() {
        if (versionLogged) {
            return;
        }
        Context context = currentApplication();
        if (context == null) {
            return;
        }
        versionLogged = true;
        try {
            String version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            XposedBridge.log("WX: anti-revoke wechat " + version + ", symbols for 8.0.78");
        } catch (Throwable error) {
            XposedBridge.log("WX: anti-revoke wechat version unknown");
        }
    }

    private static void logMiss(String name) {
        if (loggedMiss.add(name)) {
            XposedBridge.log("WX: anti-revoke miss " + name);
        }
    }

    private static boolean watched(String name) {
        for (int i = 0; i < WATCH.length; i++) {
            if (WATCH[i].equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void hookNames(ClassLoader loader) {
        if (loader == null) {
            return;
        }
        busy.set(Boolean.TRUE);
        try {
            for (String name : WATCH) {
                try {
                    hookType(Class.forName(name, false, loader));
                } catch (Throwable error) {
                    logMiss(name);
                }
            }
        } finally {
            busy.remove();
        }
    }

    private static void hookType(Class<?> type) {
        if (type == null) {
            return;
        }
        synchronized (hooked) {
            if (!hooked.add(type)) {
                return;
            }
        }
        busy.set(Boolean.TRUE);
        try {
            ClassLoader loader = type.getClassLoader();
            String loaderName = loader == null ? "boot" : loader.getClass().getSimpleName();
            XposedBridge.log("WX: anti-revoke class " + type.getName() + " " + loaderName);
            switch (type.getName()) {
                case "b41.t":
                    hookConsumer(type);
                    break;
                case "im.c8":
                    hookSetType(type);
                    break;
                case "com.tencent.mm.storage.e9":
                    hookContent(type);
                    break;
                case "com.tencent.mm.storage.f9":
                    hookSave(type);
                    break;
                case "com.tencent.mm.sdk.event.IEvent":
                    hookEvent(type);
                    break;
                case "com.tencent.mm.kiss.widget.textview.SysTextView":
                case "com.tencent.mm.kiss.widget.textview.PLSysTextView":
                    hookSysText(type);
                    break;
                default:
                    break;
            }
        } finally {
            busy.remove();
        }
    }

    private static void hookConsumer(Class<?> type) {
        Method incoming = find(type, "c", 6, String.class, long.class);
        Method parser = find(type, "i7", 3, String.class, Map.class);
        hook(incoming, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!readWant() || officialSession(param.args)) {
                    return;
                }
                XposedBridge.log("WX: anti-revoke keep");
            }
        });
        hook(parser, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length > 0 && "revokemsg".equals(param.args[0])) {
                    XposedBridge.log("WX: anti-revoke xml");
                }
            }
        });
    }

    private static void hookContent(Class<?> type) {
        hook(find(type, "b1", 1, String.class), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || !recallText(param.args[0])) {
                    return;
                }
                remember(param.thisObject);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args == null || !recallText(param.args[0])) {
                    return;
                }
                restoreRecallText(param.thisObject);
            }
        });
        hook(find(type, "u3", 1, String.class), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!keepMessage(param.thisObject)) {
                    return;
                }
                boolean held = recentlyHeld(messageId(param.thisObject));
                if (!held && !recallText(param.args)) {
                    return;
                }
                if (selfSent() || param.args == null || param.args.length == 0) {
                    return;
                }
                param.args[0] = XposedHelpers.getObjectField(param.thisObject, "x0");
                mark(param.thisObject);
            }
        });
    }

    private static void hookSetType(Class<?> type) {
        hook(find(type, "setType", 1, int.class), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int next = ((Integer) param.args[0]).intValue();
                long id = messageId(param.thisObject);
                boolean recallFlag = revokeType(next);
                boolean plainRecall = (next == 10000 || next == 10002) && recentlyHeld(id);
                if (!recallFlag && !plainRecall) {
                    return;
                }
                if (!keepMessage(param.thisObject) || selfSent()) {
                    return;
                }
                Object current = XposedHelpers.callMethod(param.thisObject, "getType");
                if (current instanceof Integer && !revokeType(((Integer) current).intValue())) {
                    param.args[0] = current;
                }
                String saved = goodText.get(id);
                if (saved != null) {
                    XposedHelpers.setObjectField(param.thisObject, "field_content", saved);
                }
                mark(param.thisObject);
            }
        });
    }

    private static void hookSave(Class<?> type) {
        Method save = null;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if ("Qc".equals(method.getName()) && params.length == 3
                    && params[0] == long.class && params[2] == boolean.class) {
                save = method;
                break;
            }
        }
        hook(save, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object row = param.args[1];
                if (!recentlyHeld(messageId(row)) || selfSent()) {
                    return;
                }
                Object typeValue = XposedHelpers.callMethod(row, "getType");
                if (typeValue instanceof Integer && revokeType(((Integer) typeValue).intValue())) {
                    param.setResult(0);
                    mark(row);
                }
            }
        });
    }

    private static void hookEvent(Class<?> type) {
        Method publish = null;
        for (Method method : type.getDeclaredMethods()) {
            if ("e".equals(method.getName()) && method.getParameterTypes().length == 0
                    && method.getReturnType() == boolean.class) {
                publish = method;
                break;
            }
        }
        hook(publish, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject == null || !"com.tencent.mm.autogen.events.RevokeMsgEvent"
                        .equals(param.thisObject.getClass().getName())) {
                    return;
                }
                Object message = eventMessage(param.thisObject);
                long id = messageId(message);
                if (!recentlyHeld(id) || selfSent()) {
                    return;
                }
                param.setResult(Boolean.FALSE);
                try {
                    Object data = XposedHelpers.getObjectField(param.thisObject, "g");
                    String saved = goodText.get(id);
                    if (data != null && saved != null) {
                        XposedHelpers.setObjectField(data, "b", saved);
                    }
                } catch (Throwable ignored) {
                    // The publish result is already cancelled.
                }
            }
        });
    }

    /** Saves the text still on the message before a recall notice replaces it. */
    private static void remember(Object message) {
        if (!readWant() || selfSent() || sent(message) || officialTalker(message)) {
            return;
        }
        long id = messageId(message);
        if (id <= 0L) {
            return;
        }
        Object current = XposedHelpers.callMethod(message, "j");
        if (!(current instanceof String) || ((String) current).isEmpty() || recallText(current)) {
            return;
        }
        storeGoodText(id, (String) current);
    }

    private static void restoreRecallText(Object message) {
        long id = messageId(message);
        String saved = goodText.get(id);
        if (saved == null || !readWant()) {
            return;
        }
        Object current = XposedHelpers.callMethod(message, "j");
        if (!(current instanceof String) || !recallText(current) || saved.equals(current) || selfSent()) {
            return;
        }
        String notice = (String) current;
        XposedHelpers.setObjectField(message, "field_content", saved);
        mark(message);
        showRecallTip(message, notice);
    }

    /**
     * Inserts one gray system line just after the kept message. The original
     * row stays unchanged. WeChat's own {@code f9.yb} notifies the open chat.
     */
    private static void showRecallTip(Object message, String raw) {
        if (Boolean.TRUE.equals(inserting.get())) {
            return;
        }
        final String talker = talker(message);
        long id = messageId(message);
        if (talker == null || talker.isEmpty() || !claimTip(id)) {
            return;
        }
        final String text = noticeText(raw);
        final long when = createTime(message) + 1L;
        final Class<?> type = message.getClass();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                insertTip(type, talker, when, text);
            }
        });
    }

    private static void insertTip(Class<?> type, String talker, long when, String text) {
        if (talker == null || talker.isEmpty()) {
            return;
        }
        inserting.set(Boolean.TRUE);
        try {
            ClassLoader loader = type.getClassLoader();
            Class<?> kernel = Class.forName("gp0.j1", false, loader);
            Class<?> service = Class.forName("tn3.c4", false, loader);
            Object foundation = kernel.getMethod("v", Class.class).invoke(null, service);
            Object storage = foundation.getClass().getMethod("lj").invoke(foundation);
            Object tip = type.getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(tip, "u1", talker);
            XposedHelpers.callMethod(tip, "setType", Integer.valueOf(TYPE_SYSTEM));
            XposedHelpers.callMethod(tip, "e1", Long.valueOf(when));
            XposedHelpers.callMethod(tip, "t1", Integer.valueOf(4));
            XposedHelpers.callMethod(tip, "k1", Integer.valueOf(0));
            XposedHelpers.callMethod(tip, "b1", text);
            Object row = XposedHelpers.callMethod(storage, "yb", tip);
            if (tipNotes.incrementAndGet() <= 20) {
                XposedBridge.log("WX: anti-revoke tip " + row);
            }
            toast(text);
        } catch (Throwable error) {
            XposedBridge.log("WX: anti-revoke tip " + error.getClass().getSimpleName()
                    + " " + error.getMessage());
        } finally {
            inserting.remove();
        }
    }

    /** WeChat draws system tips with SysTextView and ignores TextView.setTextColor. */
    private static void hookSysText(Class<?> type) {
        XC_MethodHook textHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof TextView)) {
                    return;
                }
                TextView view = (TextView) param.thisObject;
                CharSequence text = param.args != null && param.args.length > 0
                        && param.args[0] instanceof CharSequence
                        ? (CharSequence) param.args[0] : null;
                if (ours(text)) {
                    paintTip(view);
                } else {
                    restoreTip(view);
                }
            }
        };
        XC_MethodHook colorHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(painting.get()) || !(param.thisObject instanceof TextView)) {
                    return;
                }
                TextView view = (TextView) param.thisObject;
                if (!ours(view.getText())) {
                    tipColors.remove(view);
                    return;
                }
                if (!tipColors.containsKey(view)) {
                    tipColors.put(view, Integer.valueOf(readSysColor(view)));
                }
                param.args[0] = Integer.valueOf(tipColor(view));
            }
        };
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0) {
                continue;
            }
            if ("setText".equals(method.getName()) && params[0] == CharSequence.class
                    && (params.length == 1 || params.length == 2)) {
                hook(method, textHook);
            } else if ("setTextColor".equals(method.getName()) && params.length == 1
                    && params[0] == int.class) {
                hook(method, colorHook);
            }
        }
    }

    private static void paintTip(TextView view) {
        if (!tipColors.containsKey(view)) {
            tipColors.put(view, Integer.valueOf(readSysColor(view)));
        }
        if (Boolean.TRUE.equals(painting.get())) {
            return;
        }
        painting.set(Boolean.TRUE);
        try {
            view.setTextColor(tipColor(view));
            view.requestLayout();
            view.invalidate();
        } finally {
            painting.remove();
        }
    }

    private static void restoreTip(TextView view) {
        Integer saved = tipColors.remove(view);
        if (saved == null || Boolean.TRUE.equals(painting.get())) {
            return;
        }
        painting.set(Boolean.TRUE);
        try {
            view.setTextColor(saved.intValue());
            view.requestLayout();
        } finally {
            painting.remove();
        }
    }

    private static int tipColor(TextView view) {
        int mode = view.getContext().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES ? TIP_RED_NIGHT : TIP_RED;
    }

    /** Color stored by SysTextView beside the real TextView paint. */
    private static int readSysColor(TextView view) {
        try {
            Object layout = XposedHelpers.getObjectField(view, "g");
            if (layout == null) {
                return view.getCurrentTextColor();
            }
            Object config = XposedHelpers.getObjectField(layout, "b");
            if (config == null) {
                return view.getCurrentTextColor();
            }
            return XposedHelpers.getIntField(config, "m");
        } catch (Throwable ignored) {
            return view.getCurrentTextColor();
        }
    }

    private static boolean ours(CharSequence text) {
        if (text == null || text.length() < TIP_MARK.length()) {
            return false;
        }
        for (int i = 0; i < TIP_MARK.length(); i++) {
            if (text.charAt(i) != TIP_MARK.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String noticeText(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("<") || text.length() > 120) {
            text = "\u5bf9\u65b9\u64a4\u56de\u4e86\u4e00\u6761\u6d88\u606f";
        }
        if (text.startsWith("\u3010\u5df2\u64a4\u56de\u3011")) {
            return text;
        }
        return "\u3010\u5df2\u64a4\u56de\u3011" + text;
    }

    /** One tip per message, shared by the main process and :push. */
    private static boolean claimTip(long id) {
        Context context = currentApplication();
        if (context == null || id <= 0L) {
            return false;
        }
        File dir = new File(context.getFilesDir(), "wx-revoke");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return false;
        }
        File mark = new File(dir, Long.toString(id));
        try {
            if (!mark.createNewFile()) {
                return false;
            }
        } catch (IOException error) {
            return false;
        }
        File[] files = dir.listFiles();
        if (files != null && files.length > 300) {
            long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
            for (int i = 0; i < files.length; i++) {
                if (files[i].lastModified() < cutoff) {
                    files[i].delete();
                }
            }
        }
        return true;
    }

    private static void toast(String text) {
        final Context context = currentApplication();
        if (context == null) {
            return;
        }
        Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_LONG).show();
    }

    private static String talker(Object message) {
        try {
            Object value = XposedHelpers.callMethod(message, "N0");
            if (value instanceof String) {
                return (String) value;
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private static long createTime(Object message) {
        try {
            Object value = XposedHelpers.callMethod(message, "getCreateTime");
            if (value instanceof Long && ((Long) value).longValue() > 0L) {
                return ((Long) value).longValue();
            }
        } catch (Throwable ignored) {
            return System.currentTimeMillis();
        }
        return System.currentTimeMillis();
    }

    /** Drops text that is no longer in a live recall. A full clear would lose the row being restored. */
    private static void storeGoodText(long id, String text) {
        if (goodText.size() >= 500 && !goodText.containsKey(id)) {
            long now = SystemClock.elapsedRealtime();
            Iterator<Map.Entry<Long, Long>> held = heldAt.entrySet().iterator();
            while (held.hasNext()) {
                Map.Entry<Long, Long> entry = held.next();
                if (now - entry.getValue().longValue() > HELD_MS) {
                    held.remove();
                }
            }
            goodText.entrySet().removeIf(entry -> !heldAt.containsKey(entry.getKey()));
            if (goodText.size() >= 450) {
                Long[] ids = heldAt.keySet().toArray(new Long[0]);
                Arrays.sort(ids, (left, right) -> Long.compare(
                        heldAt.getOrDefault(left, 0L), heldAt.getOrDefault(right, 0L)));
                int extra = goodText.size() - 400;
                for (int i = 0; i < ids.length && extra > 0; i++) {
                    if (ids[i].longValue() == id) {
                        continue;
                    }
                    if (goodText.remove(ids[i]) != null) {
                        extra--;
                    }
                    heldAt.remove(ids[i]);
                }
            }
        }
        goodText.put(id, text);
    }

    private static void mark(Object message) {
        long id = messageId(message);
        if (id > 0L) {
            heldAt.put(id, SystemClock.elapsedRealtime());
        }
        if (notes.incrementAndGet() <= 20) {
            XposedBridge.log("WX: anti-revoke held");
        }
    }

    private static boolean recentlyHeld(long id) {
        Long at = heldAt.get(id);
        if (at == null) {
            return false;
        }
        if (SystemClock.elapsedRealtime() - at.longValue() > HELD_MS) {
            heldAt.remove(id);
            return false;
        }
        return true;
    }

    private static boolean keepMessage(Object message) {
        return message != null && readWant() && messageId(message) > 0L
                && !sent(message) && !officialTalker(message);
    }

    private static boolean recallText(Object value) {
        return value instanceof String && ((String) value).contains(RECALL);
    }

    private static boolean revokeType(int type) {
        return type == TYPE_REVOKE || type == TYPE_REVOKE_APP || type == TYPE_REVOKE_MEDIA;
    }

    /** A recall this phone sent. An open chat on the stack is not enough. */
    private static boolean selfSent() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 0; i < stack.length; i++) {
            if (stack[i].getClassName().startsWith("com.tencent.mm.modelsimple.d1")) {
                return true;
            }
        }
        return false;
    }

    private static long messageId(Object message) {
        if (message == null) {
            return 0L;
        }
        try {
            Object value = XposedHelpers.callMethod(message, "getMsgId");
            if (value instanceof Long) {
                return ((Long) value).longValue();
            }
        } catch (Throwable ignored) {
            return 0L;
        }
        return 0L;
    }

    private static boolean sent(Object message) {
        try {
            Object value = XposedHelpers.callMethod(message, "z0");
            return value instanceof Integer && ((Integer) value).intValue() != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean officialTalker(Object message) {
        try {
            Object value = XposedHelpers.callMethod(message, "N0");
            return value instanceof String && ((String) value).startsWith("gh_");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean officialSession(Object[] args) {
        return args != null && args.length > 0 && args[0] instanceof String
                && ((String) args[0]).startsWith("gh_");
    }

    private static Object eventMessage(Object event) {
        try {
            Object data = XposedHelpers.getObjectField(event, "g");
            if (data == null) {
                return null;
            }
            return XposedHelpers.getObjectField(data, "c");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method find(Class<?> type, String name, int count, Class<?> first, Class<?> second) {
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (!name.equals(method.getName()) || params.length != count || params[0] != first) {
                continue;
            }
            if (second != null && params[1] != second) {
                continue;
            }
            return method;
        }
        logMiss(type.getName() + "." + name);
        return null;
    }

    private static Method find(Class<?> type, String name, int count, Class<?> first) {
        return find(type, name, count, first, null);
    }

    private static void hook(Method method, XC_MethodHook callback) {
        if (method == null) {
            return;
        }
        XposedBridge.hookMethod(method, callback);
    }

    private static ClassLoader appLoader() {
        Context context = currentApplication();
        return context == null ? null : context.getClassLoader();
    }

    private static boolean readWant() {
        long now = SystemClock.elapsedRealtime();
        if (configReadAt != 0L && now - configReadAt < CONFIG_TTL_MS) {
            return wantOn;
        }
        Context context = currentApplication();
        if (context == null) {
            return wantOn;
        }
        try {
            Bundle bundle = context.getContentResolver().call(CONFIG, "getConfig", null, null);
            if (bundle != null && bundle.containsKey("antiRevoke")) {
                wantOn = bundle.getBoolean("antiRevoke", false);
            }
            configReadAt = now;
        } catch (Throwable error) {
            configReadAt = now;
            XposedBridge.log("WX: anti-revoke config " + error.getClass().getSimpleName());
        }
        return wantOn;
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }
}
