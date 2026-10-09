package dev.dsport.module;

/**
 * Step-count adjustment shared by the UI preview and the Vector hook.
 * Only cumulative counters are rewritten. Step detector events stay untouched.
 */
public final class StepMath {
    public static final int TYPE_STEP_COUNTER = 19;
    public static final int TYPE_STEP_DETECTOR = 18;
    public static final int TYPE_PEDOMETER_MINUTE = 33171034;
    public static final String STRING_PEDOMETER_MINUTE = "qti.sensor.pedometer_minute";

    public static final String KEY_COUNTER = "step_counter";
    public static final String KEY_DETECTOR = "step_detector";
    public static final String KEY_MINUTE = "pedometer_minute";
    /** WeChat's step_counter budget: 300 steps in one minute, 1500 in five minutes. */
    public static final long STEPS_PER_MINUTE = 300L;
    public static final long STEPS_PER_FIVE_MINUTES = 1500L;
    /** Sent step_counter total at which DSport stops adding steps. */
    public static final long DEFAULT_STEP_CAP = 18_000L;
    /**
     * WeChat drops a slice that reaches the five-minute budget ({@code >=}).
     * The releaser stops one step earlier so that slice is still accepted.
     */
    public static final long ACCEPTABLE_FIVE_MINUTES = STEPS_PER_FIVE_MINUTES - 1L;

    private StepMath() {}

    public static boolean isMinutePedometer(int type, String stringType) {
        return type == TYPE_PEDOMETER_MINUTE || STRING_PEDOMETER_MINUTE.equals(stringType);
    }

    public static boolean rewritesCumulative(int type, String stringType) {
        return type == TYPE_STEP_COUNTER;
    }

    /**
     * Only the system step process moves the credited total forward.
     * Other hooked processes, including WeChat, add that same total and do not
     * release another minute of their own.
     */
    public static boolean releasesCredit(String packageName) {
        return "com.oplus.exsystemservice".equals(packageName)
                || "com.oplus.healthservice".equals(packageName);
    }

    public static String keyFor(int type, String stringType) {
        if (type == TYPE_STEP_COUNTER) {
            return KEY_COUNTER;
        }
        if (type == TYPE_STEP_DETECTOR) {
            return KEY_DETECTOR;
        }
        if (isMinutePedometer(type, stringType)) {
            return KEY_MINUTE;
        }
        return null;
    }

    /** WeChat drops a slice that reaches the five-minute cap, so release stops one step earlier. */
    public static long acceptableFive(long perFive) {
        return perFive <= 0L ? 0L : perFive - 1L;
    }

    /**
     * Steps to add to the step_counter total this minute.
     * {@code releasedInFiveMinutes} is what this process already added in the current
     * five wall-clock minutes. The result never exceeds one minute or the five-minute budget.
     */
    /** Absolute goal after the enable switch is turned off and a new amount is entered. */
    public static final class AdditionGoal {
        public final long base;
        public final long goal;

        public AdditionGoal(long base, long goal) {
            this.base = base;
            this.goal = goal;
        }
    }

    /**
     * A new round starts when the switch was on, or the previous goal is already reached.
     * The goal never moves below steps already credited.
     */
    public static AdditionGoal additionGoal(long credited, long storedBase, long storedGoal,
            boolean wasEnabled, long typedAddition) {
        long addition = Math.max(0L, typedAddition);
        boolean newRound = wasEnabled || storedGoal <= credited;
        long base = newRound ? Math.max(0L, credited) : Math.max(0L, storedBase);
        long goal = base + addition;
        if (goal < credited) {
            goal = credited;
        }
        return new AdditionGoal(base, goal);
    }

    public static long nextRelease(long credited, long offset, long releasedInFiveMinutes) {
        return nextRelease(credited, offset, releasedInFiveMinutes,
                STEPS_PER_MINUTE, STEPS_PER_FIVE_MINUTES);
    }

    public static long nextRelease(long credited, long offset, long releasedInFiveMinutes,
            long perMinute, long perFive) {
        if (offset <= 0L || credited >= offset || perMinute <= 0L) {
            return 0L;
        }
        long remain = offset - Math.max(0L, credited);
        long windowRoom = acceptableFive(perFive) - Math.max(0L, releasedInFiveMinutes);
        if (remain <= 0L || windowRoom <= 0L) {
            return 0L;
        }
        return Math.min(perMinute, Math.min(remain, windowRoom));
    }

    /**
     * Boost that may be added to {@code raw} without the sent total passing {@code cap}.
     * A raw count already at the cap receives nothing.
     */
    public static long allowedBoost(float raw, long credited, long cap) {
        if (credited <= 0L) {
            return 0L;
        }
        if (cap <= 0L) {
            return credited;
        }
        if (Float.isNaN(raw) || raw < 0f) {
            return 0L;
        }
        long rawSteps = (long) Math.floor(raw);
        if (rawSteps >= cap) {
            return 0L;
        }
        return Math.min(credited, cap - rawSteps);
    }

    /** How many more credited steps can still be released under the cap. */
    public static long releaseRoom(float raw, long credited, long cap) {
        if (cap <= 0L) {
            return Long.MAX_VALUE;
        }
        if (Float.isNaN(raw) || raw < 0f) {
            return 0L;
        }
        long room = cap - (long) Math.floor(raw) - Math.max(0L, credited);
        return room > 0L ? room : 0L;
    }

    public static float applyOffset(float raw, long offset, boolean enabled) {
        if (!enabled || offset == 0L) {
            return raw;
        }
        double value = (double) raw + (double) offset;
        if (value < 0d) {
            value = 0d;
        }
        if (value > Float.MAX_VALUE) {
            value = Float.MAX_VALUE;
        }
        return (float) value;
    }

    public static String formatCount(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return "—";
        }
        int nearest = Math.round(value);
        if (Math.abs(value - nearest) < 0.05f) {
            return Integer.toString(nearest);
        }
        return Float.toString(value);
    }
}
