package wx.module;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Switch;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends android.app.Activity {
    private static final long SU_WAIT_MS = 8_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Switch antiRevokeSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        antiRevokeSwitch = findViewById(R.id.anti_revoke_switch);
        antiRevokeSwitch.setChecked(ConfigStore.get(this).antiRevoke);
        antiRevokeSwitch.setOnCheckedChangeListener((button, checked) ->
                ConfigStore.putAntiRevoke(this, checked));

        findViewById(R.id.wechat_stop).setOnClickListener(view -> controlWeChat(false));
        findViewById(R.id.wechat_start).setOnClickListener(view -> controlWeChat(true));
    }

    /** Stops or starts WeChat. APatch must allow this app to use root. */
    private void controlWeChat(boolean start) {
        String command = start
                ? "am start -n com.tencent.mm/.ui.LauncherUI"
                : "am force-stop com.tencent.mm";
        String done = start ? "已启动微信" : "已停止微信";
        new Thread(() -> {
            String message = done;
            Process process = null;
            try {
                process = new ProcessBuilder("/system/bin/su", "-c", command)
                        .redirectErrorStream(true)
                        .start();
                AtomicBoolean timedOut = new AtomicBoolean();
                Thread watch = armTimeout(process, timedOut);
                ByteArrayOutputStream captured = new ByteArrayOutputStream();
                InputStream stream = process.getInputStream();
                byte[] buffer = new byte[256];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    captured.write(buffer, 0, read);
                }
                int code = process.waitFor();
                watch.interrupt();
                if (timedOut.get()) {
                    message = "操作超时";
                } else if (code != 0) {
                    String detail = captured.toString(StandardCharsets.UTF_8).trim();
                    message = detail.isEmpty() ? "需要在 APatch 里允许使用 root" : detail;
                }
            } catch (Throwable error) {
                message = "需要在 APatch 里允许使用 root";
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
            String toast = message;
            handler.post(() -> Toast.makeText(getApplicationContext(), toast, Toast.LENGTH_SHORT).show());
        }, "wx-wechat").start();
    }

    private static Thread armTimeout(Process process, AtomicBoolean timedOut) {
        Thread watch = new Thread(() -> {
            try {
                Thread.sleep(SU_WAIT_MS);
                timedOut.set(true);
                process.destroyForcibly();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "wx-su-timeout");
        watch.setDaemon(true);
        watch.start();
        return watch;
    }
}
