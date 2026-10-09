package dev.dsport.module;

import android.content.Context;
import android.content.SharedPreferences;

public final class ConfigStore {
    public static final String PREF = "dsport";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_OFFSET = "offset";
    private static final String KEY_CREDITED = "credited";
    private static final String KEY_PER_MINUTE = "perMinute";
    private static final String KEY_PER_FIVE = "perFive";
    private static final String KEY_BASE = "base";
    private static final String KEY_ADDITION = "addition";
    private static final String KEY_STEP_CAP = "stepCap";
    private static final String KEY_ANTI_REVOKE = "antiRevoke";

    public static final class Config {
        public final boolean enabled;
        /** Absolute step target. The hook releases until {@code credited} reaches this. */
        public final long goal;
        public final long credited;
        /** Credited value when the current add-on round started. */
        public final long base;
        /** Amount typed while the switch is off. */
        public final long addition;
        public final long perMinute;
        public final long perFive;
        public final long stepCap;
        /** When true, WeChat's message table keeps a row that a recall would replace. */
        public final boolean antiRevoke;

        public Config(boolean enabled, long goal, long credited, long base, long addition,
                long perMinute, long perFive, long stepCap, boolean antiRevoke) {
            this.enabled = enabled;
            this.goal = goal;
            this.credited = credited;
            this.base = base;
            this.addition = addition;
            this.perMinute = perMinute;
            this.perFive = perFive;
            this.stepCap = stepCap;
            this.antiRevoke = antiRevoke;
        }
    }

    private ConfigStore() {}

    public static Config get(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long perMinute = prefs.getLong(KEY_PER_MINUTE, StepMath.STEPS_PER_MINUTE);
        long perFive = prefs.getLong(KEY_PER_FIVE, StepMath.STEPS_PER_FIVE_MINUTES);
        if (perMinute <= 0L) {
            perMinute = StepMath.STEPS_PER_MINUTE;
        }
        if (perFive < 2L) {
            perFive = StepMath.STEPS_PER_FIVE_MINUTES;
        }
        long stepCap = prefs.getLong(KEY_STEP_CAP, StepMath.DEFAULT_STEP_CAP);
        if (stepCap <= 0L) {
            stepCap = StepMath.DEFAULT_STEP_CAP;
        }
        return new Config(
                prefs.getBoolean(KEY_ENABLED, false),
                prefs.getLong(KEY_OFFSET, 0L),
                prefs.getLong(KEY_CREDITED, 0L),
                prefs.getLong(KEY_BASE, 0L),
                prefs.getLong(KEY_ADDITION, 0L),
                perMinute,
                perFive,
                stepCap,
                prefs.getBoolean(KEY_ANTI_REVOKE, true));
    }

    /** Leaves the step offset and the release caps untouched. */
    public static void putAntiRevoke(Context context, boolean enabled) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ANTI_REVOKE, enabled)
                .commit();
    }

    /** Switch on: {@code goal} is the absolute target. A lower goal pulls credited down. */
    public static void putAbsolute(Context context, long goal, long perMinute, long perFive,
            long stepCap) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long credited = prefs.getLong(KEY_CREDITED, 0L);
        if (credited > goal) {
            credited = Math.max(0L, goal);
        }
        prefs.edit()
                .putBoolean(KEY_ENABLED, true)
                .putLong(KEY_OFFSET, goal)
                .putLong(KEY_CREDITED, credited)
                .putLong(KEY_BASE, 0L)
                .putLong(KEY_ADDITION, 0L)
                .putLong(KEY_PER_MINUTE, perMinute)
                .putLong(KEY_PER_FIVE, perFive)
                .putLong(KEY_STEP_CAP, stepCap)
                .commit();
    }

    /**
     * Switch off: keep every step already credited and release {@code addition}
     * more, from {@code base}, with the same per-minute rules.
     */
    public static void putAddition(Context context, long base, long addition, long goal,
            long perMinute, long perFive, long stepCap) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(KEY_ENABLED, false)
                .putLong(KEY_OFFSET, goal)
                .putLong(KEY_BASE, base)
                .putLong(KEY_ADDITION, addition)
                .putLong(KEY_PER_MINUTE, perMinute)
                .putLong(KEY_PER_FIVE, perFive)
                .putLong(KEY_STEP_CAP, stepCap)
                .commit();
    }

    /** Writes the two release caps and leaves the offset switch untouched. */
    public static void putRates(Context context, long perMinute, long perFive) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        prefs.edit()
                .putLong(KEY_PER_MINUTE, perMinute)
                .putLong(KEY_PER_FIVE, perFive)
                .commit();
    }

    /** Raises the amount already fed into per-minute deltas. Never moves backward. */
    public static long creditAtLeast(Context context, long credited) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long offset = prefs.getLong(KEY_OFFSET, 0L);
        long current = prefs.getLong(KEY_CREDITED, 0L);
        long next = Math.max(current, Math.min(offset, Math.max(0L, credited)));
        prefs.edit().putLong(KEY_CREDITED, next).commit();
        return next;
    }
}
