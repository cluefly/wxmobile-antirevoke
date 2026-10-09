package dev.dsport.module;

public final class StepMathTest {
    public static void main(String[] args) {
        check(StepMath.applyOffset(12967f, 100, false) == 12967f, "disabled");
        check(StepMath.applyOffset(12967f, 0, true) == 12967f, "zero");
        check(StepMath.applyOffset(12967f, 100, true) == 13067f, "add");
        check(StepMath.applyOffset(10f, -50, true) == 10f, "refuse negative");
        check(StepMath.nonDecreasingGoal(3000, 1000) == 3000, "keep credited");
        check(StepMath.nonDecreasingGoal(3000, 8000) == 8000, "raise goal");
        check(StepMath.nonDecreasingGoal(3000, -5) == 3000, "refuse negative goal");
        check(StepMath.nextRelease(100, -50, 0) == 0, "no negative release");
        check(StepMath.allowedBoost(100f, -20, 18000) == 0, "no negative boost");
        check(StepMath.applyOffset(13049f, 500, true) == 13549f, "minute");
        check(StepMath.isMinutePedometer(33171034, "other"), "type");
        check(StepMath.isMinutePedometer(1, "qti.sensor.pedometer_minute"), "name");
        check(!StepMath.rewritesCumulative(18, "android.sensor.step_detector"), "detector");
        check(StepMath.rewritesCumulative(19, "android.sensor.step_counter"), "counter");
        check(!StepMath.rewritesCumulative(33171034, "qti.sensor.pedometer_minute"), "minute raw");
        check(StepMath.releasesCredit("com.oplus.exsystemservice"), "release system");
        check(StepMath.releasesCredit("com.oplus.healthservice"), "release health");
        check(!StepMath.releasesCredit("com.tencent.mm"), "wechat apply only");
        check(StepMath.nextRelease(0, 3800, 0) == 300, "drip");
        check(StepMath.nextRelease(0, 3800, 1200) == 299, "window");
        check(StepMath.nextRelease(0, 3800, 1499) == 0, "full");
        check(StepMath.nextRelease(3600, 3800, 0) == 200, "tail");
        check(StepMath.nextRelease(3800, 3800, 0) == 0, "done");
        StepMath.AdditionGoal added = StepMath.additionGoal(5300, 0, 6000, true, 2000);
        check(added.base == 5300 && added.goal == 7300, "new round");
        added = StepMath.additionGoal(6000, 5300, 7300, false, 2000);
        check(added.base == 5300 && added.goal == 7300, "same round");
        added = StepMath.additionGoal(7300, 5300, 7300, false, 2000);
        check(added.base == 7300 && added.goal == 9300, "next round");
        added = StepMath.additionGoal(6000, 5300, 7300, false, 100);
        check(added.base == 5300 && added.goal == 6000, "no drop");
        check(StepMath.allowedBoost(0f, 6000, 18000) == 6000, "cap room");
        check(StepMath.allowedBoost(17900f, 6000, 18000) == 100, "cap tail");
        check(StepMath.allowedBoost(18000f, 6000, 18000) == 0, "cap full");
        check(StepMath.releaseRoom(0f, 6000, 18000) == 12000, "release room");
        check(StepMath.releaseRoom(17990f, 20, 18000) == 0, "release none");
        check(StepMath.DEFAULT_STEP_CAP == 18000, "default cap");
        check(StepMath.nextRelease(0, 3800, 0, 100, 400) == 100, "custom minute");
        check(StepMath.nextRelease(0, 3800, 300, 100, 400) == 99, "custom window");
        check(WeChatSportConfig.ratesFrom(null) == null, "no rates");
        String blob = "old {\"stepCounterRateUs\":1} KEY {\"stepCounterRateUs\":600000,"
                + "\"stepCounterSaveInterval\":60000,\"stepCounterSaveStep\":50,"
                + "\"stepCounterMaxStep5m\":1500,\"deviceStepSwitch\":1,\"extStepApiSwitch\":1,"
                + "\"stepExtConfig\":{\"version\":1,\"interval\":7200},"
                + "\"stepCountUploadConfig\":{\"backgroundTimeInterval\":60,"
                + "\"backgroundStepCountInterval\":500},\"checkWeSportInstall\":1}";
        String json = WeChatSportConfig.extractConfigJson(blob);
        check(json != null && json.startsWith("{\"stepCounterRateUs\":600000"), "json");
        WeChatSportConfig.Rates rates = WeChatSportConfig.ratesFrom(json);
        check(rates != null && rates.perMinute == 300 && rates.perFive == 1500, "rates");
        check(WeChatSportConfig.field(json, "stepCounterMaxStep5m", -1) == 1500, "max");
        check(WeChatSportConfig.field(json, "interval", -1) == 7200, "interval");
        check(WeChatSportConfig.describe(json).contains("每分钟 300"), "describe");
        WeChatSportConfig.Line[] lines = WeChatSportConfig.lines(json);
        boolean fiveRow = false;
        boolean deviceOn = false;
        for (WeChatSportConfig.Line line : lines) {
            if ("5 分钟上限".equals(line.label) && "1500 步".equals(line.value)) {
                fiveRow = true;
            }
            if ("本机计步".equals(line.label) && "开".equals(line.value) && line.tone == 1) {
                deviceOn = true;
            }
        }
        check(fiveRow && deviceOn, "rows");
        check(WeChatSportConfig.applyNote(json).contains("每分钟 300"), "note");
        check("13049".equals(StepMath.formatCount(13049f)), "format");
        check(StepMath.KEY_COUNTER.equals(StepMath.keyFor(19, "android.sensor.step_counter")), "key");
        System.out.println("StepMathTest ok");
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
    }
}
