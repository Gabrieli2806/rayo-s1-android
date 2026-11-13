package local.gabos.diagnostics;

import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;
import android.widget.*;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;
import local.gabos.ui.GabosUi;

/**
 * GabOS Diagnostics: live system, radio and projector status in the Home look.
 * The JSON record and the "test" extras are used by the host validation tools.
 * Never records network names, credentials or nearby device identities.
 */
public class MainActivity extends Activity {
    private static final String DLP="/sys/devices/20072000.i2c/i2c-0/0-001b/";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONObject result = new JSONObject();
    private GabosUi ui;
    private TextView codecReport;
    private String codecSummary;
    private boolean renderPending;
    private boolean codecRunning;
    private TextView cpu, memory, storage, uptime, network, address, wifi, bluetooth, battery, light, panel, system;
    private BluetoothAdapter adapter;
    private int found, frameCount;
    private long frameStart, lastIdle, lastTotal;
    private boolean measuringFrames, registered, requested, finished;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) { found++; record("devices_found", found); }
            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) record("discovery_started", true);
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) record("discovery_finished", true);
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) record("bluetooth_state", intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
        }
    };
    private final Runnable refresh = new Runnable() { public void run() { systemInfo(); handler.postDelayed(this, 2000); } };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new GabosUi(this);
        ui.onThemeChange(this::themeChanged);
        render();
        startTests();
    }

    /** Builds the page in the current GabOS theme; called again when the theme changes. */
    private void render() {
        LinearLayout body = ui.page(getString(R.string.title), getString(R.string.subtitle));
        ui.section(body, getString(R.string.section_system));
        LinearLayout row = ui.row(body, 14);
        cpu = ui.stat(row, getString(R.string.cap_processor), "-"); memory = ui.stat(row, getString(R.string.cap_memory), "-"); storage = ui.stat(row, getString(R.string.cap_storage), "-");
        row = ui.row(body, 18);
        uptime = ui.stat(row, getString(R.string.cap_uptime), "-"); system = ui.stat(row, getString(R.string.cap_software), "-"); battery = ui.stat(row, getString(R.string.cap_battery), "-");
        ui.section(body, getString(R.string.section_connections));
        row = ui.row(body, 14);
        network = ui.stat(row, getString(R.string.cap_internet), "-"); wifi = ui.stat(row, getString(R.string.cap_wifi), "-"); address = ui.stat(row, getString(R.string.cap_ip), "-");
        row = ui.row(body, 18);
        bluetooth = ui.stat(row, getString(R.string.cap_bluetooth), "-"); light = ui.stat(row, getString(R.string.cap_light), "-"); panel = ui.stat(row, getString(R.string.cap_engine), "-");
        ui.section(body, getString(R.string.section_tests));
        LinearLayout actions = ui.row(body, 14);
        View tone = ui.primaryTile(actions, getString(R.string.test_speaker), v -> {
            try { final ToneGenerator generator = new ToneGenerator(AudioManager.STREAM_MUSIC, 60); generator.startTone(ToneGenerator.TONE_PROP_BEEP, 800); handler.postDelayed(generator::release, 1000); }
            catch (RuntimeException e) { record("audio_test_error", e.getClass().getSimpleName()); }
        });
        ui.tile(actions, getString(R.string.test_network), v -> { startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)); ui.fade(); });
        ui.tile(actions, getString(R.string.test_bluetooth), v -> { startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); ui.fade(); });
        ui.tile(actions, getString(R.string.test_decoders), v -> codecTest());
        LinearLayout report = ui.card(body, 22);
        report.addView(ui.text(getString(R.string.cap_decoders), 14, GabosUi.DIM));
        codecReport = ui.text(codecSummary != null ? codecSummary : getString(codecRunning ? R.string.codec_running : R.string.codec_idle), 17, GabosUi.INK);
        codecReport.setPadding(0, ui.dp(12), 0, 0); codecReport.setLineSpacing(ui.dp(5), 1); report.addView(codecReport);
        tone.post(tone::requestFocus);
        if (lastTotal > 0) systemInfo();
    }

    /** A running decoder test owns a surface in this window; rebuild only after it ends. */
    private void themeChanged() { if (codecRunning) renderPending = true; else render(); }

    private void startTests() {
        if ("codecs".equals(getIntent().getStringExtra("test"))) handler.postDelayed(this::codecTest, 3000);
        if ("frames".equals(getIntent().getStringExtra("test"))) {
            // Exclude service discovery and the expensive first window draw from steady-state callbacks.
            handler.postDelayed(() -> {
                frameStart = SystemClock.elapsedRealtime(); measuringFrames = true;
                Choreographer.getInstance().postFrameCallback(frameProbe);
                handler.postDelayed(() -> { measuringFrames = false; record("frame_callbacks_5s", frameCount); record("frame_elapsed_ms", SystemClock.elapsedRealtime() - frameStart); }, 5000);
            }, 15000);
        }
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            record("wifi_enabled", wifiManager != null && wifiManager.isWifiEnabled());
            adapter = BluetoothAdapter.getDefaultAdapter();
            record("bluetooth_present", adapter != null);
            if (adapter != null) record("bluetooth_state", adapter.getState());
            if (adapter != null && "bluetooth".equals(getIntent().getStringExtra("test"))) {
                IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
                filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
                filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                filter.addAction(BluetoothDevice.ACTION_FOUND);
                registerReceiver(receiver, filter); registered = true;
                if (!adapter.isEnabled()) record("enable_requested", adapter.enable());
                handler.post(poll);
                handler.postDelayed(this::end, 30000);
            }
        } catch (Exception e) { record("error", e.getClass().getSimpleName() + ": " + e.getMessage()); }
    }

    @Override protected void onResume() { super.onResume(); if (ui.refreshTheme()) themeChanged(); handler.removeCallbacks(refresh); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }

    private final Choreographer.FrameCallback frameProbe = new Choreographer.FrameCallback() {
        @Override public void doFrame(long time) { if (!measuringFrames) return; frameCount++; Choreographer.getInstance().postFrameCallback(this); }
    };

    /** Full-screen surface for real decoder output while the codec test runs. */
    private void codecTest() {
        if (codecRunning) return;
        codecRunning = true; codecReport.setText(R.string.codec_running);
        final android.view.SurfaceView surface = new android.view.SurfaceView(this);
        final android.widget.FrameLayout root = (android.widget.FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);
        root.addView(surface, new android.widget.FrameLayout.LayoutParams(-1, -1));
        surface.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            @Override public void surfaceCreated(android.view.SurfaceHolder holder) {
                new Thread(() -> {
                    org.json.JSONArray results = CodecTest.run(getCacheDir(), holder.getSurface());
                    record("codec_results", results);
                    final String summary = summarize(MainActivity.this, results);
                    handler.post(() -> { root.removeView(surface); codecSummary = summary; codecReport.setText(summary); codecRunning = false; if (renderPending) { renderPending = false; render(); } });
                }, "codec-test").start();
            }
            @Override public void surfaceChanged(android.view.SurfaceHolder holder, int f, int w, int h) { }
            @Override public void surfaceDestroyed(android.view.SurfaceHolder holder) { }
        });
    }

    /** Readable summary of the codec test in the interface language; the JSON record stays English. */
    private static String summarize(Context c, org.json.JSONArray results) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < results.length(); i++) {
            org.json.JSONObject entry = results.optJSONObject(i);
            if (entry == null) continue;
            String format = entry.optString("mime").replace("video/", "").toUpperCase(Locale.ROOT);
            org.json.JSONArray decoders = entry.optJSONArray("decoders");
            StringBuilder outcome = new StringBuilder();
            if (decoders == null) outcome.append(codecError(c, entry.optString("error", "")));
            else if (decoders.length() == 0) outcome.append(c.getString(R.string.codec_none));
            else for (int d = 0; d < decoders.length(); d++) {
                org.json.JSONObject r = decoders.optJSONObject(d);
                if (r == null) continue;
                String name = r.optString("decoder");
                boolean hw = !(name.startsWith("c2.android.") || name.startsWith("OMX.google."));
                String detail = r.has("error") ? c.getString(R.string.codec_failed)
                        : c.getString(R.string.codec_frames, r.optInt("frames_out"), r.optInt("frames_in"), r.optDouble("fps"));
                if (outcome.length() > 0) outcome.append(",  ");
                outcome.append(c.getString(hw ? R.string.codec_hardware : R.string.codec_software, detail));
            }
            text.append(c.getString(R.string.codec_line, format, entry.optString("size"), outcome)).append('\n');
        }
        return text.toString().trim();
    }

    /** Maps the English error text kept in the JSON record to interface text. */
    private static String codecError(Context c, String error) {
        if (error.isEmpty()) return c.getString(R.string.codec_not_tested);
        if (error.startsWith("no software encoder")) return c.getString(R.string.codec_no_encoder);
        int colon = error.indexOf(':');
        return c.getString(R.string.codec_error, colon > 0 ? error.substring(0, colon) : error);
    }

    /** The kernel reports the battery status in English; show it in the interface language. */
    private String batteryStatus(String status) {
        switch (status.toLowerCase(Locale.ROOT)) {
            case "charging": return getString(R.string.battery_charging);
            case "discharging": return getString(R.string.battery_discharging);
            case "full": return getString(R.string.battery_full);
            case "not charging": return getString(R.string.battery_not_charging);
            default: return getString(R.string.battery_unknown);
        }
    }

    private static String read(String path) {
        try (FileInputStream in = new FileInputStream(path)) { byte[] b = new byte[512]; int n = in.read(b); return n > 0 ? new String(b, 0, n, StandardCharsets.US_ASCII).trim() : ""; }
        catch (Exception e) { return ""; }
    }

    private void systemInfo() {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(info);
        StatFs disk = new StatFs(getFilesDir().getPath());
        record("android_version", Build.VERSION.RELEASE); record("sdk", Build.VERSION.SDK_INT);
        record("ram_total_mib", info.totalMem / 1048576); record("ram_available_mib", info.availMem / 1048576);
        record("data_available_mib", disk.getAvailableBytes() / 1048576); record("low_memory", info.lowMemory);

        // Processor load from /proc/stat deltas, plus the current clock.
        String[] stat = read("/proc/stat").split("\n")[0].trim().split("\\s+");
        if (stat.length > 5) {
            long total = 0; for (int i = 1; i < stat.length; i++) total += Long.parseLong(stat[i]);
            long idle = Long.parseLong(stat[4]) + Long.parseLong(stat[5]);
            if (lastTotal > 0 && total > lastTotal) {
                int load = (int) (100 * (1 - (idle - lastIdle) / (double) (total - lastTotal)));
                String mhz = read("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq");
                cpu.setText(mhz.isEmpty() ? getString(R.string.cpu_value, load) : getString(R.string.cpu_value_clock, load, Integer.parseInt(mhz) / 1000));
            }
            lastIdle = idle; lastTotal = total;
        }
        memory.setText(getString(R.string.memory_value, (int) (info.availMem / 1048576), (int) (info.totalMem / 1048576)));
        memory.setTextColor(info.lowMemory ? GabosUi.WARN : GabosUi.INK);
        storage.setText(getString(R.string.storage_value, (int) (disk.getAvailableBytes() / 1048576)));
        long seconds = SystemClock.elapsedRealtime() / 1000;
        uptime.setText(seconds >= 3600 ? getString(R.string.uptime_hours, (int) (seconds / 3600), (int) (seconds % 3600 / 60)) : getString(R.string.uptime_minutes, (int) (seconds / 60), (int) (seconds % 60)));
        system.setText(getString(R.string.software_value, Build.VERSION.RELEASE));

        String status = read("/sys/class/power_supply/battery/status"), capacity = read("/sys/class/power_supply/battery/capacity");
        battery.setText(capacity.isEmpty() ? getString(R.string.not_present) : getString(R.string.battery_value, capacity, batteryStatus(status)));

        ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : connectivity.getNetworkCapabilities(active);
        boolean validated = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        network.setText(active == null ? R.string.net_offline : validated ? R.string.net_connected : R.string.net_no_internet);
        network.setTextColor(validated ? GabosUi.INK : GabosUi.WARN);
        String ip = "-";
        LinkProperties link = active == null ? null : connectivity.getLinkProperties(active);
        if (link != null) for (LinkAddress a : link.getLinkAddresses()) if (a.getAddress() instanceof Inet4Address) ip = a.getAddress().getHostAddress();
        address.setText(ip);

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager == null || !wifiManager.isWifiEnabled()) wifi.setText(R.string.state_off);
        else {
            WifiInfo connection = wifiManager.getConnectionInfo();
            int rssi = connection == null ? -127 : connection.getRssi();
            if (connection == null || connection.getNetworkId() == -1 || rssi <= -127) wifi.setText(R.string.not_connected);
            else wifi.setText(getString(R.string.wifi_value, WifiManager.calculateSignalLevel(rssi, 5) * 25, rssi));
        }
        if (adapter == null) bluetooth.setText(R.string.not_present);
        else { int s = adapter.getState(); bluetooth.setText(s == BluetoothAdapter.STATE_ON ? R.string.state_on : s == BluetoothAdapter.STATE_OFF ? R.string.state_off : R.string.state_changing); }

        String type = read(DLP + "panel_type");
        panel.setText(type.isEmpty() ? getString(R.string.not_detected) : type);
        String currents = read(DLP + "panel_brightness").replaceAll(",$", "");
        String off = read(DLP + "panel_pmu_off");
        light.setText(currents.isEmpty() ? "-" : "1".equals(off) ? getString(R.string.state_off) : getString(R.string.light_value, currents.replace(",", " / ")));
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (finished) return;
            try {
                record("bluetooth_state", adapter.getState());
                if (adapter.isEnabled() && !requested) { requested = true; record("discovery_requested", adapter.startDiscovery()); }
            } catch (Exception e) { record("error", e.getClass().getSimpleName()); }
            handler.postDelayed(this, 1000);
        }
    };

    private void end() {
        if (finished) return;
        finished = true;
        try { if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery(); }
        catch (Exception e) { record("cancel_error", e.getClass().getSimpleName()); }
        record("devices_found", found); record("finished", true);
    }

    private void record(String key, Object value) {
        try {
            result.put(key, value);
            byte[] data = result.toString(2).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream stream = openFileOutput("radio-result.json", MODE_PRIVATE)) { stream.write(data); }
            Log.i("GabOSRadioProbe", key + "=" + value);
        } catch (Exception e) { Log.e("GabOSRadioProbe", "Could not write diagnostic result", e); }
    }

    @Override public void onBackPressed() { super.onBackPressed(); ui.fade(); }
    @Override protected void onDestroy() {
        measuringFrames = false; Choreographer.getInstance().removeFrameCallback(frameProbe);
        end(); handler.removeCallbacksAndMessages(null);
        if (registered) unregisterReceiver(receiver);
        super.onDestroy();
    }
}
