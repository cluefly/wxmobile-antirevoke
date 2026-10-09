package dev.dsport.module.hook;

import android.content.Context;
import android.hardware.Sensor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SparseArray;

import java.util.Map;

import dev.dsport.module.StepMath;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Rewrites the step_counter cumulative value before {@code dispatchSensorEvent}
 * copies it to the listener. Step detector and pedometer_minute stay unchanged.
 */
final class SensorDispatch {
    private static final Uri CONFIG = Uri.parse("content://dev.dsport.module.config");
    private static final long CONFIG_TTL_MS = 1000L;
    private static final long REPORT_TTL_MS = 1500L;

    private static volatile long configReadAt;
    private static volatile boolean enabled;
    private static volatile long offset;
    private static volatile long credited;
    private static volatile long stepsPerMinute = StepMath.STEPS_PER_MINUTE;
    private static volatile long stepsPerFive = StepMath.STEPS_PER_FIVE_MINUTES;
    private static volatile long stepCap = StepMath.DEFAULT_STEP_CAP;
    private static final Object STATE = new Object();
    private static boolean configLogged;
    private static boolean resolveLogged;
    private static long lastCreditMinute = Long.MIN_VALUE;
    private static final int WINDOW = 8;
    private static final long[] creditMinute = new long[WINDOW];
    private static final long[] creditAmount = new long[WINDOW];
    private static int creditCount;
    private static final long[] lastReportAt = new long[3];
    private static final float[] lastRaw = new float[] {Float.NaN, Float.NaN, Float.NaN};

    private SensorDispatch() {}

    static void onEvent(String packageName, Object queue, Object[] args) {
        if (args == null || args.length < 2 || !(args[0] instanceof Integer) || !(args[1] instanceof float[])) {
            return;
        }
        float[] values = (float[]) args[1];
        if (values.length == 0) {
            return;
        }
        Sensor sensor = resolve(queue, (Integer) args[0]);
        if (sensor == null) {
            return;
        }
        String key = StepMath.keyFor(sensor.getType(), sensor.getStringType());
        if (key == null) {
            return;
        }
        refreshConfig();
        float raw0 = values[0];
        float raw1 = values.length > 1 ? values[1] : Float.NaN;
        float mod0 = raw0;
        float mod1 = raw1;
        if (StepMath.rewritesCumulative(sensor.getType(), sensor.getStringType())) {
            if (StepMath.releasesCredit(packageName)) {
                considerAdvance(raw0);
            }
            long boost = 0L;
            if (offset > 0L && credited > 0L) {
                boost = StepMath.allowedBoost(raw0, credited, stepCap);
            }
            mod0 = StepMath.applyOffset(raw0, boost, boost != 0L);
            values[0] = mod0;
            if (mod0 != raw0 && !"1".equals(System.getProperty("dsport.logged"))) {
                System.setProperty("dsport.logged", "1");
                XposedBridge.log("DSport: " + packageName + " step_counter " + raw0 + " -> " + mod0
                        + " credited " + credited + "/" + offset);
            }
        }
        report(packageName, key, raw0, mod0, raw1, mod1);
    }

    private static Sensor resolve(Object queue, int handle) {
        try {
            Object manager = XposedHelpers.getObjectField(queue, "mManager");
            Object map = XposedHelpers.getObjectField(manager, "mHandleToSensor");
            Object sensor = null;
            if (map instanceof Map) {
                sensor = ((Map<?, ?>) map).get(handle);
            } else if (map instanceof SparseArray) {
                sensor = ((SparseArray<?>) map).get(handle);
            }
            if (sensor instanceof Sensor) {
                return (Sensor) sensor;
            }
        } catch (Throwable error) {
            if (!resolveLogged) {
                resolveLogged = true;
                XposedBridge.log(error);
            }
        }
        return null;
    }

    private static void refreshConfig() {
        long now = SystemClock.elapsedRealtime();
        if (configReadAt != 0L && now - configReadAt < CONFIG_TTL_MS) {
            return;
        }
        Context context = currentApplication();
        if (context == null) {
            return;
        }
        try {
            Bundle bundle = context.getContentResolver().call(CONFIG, "getConfig", null, null);
            if (bundle == null) {
                configReadAt = now;
                return;
            }
            long remoteOffset = bundle.getLong("offset", 0L);
            if (remoteOffset < 0L) {
                remoteOffset = 0L;
            }
            long remoteCredited = bundle.getLong("credited", 0L);
            if (remoteCredited < 0L) {
                remoteCredited = 0L;
            }
            long minuteCap = bundle.getLong("perMinute", StepMath.STEPS_PER_MINUTE);
            long fiveCap = bundle.getLong("perFive", StepMath.STEPS_PER_FIVE_MINUTES);
            long cap = bundle.getLong("stepCap", StepMath.DEFAULT_STEP_CAP);
            synchronized (STATE) {
                enabled = bundle.getBoolean("enabled", false);
                offset = remoteOffset;
                if (remoteCredited > credited) {
                    credited = remoteCredited;
                }
                if (credited < 0L) {
                    credited = 0L;
                }
                if (minuteCap > 0L) {
                    stepsPerMinute = minuteCap;
                }
                if (fiveCap > 1L) {
                    stepsPerFive = fiveCap;
                }
                if (cap > 0L) {
                    stepCap = cap;
                }
            }
            configReadAt = now;
            configLogged = false;
        } catch (Throwable error) {
            configReadAt = now;
            if (!configLogged) {
                configLogged = true;
                XposedBridge.log(error);
            }
        }
    }

