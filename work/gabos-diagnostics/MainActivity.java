package local.gabos.diagnostics;

import android.app.Activity;
import android.view.Choreographer;
import android.app.ActivityManager;
import android.os.StatFs;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.widget.*;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.*;
import android.util.Log;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.json.JSONObject;
import local.gabos.ui.GabosUi;

/** Development-only radio checks. Never record network credentials or nearby identities. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private final JSONObject result = new JSONObject();
    private TextView screen, ram, storage, platform, wifi, bluetooth;
    private GabosUi ui;
    private BluetoothAdapter adapter;
    private int found;
    private int frameCount;
    private long frameStart;
    private boolean measuringFrames;
    private boolean registered, requested, finished;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) { found++; record("devices_found", found); }
            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) record("discovery_started", true);
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) record("discovery_finished", true);
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) record("bluetooth_state", intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new GabosUi(this);
        LinearLayout body = ui.page("Diagnostics", "Hardware and system status of this projector.");
        LinearLayout stats = ui.row(body, 26);
        ram = ui.stat(stats, "MEMORY FREE", "-"); storage = ui.stat(stats, "STORAGE FREE", "-");
        platform = ui.stat(stats, "ANDROID", "-");
        LinearLayout radios = ui.row(body, 18);
        wifi = ui.stat(radios, "WI-FI", "-"); bluetooth = ui.stat(radios, "BLUETOOTH", "-");
        LinearLayout actions = ui.row(body, 26);
        android.view.View refresh = ui.tile(actions, "Refresh", GabosUi.BLUE_TOP, GabosUi.BLUE_BOTTOM, v->systemInfo());
        ui.tile(actions, "Network", v->{startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));ui.fade();});
        ui.tile(actions, "Speaker test", v->{
            try { final ToneGenerator generator=new ToneGenerator(AudioManager.STREAM_MUSIC,25);generator.startTone(ToneGenerator.TONE_PROP_BEEP,1000);handler.postDelayed(()->generator.release(),1200); }
            catch(RuntimeException e){record("audio_test_error",e.getClass().getSimpleName());}
        });
        LinearLayout card = ui.card(body, 34);
        card.addView(ui.text("DETAILS", 12, GabosUi.DIM));
        screen = ui.text("", 14, GabosUi.INK); screen.setPadding(0, ui.dp(12), 0, 0); screen.setLineSpacing(ui.dp(4), 1); card.addView(screen);
        refresh.post(refresh::requestFocus);
        systemInfo();
        if ("frames".equals(getIntent().getStringExtra("test"))) {
            // Exclude service discovery and the expensive first window draw from steady-state callbacks.
            handler.postDelayed(()->{
                frameStart=SystemClock.elapsedRealtime(); measuringFrames=true;
                Choreographer.getInstance().postFrameCallback(frameProbe);
                handler.postDelayed(()->{measuringFrames=false;record("frame_callbacks_5s",frameCount);record("frame_elapsed_ms",SystemClock.elapsedRealtime()-frameStart);},5000);
            },15000);
        }
        try {
            WifiManager wifi = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            record("wifi_enabled", wifi != null && wifi.isWifiEnabled());
            adapter = BluetoothAdapter.getDefaultAdapter();
            record("bluetooth_present", adapter != null);
            if (adapter == null) { record("finished", true); return; }
            record("bluetooth_state", adapter.getState());
            if (!"bluetooth".equals(getIntent().getStringExtra("test"))) return;
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            registerReceiver(receiver, filter); registered = true;
            if (!adapter.isEnabled()) record("enable_requested", adapter.enable());
            handler.post(poll);
            handler.postDelayed(() -> end(), 30000);
        } catch (Exception e) { record("error", e.getClass().getSimpleName()+": "+e.getMessage()); }
    }
    private final Choreographer.FrameCallback frameProbe=new Choreographer.FrameCallback(){
        @Override public void doFrame(long time){if(!measuringFrames)return;frameCount++;Choreographer.getInstance().postFrameCallback(this);}
    };
    private void systemInfo() {
        ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
        ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(info);
        StatFs storage=new StatFs(getFilesDir().getPath());
        record("android_version",Build.VERSION.RELEASE);record("sdk",Build.VERSION.SDK_INT);
        record("ram_total_mib",info.totalMem/1048576);record("ram_available_mib",info.availMem/1048576);
        record("data_available_mib",storage.getAvailableBytes()/1048576);record("low_memory",info.lowMemory);
    }
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (finished) return;
            try {
                record("bluetooth_state", adapter.getState());
                if (adapter.isEnabled() && !requested) {
                    requested = true; record("discovery_requested", adapter.startDiscovery());
                }
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
            render();
            Log.i("GabOSRadioProbe", key+"="+value);
        } catch (Exception e) { Log.e("GabOSRadioProbe", "Could not write diagnostic result", e); }
    }
    private void render() {
        if (screen == null) return;
        if (result.has("ram_available_mib")) ram.setText(result.optLong("ram_available_mib")+" / "+result.optLong("ram_total_mib")+" MiB");
        if (result.has("data_available_mib")) storage.setText(result.optLong("data_available_mib")+" MiB");
        if (result.has("android_version")) platform.setText(result.optString("android_version")+"  -  API "+result.optInt("sdk"));
        if (result.has("wifi_enabled")) wifi.setText(result.optBoolean("wifi_enabled")?"On":"Off");
        if (result.has("bluetooth_present")) {
            int state=result.optInt("bluetooth_state",-1);
            bluetooth.setText(!result.optBoolean("bluetooth_present")?"Not present":state==BluetoothAdapter.STATE_ON?"On":state==BluetoothAdapter.STATE_OFF?"Off":"Changing");
        }
        StringBuilder lines=new StringBuilder();
        for (Iterator<String> keys=result.keys(); keys.hasNext();) {
            String key=keys.next();
            lines.append(key.replace('_',' ')).append(":  ").append(result.opt(key)).append('\n');
        }
        screen.setText(lines.toString().trim());
    }
    @Override public void onBackPressed() { super.onBackPressed(); ui.fade(); }
    @Override protected void onDestroy() {
        measuringFrames=false; Choreographer.getInstance().removeFrameCallback(frameProbe);
        end(); handler.removeCallbacksAndMessages(null);
        if (registered) unregisterReceiver(receiver);
        super.onDestroy();
    }
}
