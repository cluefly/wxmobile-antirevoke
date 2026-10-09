package dev.dsport.module;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends android.app.Activity {
    private static final int REQ_PERMISSION = 1;
    private static final long MAX_OFFSET = 100_000_000L;
    private static final long MAX_RATE = 1_000_000L;

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SensorManager sensorManager;
    private TextView permText;
    private TextView counterValue;
    private TextView counterTime;
    private TextView counterHook;
    private TextView detectorValue;
    private TextView detectorCount;
    private TextView detectorTime;
    private TextView detectorHook;
    private TextView minuteValue;
    private TextView minuteSeq;
    private TextView minuteExtra;
    private TextView minuteTime;
    private TextView minuteHook;
    private TextView savedLine;
    private TextView previewLine;
    private Switch enabledSwitch;
    private EditText offsetInput;
    private EditText perMinuteInput;
    private EditText perFiveInput;
    private EditText stepCapInput;
    private View generalPage;
    private View wechatPage;
    private Button tabGeneral;
    private Button tabWechat;
    private TextView wechatStatus;
    private LinearLayout wechatRows;
    private TextView wechatNote;
    private Switch antiRevokeSwitch;
    private int wechatLoad;
    private int detectorEvents;
    private Float counterRaw;
    private Float minuteRaw;

    private final SensorEventListener listener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            String key = StepMath.keyFor(event.sensor.getType(), event.sensor.getStringType());
            if (StepMath.KEY_COUNTER.equals(key)) {
                counterRaw = event.values[0];
                counterValue.setText(StepMath.formatCount(event.values[0]));
                counterTime.setText("收到时间 " + clock.format(new Date()));
            } else if (StepMath.KEY_DETECTOR.equals(key)) {
                detectorEvents += 1;
                detectorValue.setText(StepMath.formatCount(event.values[0]));
                detectorCount.setText("本次打开后触发 " + detectorEvents + " 次");
                detectorTime.setText("收到时间 " + clock.format(new Date()));
            } else if (StepMath.KEY_MINUTE.equals(key)) {
                minuteRaw = event.values[0];
                minuteValue.setText(StepMath.formatCount(event.values[0]));
                if (event.values.length > 1) {
                    minuteSeq.setText("序号 values[1] " + StepMath.formatCount(event.values[1]) + "（不修改）");
                }
                minuteExtra.setText(formatExtra(event.values));
                minuteTime.setText("收到时间 " + clock.format(new Date()));
            }
            renderPreview();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final Runnable hookRefresh = new Runnable() {
        @Override
        public void run() {
            renderSaved();
            renderHook();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        bindViews();
        if (savedInstanceState == null) {
            ConfigStore.Config config = ConfigStore.get(this);
            enabledSwitch.setChecked(config.enabled);
            offsetInput.setText(Long.toString(config.enabled ? config.goal : config.addition));
            offsetInput.setHint(config.enabled
                    ? getString(R.string.offset_hint)
                    : getString(R.string.offset_add_hint));
            perMinuteInput.setText(Long.toString(config.perMinute));
            perFiveInput.setText(Long.toString(config.perFive));
            stepCapInput.setText(Long.toString(config.stepCap));
        }
        TextWatcher previewWatch = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                renderPreview();
            }
        };
        offsetInput.addTextChangedListener(previewWatch);
        perMinuteInput.addTextChangedListener(previewWatch);
        perFiveInput.addTextChangedListener(previewWatch);
        stepCapInput.addTextChangedListener(previewWatch);
        antiRevokeSwitch.setChecked(ConfigStore.get(this).antiRevoke);
        antiRevokeSwitch.setOnCheckedChangeListener((button, checked) ->
                ConfigStore.putAntiRevoke(this, checked));
        enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            offsetInput.setHint(checked
                    ? getString(R.string.offset_hint)
                    : getString(R.string.offset_add_hint));
            if (!checked) {
                offsetInput.setText("");
            } else {
                offsetInput.setText(Long.toString(ConfigStore.get(this).goal));
            }
            renderPreview();
        });
        findViewById(R.id.save_button).setOnClickListener(this::save);
        tabGeneral.setOnClickListener(view -> showTab(true));
        tabWechat.setOnClickListener(view -> showTab(false));
        findViewById(R.id.wechat_refresh).setOnClickListener(view -> loadWeChatConfig());
        findViewById(R.id.wechat_apply).setOnClickListener(view -> applyWeChatRates());
        findViewById(R.id.wechat_stop).setOnClickListener(view -> controlWeChat(false));
        findViewById(R.id.wechat_start).setOnClickListener(view -> controlWeChat(true));
        showTab(true);
        renderSaved();
        renderPreview();
        renderHook();
    }

    private void bindViews() {
        permText = findViewById(R.id.perm_text);
        counterValue = findViewById(R.id.counter_value);
        counterTime = findViewById(R.id.counter_time);
        counterHook = findViewById(R.id.counter_hook);
        detectorValue = findViewById(R.id.detector_value);
        detectorCount = findViewById(R.id.detector_count);
        detectorTime = findViewById(R.id.detector_time);
        detectorHook = findViewById(R.id.detector_hook);
        minuteValue = findViewById(R.id.minute_value);
        minuteSeq = findViewById(R.id.minute_seq);
        minuteExtra = findViewById(R.id.minute_extra);
        minuteTime = findViewById(R.id.minute_time);
        minuteHook = findViewById(R.id.minute_hook);
        savedLine = findViewById(R.id.saved_line);
        previewLine = findViewById(R.id.preview_line);
        enabledSwitch = findViewById(R.id.enabled_switch);
        offsetInput = findViewById(R.id.offset_input);
        perMinuteInput = findViewById(R.id.per_minute_input);
        perFiveInput = findViewById(R.id.per_five_input);
        stepCapInput = findViewById(R.id.step_cap_input);
        generalPage = findViewById(R.id.general_page);
        wechatPage = findViewById(R.id.wechat_page);
        tabGeneral = findViewById(R.id.tab_general);
        tabWechat = findViewById(R.id.tab_wechat);
        wechatStatus = findViewById(R.id.wechat_status);
        wechatRows = findViewById(R.id.wechat_rows);
        wechatNote = findViewById(R.id.wechat_note);
        antiRevokeSwitch = findViewById(R.id.anti_revoke_switch);
    }

    private void showWeChat(String json) {
        wechatRows.removeAllViews();
        if (json == null) {
            wechatNote.setText("");
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        float density = getResources().getDisplayMetrics().density;
        for (WeChatSportConfig.Line line : WeChatSportConfig.lines(json)) {
            if (line.heading) {
                TextView heading = new TextView(this);
                heading.setText(line.label);
                heading.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
                heading.setTextColor(0xFF1B6B4A);
                heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                int pad = Math.round(10f * density);
                heading.setPadding(0, pad, 0, 0);
                wechatRows.addView(heading);
                continue;
            }
            View row = inflater.inflate(R.layout.wechat_row, wechatRows, false);
            TextView label = row.findViewById(R.id.row_label);
            TextView value = row.findViewById(R.id.row_value);
            label.setText(line.label);
            value.setText(line.value);
            if (line.tone == 1) {
                value.setTextColor(0xFF1B6B4A);
            } else if (line.tone == 2) {
                value.setTextColor(0xFF8A3B12);
            }
            wechatRows.addView(row);
        }
        wechatNote.setText(WeChatSportConfig.applyNote(json));
    }

    /** Stops or starts WeChat. APatch must allow this app to use root. */
    private void controlWeChat(boolean start) {
        String command = start
                ? "am start -n com.tencent.mm/.ui.LauncherUI"
                : "am force-stop com.tencent.mm";
        String done = start ? "已启动微信" : "已停止微信";
        new Thread(() -> {
            String message = done;
            try {
                Process process = new ProcessBuilder("/system/bin/su", "-c", command)
                        .redirectErrorStream(true)
                        .start();
                ByteArrayOutputStream captured = new ByteArrayOutputStream();
                InputStream stream = process.getInputStream();
                byte[] buffer = new byte[256];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    captured.write(buffer, 0, read);
                }
                int code = process.waitFor();
                if (code != 0) {
                    String detail = captured.toString(StandardCharsets.UTF_8).trim();
                    message = detail.isEmpty() ? "需要在 APatch 里允许 DSport 使用 root" : detail;
                }
            } catch (Throwable error) {
                message = "需要在 APatch 里允许 DSport 使用 root";
            }
            String toast = message;
            handler.post(() -> Toast.makeText(getApplicationContext(), toast, Toast.LENGTH_SHORT).show());
        }, "dsport-wechat").start();
    }

    private void showTab(boolean general) {
        generalPage.setVisibility(general ? View.VISIBLE : View.GONE);
        wechatPage.setVisibility(general ? View.GONE : View.VISIBLE);
        styleTab(tabGeneral, general);
        styleTab(tabWechat, !general);
        if (!general) {
            loadWeChatConfig();
        }
    }

    private void styleTab(Button button, boolean selected) {
        button.setBackgroundColor(selected ? 0xFF1B6B4A : 0xFFE4EBE7);
        button.setTextColor(selected ? 0xFFFFFFFF : 0xFF143D2C);
    }

    private void applyWeChatRates() {
        final int ticket = ++wechatLoad;
        wechatStatus.setText("正在用 SportKv 覆盖通用页上限。");
        new Thread(() -> {
            SportRead read = readSportKv();
            String json = WeChatSportConfig.extractConfigJson(read.blob);
            WeChatSportConfig.Rates rates = WeChatSportConfig.ratesFrom(json);
            handler.post(() -> {
                if (ticket != wechatLoad) {
                    return;
                }
                if (rates == null) {
                    wechatStatus.setText(read.blob == null
                            ? "没有读到 SportKv。" + read.error
                            : "SportKv 里没有可用的 5 分钟上限，没有覆盖。");
                    if (json != null) {
                        showWeChat(json);
                    }
                    return;
                }
                ConfigStore.putRates(MainActivity.this, rates.perMinute, rates.perFive);
                perMinuteInput.setText(Long.toString(rates.perMinute));
                perFiveInput.setText(Long.toString(rates.perFive));
                renderSaved();
                renderPreview();
                wechatStatus.setText("已覆盖通用页：每分钟 " + rates.perMinute
                        + " 步，每 5 分钟 " + rates.perFive
                        + " 步。释放停在 " + StepMath.acceptableFive(rates.perFive) + "。");
                if (json != null) {
                    showWeChat(json);
                }
            });
        }, "wechat-apply").start();
    }

    private void loadWeChatConfig() {
        final int ticket = ++wechatLoad;
        wechatStatus.setText("正在读取主用户的微信运动配置。");
        showWeChat(null);
        new Thread(() -> {
            SportRead read = readSportKv();
            String json = WeChatSportConfig.extractConfigJson(read.blob);
            handler.post(() -> {
                if (ticket != wechatLoad) {
                    return;
                }
                if (json != null) {
                    wechatStatus.setText("来源：主用户 SportKv 的服务端配置");
                    showWeChat(json);
                } else if (read.blob == null) {
                    wechatStatus.setText("没有读到 SportKv。" + read.error);
                    showWeChat(null);
                } else {
                    wechatStatus.setText("文件里还没有 stepCounter 配置。");
                    showWeChat(null);
                }
            });
        }, "wechat-config").start();
    }

    /** SportKv is not readable with cat; cp streams the plaintext. */
    private static SportRead readSportKv() {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/su", "-M", "-c",
                    "cp /data/user/0/com.tencent.mm/files/mmkv/SportKv /dev/stdout").start();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            InputStream input = process.getInputStream();
            InputStream error = process.getErrorStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = input.read(chunk)) >= 0) {
                buffer.write(chunk, 0, n);
            }
            while ((n = error.read(chunk)) >= 0 && errors.size() < 180) {
                errors.write(chunk, 0, n);
            }
            int code = process.waitFor();
            if (code != 0 || buffer.size() == 0) {
                String why = new String(errors.toByteArray(), StandardCharsets.UTF_8).trim();
                if (why.isEmpty()) {
                    why = "退出码 " + code;
                }
                int line = why.indexOf('\n');
                if (line >= 0) {
                    why = why.substring(0, line).trim();
                }
                return new SportRead(null, why);
            }
            return new SportRead(new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1), "");
        } catch (Exception error) {
            String why = error.getClass().getSimpleName();
            if (error.getMessage() != null) {
                why = why + ": " + error.getMessage();
            }
            return new SportRead(null, why);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static final class SportRead {
        final String blob;
        final String error;

        SportRead(String blob, String error) {
            this.blob = blob;
            this.error = error == null ? "" : error;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.ACTIVITY_RECOGNITION}, REQ_PERMISSION);
        }
        registerSensors();
        handler.removeCallbacks(hookRefresh);
        handler.post(hookRefresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(hookRefresh);
        sensorManager.unregisterListener(listener);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        registerSensors();
    }

    private void registerSensors() {
        sensorManager.unregisterListener(listener);
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            permText.setVisibility(View.VISIBLE);
            permText.setText("需要活动识别权限，才能读取下面三项。");
            return;
        }
        permText.setVisibility(View.GONE);
        registerOne(sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER), counterValue, "这台手机没有 step_counter");
        registerOne(sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR), detectorValue, "这台手机没有 step_detector");
        registerOne(findMinute(), minuteValue, "这台手机没有 pedometer_minute");
    }

    private void registerOne(Sensor sensor, TextView value, String missing) {
        if (sensor == null) {
            value.setText(missing);
            return;
        }
        if (!sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
            value.setText("注册失败");
        }
    }

    private Sensor findMinute() {
        List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor sensor : sensors) {
            if (StepMath.isMinutePedometer(sensor.getType(), sensor.getStringType())) {
                return sensor;
            }
        }
        return null;
    }

    private void save(View view) {
        Long offset = parseOffset(offsetInput.getText().toString());
        if (offset == null || offset > MAX_OFFSET || offset < -MAX_OFFSET) {
            Toast.makeText(this, "偏移要是整数，范围 ±100000000", Toast.LENGTH_SHORT).show();
            return;
        }
        Long perMinute = parseWhole(perMinuteInput.getText().toString());
        Long perFive = parseWhole(perFiveInput.getText().toString());
        Long stepCap = parseWhole(stepCapInput.getText().toString());
        if (!validRates(perMinute, perFive)) {
            Toast.makeText(this, "每分钟、每 5 分钟要是正整数，每 5 分钟至少 2，且每分钟不能更大", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!validCap(stepCap)) {
            Toast.makeText(this, "步数上限要是 1 到 100000000 的整数", Toast.LENGTH_SHORT).show();
            return;
        }
        if (enabledSwitch.isChecked()) {
            ConfigStore.putAbsolute(this, offset, perMinute, perFive, stepCap);
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        } else {
            if (offset < 0L) {
                Toast.makeText(this, "关闭开关时，输入的是再增加的步数，不能为负数", Toast.LENGTH_SHORT).show();
                return;
            }
            ConfigStore.Config current = ConfigStore.get(this);
            StepMath.AdditionGoal addition = StepMath.additionGoal(
                    current.credited, current.base, current.goal, current.enabled, offset);
            long added = addition.goal - addition.base;
            ConfigStore.putAddition(this, addition.base, added, addition.goal, perMinute, perFive, stepCap);
            offsetInput.setText(Long.toString(added));
            Toast.makeText(this, "在已计入 " + current.credited + " 上再增加 " + added, Toast.LENGTH_SHORT).show();
        }
        renderSaved();
        renderPreview();
    }

    private void renderSaved() {
        ConfigStore.Config config = ConfigStore.get(this);
        String rates = "每分钟 " + config.perMinute + "，每 5 分钟 " + config.perFive
                + "，释放停在 " + StepMath.acceptableFive(config.perFive)
                + "。上限 " + config.stepCap;
        if (config.enabled) {
            savedLine.setText("修改打开，目标 " + config.goal + "，已计入 " + config.credited + "。" + rates);
        } else {
            savedLine.setText("修改关闭。已计入 " + config.credited + "，本次再增加 " + config.addition
                    + "，目标 " + config.goal + "。" + rates);
        }
    }

    private void renderPreview() {
        Long offset = parseOffset(offsetInput.getText().toString());
        Long perMinute = parseWhole(perMinuteInput.getText().toString());
        Long perFive = parseWhole(perFiveInput.getText().toString());
        Long stepCap = parseWhole(stepCapInput.getText().toString());
        if (offset == null) {
            previewLine.setText("步数还不是整数。");
            return;
        }
        if (!validRates(perMinute, perFive) || !validCap(stepCap)) {
            previewLine.setText("每分钟、每 5 分钟或步数上限还不是可用的整数。");
            return;
        }
        String capNote = "送出累计达到 " + stepCap + " 后不再增加。";
        if (!enabledSwitch.isChecked()) {
            if (offset < 0L) {
                previewLine.setText("关闭开关时，输入的是再增加的步数，不能为负数。已加上的步数保留。");
                return;
            }
            ConfigStore.Config current = ConfigStore.get(this);
            StepMath.AdditionGoal addition = StepMath.additionGoal(
                    current.credited, current.base, current.goal, current.enabled, offset);
            previewLine.setText("在已计入 " + current.credited + " 的基础上再增加 "
                    + (addition.goal - addition.base) + "，目标 " + addition.goal
                    + "。每分钟最多 " + perMinute + " 步，每 5 分钟最多 "
                    + StepMath.acceptableFive(perFive) + " 步。已加上的步数保留。" + capNote);
            return;
        }
        previewLine.setText("step_counter 累计值每分钟最多加 " + perMinute
                + " 步，每 5 分钟最多 " + StepMath.acceptableFive(perFive)
                + " 步，直到加满 " + offset + "。" + capNote);
    }

    private static boolean validCap(Long stepCap) {
        return stepCap != null && stepCap >= 1L && stepCap <= MAX_OFFSET;
    }

    private static boolean validRates(Long perMinute, Long perFive) {
        return perMinute != null && perFive != null
                && perMinute >= 1L && perMinute <= MAX_RATE
                && perFive >= 2L && perFive <= MAX_RATE
                && perMinute <= perFive;
    }

    private void renderHook() {
        counterHook.setText(hookLine(StepMath.KEY_COUNTER, false));
        detectorHook.setText(hookLine(StepMath.KEY_DETECTOR, false));
        minuteHook.setText(hookLine(StepMath.KEY_MINUTE, true));
    }

    private String hookLine(String key, boolean withSeq) {
        HookStatus.Sample sample = HookStatus.get(key);
        if (sample == null) {
            return "系统服务侧：还没有回调";
        }
        String who = sample.pkg == null || sample.pkg.isEmpty() ? "其他进程" : sample.pkg;
        String text = "系统服务侧 " + who + " 原始 " + StepMath.formatCount(sample.raw0)
                + " / 送出 " + StepMath.formatCount(sample.mod0);
        if (withSeq && !Float.isNaN(sample.raw1)) {
            text += "，序号 " + StepMath.formatCount(sample.raw1);
        }
        return text;
    }

    private static String formatExtra(float[] values) {
        if (values.length <= 2) {
            return "其他：无";
        }
        StringBuilder builder = new StringBuilder("其他");
        int end = Math.min(values.length, 6);
        for (int i = 2; i < end; i++) {
            builder.append(" [").append(i).append("] ").append(StepMath.formatCount(values[i]));
        }
        return builder.toString();
    }

    private static Long parseWhole(String text) {
        Long value = parseOffset(text);
        if (value == null || value < 0L) {
            return null;
        }
        return value;
    }

    private static Long parseOffset(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