    /**
     * Releases the saved offset onto step_counter once per wall-clock minute.
     * A WeChat-like reader accepts at most {@link StepMath#STEPS_PER_MINUTE} per minute
     * and drops a jump that reaches {@link StepMath#STEPS_PER_FIVE_MINUTES} in five minutes.
     */
    private static void considerAdvance(float raw) {
        synchronized (STATE) {
            if (offset <= 0L || credited < 0L || credited >= offset) {
                return;
            }
            long room = StepMath.releaseRoom(raw, credited, stepCap);
            if (room <= 0L) {
                if (!"1".equals(System.getProperty("dsport.capped"))) {
                    System.setProperty("dsport.capped", "1");
                    XposedBridge.log("DSport: cap " + stepCap + " raw " + raw
                            + " credited " + credited + " refuse");
                }
                return;
            }
            long wallMinute = System.currentTimeMillis() / 60_000L;
            if (wallMinute == lastCreditMinute) {
                return;
            }
            long already = releasedInWindow(wallMinute);
            long release = StepMath.nextRelease(credited, offset, already, stepsPerMinute, stepsPerFive);
            if (release <= 0L) {
                lastCreditMinute = wallMinute;
                return;
            }
            if (release > room) {
                release = room;
            }
            lastCreditMinute = wallMinute;
            long before = credited;
            long next = pushCredited(before + release);
            if (next < before) {
                next = before;
            }
            credited = next;
            long added = credited - before;
            if (added > 0L) {
                rememberRelease(wallMinute, added);
            }
            configReadAt = SystemClock.elapsedRealtime();
            XposedBridge.log("DSport: credit " + credited + "/" + offset + " +" + added
                    + " minute " + stepsPerMinute
                    + " five " + stepsPerFive);
        }
    }

    private static long releasedInWindow(long wallMinute) {
        long sum = 0L;
        int kept = 0;
        for (int i = 0; i < creditCount; i++) {
            if (wallMinute - creditMinute[i] < 5L) {
                creditMinute[kept] = creditMinute[i];
                creditAmount[kept] = creditAmount[i];
                sum += creditAmount[i];
                kept++;
            }
        }
        creditCount = kept;
        return sum;
    }

    private static void rememberRelease(long wallMinute, long amount) {
        if (creditCount == WINDOW) {
            System.arraycopy(creditMinute, 1, creditMinute, 0, WINDOW - 1);
            System.arraycopy(creditAmount, 1, creditAmount, 0, WINDOW - 1);
            creditCount = WINDOW - 1;
        }
        creditMinute[creditCount] = wallMinute;
        creditAmount[creditCount] = amount;
        creditCount++;
    }

    private static long pushCredited(long next) {
        Context context = currentApplication();
        if (context == null) {
            return next;
        }
        Bundle extras = new Bundle();
        extras.putLong("credited", next);
        try {
            Bundle result = context.getContentResolver().call(CONFIG, "credit", null, extras);
            if (result != null) {
                return result.getLong("credited", next);
            }
        } catch (Throwable error) {
            XposedBridge.log(error);
        }
        return next;
    }

    private static void report(String packageName, String key, float raw0, float mod0, float raw1,
            float mod1) {
        int slot = slotOf(key);
        long now = SystemClock.elapsedRealtime();
        if (slot >= 0 && now - lastReportAt[slot] < REPORT_TTL_MS) {
            return;
        }
        if (slot >= 0) {
            lastRaw[slot] = raw0;
            lastReportAt[slot] = now;
        }
        Context context = currentApplication();
        if (context == null) {
            return;
        }
        Bundle extras = new Bundle();
        extras.putString("sensor", key);
        extras.putString("pkg", packageName);
        extras.putFloat("raw0", raw0);
        extras.putFloat("mod0", mod0);
        extras.putFloat("raw1", raw1);
        extras.putFloat("mod1", mod1);
        extras.putBoolean("modified", enabled && !StepMath.KEY_DETECTOR.equals(key) && mod0 != raw0);
        extras.putLong("time", System.currentTimeMillis());
        try {
            context.getContentResolver().call(CONFIG, "report", null, extras);
        } catch (Throwable ignored) {
            // The page still shows this process's own listener. Status is optional.
        }
    }

    private static int slotOf(String key) {
        if (StepMath.KEY_COUNTER.equals(key)) {
            return 0;
        }
        if (StepMath.KEY_DETECTOR.equals(key)) {
            return 1;
        }
        if (StepMath.KEY_MINUTE.equals(key)) {
            return 2;
        }
        return -1;
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
