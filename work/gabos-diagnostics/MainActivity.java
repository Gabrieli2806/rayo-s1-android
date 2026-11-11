package local.gabos.diagnostics;

import android.app.Activity;
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
        screen.setTextSize(20); screen.setPadding(32, 32, 32, 32); setContentView(screen);
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
        end(); handler.removeCallbacksAndMessages(null);
        if (registered) unregisterReceiver(receiver);
        super.onDestroy();
    }
}
