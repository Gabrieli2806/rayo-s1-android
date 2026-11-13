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
        LinearLayout body = ui.page("Diagnostics", "Live status of this projector. Updates every two seconds.");
        ui.section(body, "SYSTEM");
        LinearLayout row = ui.row(body, 14);
        cpu = ui.stat(row, "PROCESSOR", "-"); memory = ui.stat(row, "MEMORY FREE", "-"); storage = ui.stat(row, "STORAGE FREE", "-");
        row = ui.row(body, 18);
        uptime = ui.stat(row, "ON FOR", "-"); system = ui.stat(row, "SOFTWARE", "-"); battery = ui.stat(row, "BATTERY", "-");
        ui.section(body, "CONNECTIONS");
        row = ui.row(body, 14);
        network = ui.stat(row, "INTERNET", "-"); wifi = ui.stat(row, "WI-FI SIGNAL", "-"); address = ui.stat(row, "IP ADDRESS", "-");
        row = ui.row(body, 18);
        bluetooth = ui.stat(row, "BLUETOOTH", "-"); light = ui.stat(row, "LIGHT SOURCE", "-"); panel = ui.stat(row, "OPTICAL ENGINE", "-");
        ui.section(body, "TESTS");
        LinearLayout actions = ui.row(body, 14);
        View tone = ui.tile(actions, "Speaker test", GabosUi.BLUE_TOP, GabosUi.BLUE_BOTTOM, v -> {
            try { final ToneGenerator generator = new ToneGenerator(AudioManager.STREAM_MUSIC, 60); generator.startTone(ToneGenerator.TONE_PROP_BEEP, 800); handler.postDelayed(generator::release, 1000); }
            catch (RuntimeException e) { record("audio_test_error", e.getClass().getSimpleName()); }
        });
        ui.tile(actions, "Network settings", v -> { startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)); ui.fade(); });
        ui.tile(actions, "Bluetooth settings", v -> { startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); ui.fade(); });
        tone.post(tone::requestFocus);

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

    @Override protected void onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }

    private final Choreographer.FrameCallback frameProbe = new Choreographer.FrameCallback() {
        @Override public void doFrame(long time) { if (!measuringFrames) return; frameCount++; Choreographer.getInstance().postFrameCallback(this); }
    };

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
                cpu.setText(load + "%" + (mhz.isEmpty() ? "" : "  at " + Integer.parseInt(mhz) / 1000 + " MHz"));
            }
            lastIdle = idle; lastTotal = total;
        }
        memory.setText(info.availMem / 1048576 + " of " + info.totalMem / 1048576 + " MiB");
        memory.setTextColor(info.lowMemory ? GabosUi.WARN : GabosUi.INK);
        storage.setText(disk.getAvailableBytes() / 1048576 + " MiB");
        long seconds = SystemClock.elapsedRealtime() / 1000;
        uptime.setText(seconds >= 3600 ? seconds / 3600 + " h " + seconds % 3600 / 60 + " min" : seconds / 60 + " min " + seconds % 60 + " s");
        system.setText("Android " + Build.VERSION.RELEASE);

        String status = read("/sys/class/power_supply/battery/status"), capacity = read("/sys/class/power_supply/battery/capacity");
        battery.setText(capacity.isEmpty() ? "Not present" : capacity + "%  " + status.toLowerCase(Locale.ROOT));

        ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : connectivity.getNetworkCapabilities(active);
        boolean validated = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        network.setText(active == null ? "Offline" : validated ? "Connected" : "No internet");
        network.setTextColor(validated ? GabosUi.INK : GabosUi.WARN);
        String ip = "-";
        LinkProperties link = active == null ? null : connectivity.getLinkProperties(active);
        if (link != null) for (LinkAddress a : link.getLinkAddresses()) if (a.getAddress() instanceof Inet4Address) ip = a.getAddress().getHostAddress();
        address.setText(ip);

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager == null || !wifiManager.isWifiEnabled()) wifi.setText("Off");
        else {
            WifiInfo connection = wifiManager.getConnectionInfo();
            int rssi = connection == null ? -127 : connection.getRssi();
            if (connection == null || connection.getNetworkId() == -1 || rssi <= -127) wifi.setText("Not connected");
            else wifi.setText(WifiManager.calculateSignalLevel(rssi, 5) * 25 + "%  (" + rssi + " dBm)");
        }
        if (adapter == null) bluetooth.setText("Not present");
        else { int s = adapter.getState(); bluetooth.setText(s == BluetoothAdapter.STATE_ON ? "On" : s == BluetoothAdapter.STATE_OFF ? "Off" : "Changing"); }

        String type = read(DLP + "panel_type");
        panel.setText(type.isEmpty() ? "Not detected" : type);
        String currents = read(DLP + "panel_brightness").replaceAll(",$", "");
        String off = read(DLP + "panel_pmu_off");
        light.setText(currents.isEmpty() ? "-" : ("1".equals(off) ? "Off" : "LED " + currents.replace(",", " / ")));
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
