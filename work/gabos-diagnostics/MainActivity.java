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
import org.json.JSONObject;

/** Development-only radio checks. Never record network credentials or nearby identities. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private final JSONObject result = new JSONObject();
    private TextView screen;
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
        screen = new TextView(this); screen.setTextColor(Color.WHITE); screen.setBackgroundColor(0xff10151d);
        screen.setTextSize(18); screen.setPadding(24, 16, 24, 16);
        LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setBackgroundColor(0xff10151d);
        body.addView(screen);
        Button refresh=new Button(this);refresh.setText("Refresh system information");body.addView(refresh);refresh.setOnClickListener(v->systemInfo());
        Button radios=new Button(this);radios.setText("Wi-Fi and Bluetooth settings");body.addView(radios);radios.setOnClickListener(v->startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)));
        Button tone=new Button(this);tone.setText("Play a short speaker test");body.addView(tone);tone.setOnClickListener(v->{
            try { final ToneGenerator generator=new ToneGenerator(AudioManager.STREAM_MUSIC,25);generator.startTone(ToneGenerator.TONE_PROP_BEEP,1000);handler.postDelayed(()->generator.release(),1200); }
            catch(RuntimeException e){record("audio_test_error",e.getClass().getSimpleName());}
        });
        ScrollView scroll=new ScrollView(this);scroll.addView(body);setContentView(scroll); systemInfo();
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
            screen.setText("GabOS Diagnostics\n\n"+result.toString(2));
            Log.i("GabOSRadioProbe", key+"="+value);
        } catch (Exception e) { Log.e("GabOSRadioProbe", "Could not write diagnostic result", e); }
    }
    @Override protected void onDestroy() {
        measuringFrames=false; Choreographer.getInstance().removeFrameCallback(frameProbe);
        end(); handler.removeCallbacksAndMessages(null);
        if (registered) unregisterReceiver(receiver);
        super.onDestroy();
    }
}
