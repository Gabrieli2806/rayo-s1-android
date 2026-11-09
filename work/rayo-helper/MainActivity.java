package local.rayo.inspector;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.content.Intent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

public class MainActivity extends Activity {
    public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 12, 16, 12);
        scroll.addView(layout);
        TextView info = new TextView(this);
        info.setTextSize(18);
        info.setText("Rayo Inspector\nAndroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
            + "\nModelo: " + Build.MODEL + "\nPlaca: " + Build.BOARD
            + "\nHardware: " + Build.HARDWARE + "\nDispositivo: " + Build.DEVICE
            + "\nCompilacion: " + Build.DISPLAY + "\nTipo: " + Build.TYPE
            + "\n\nEsta app muestra datos y abre ajustes. No activa depuracion automaticamente.\n");
        layout.addView(info);
        addButton(layout, "Abrir opciones de desarrollador", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", null);
        addButton(layout, "Abrir desarrollador por acceso directo", null, "com.android.settings.Settings$DevelopmentSettingsActivity");
        addButton(layout, "Abrir configuracion USB", "android.settings.STORAGE_USB_SETTINGS", null);
        addButton(layout, "Abrir ajustes Android", "android.settings.SETTINGS", null);
        setContentView(scroll);
    }
    private void addButton(LinearLayout parent, String label, final String action, final String component) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                Intent intent = action == null ? new Intent() : new Intent(action);
                if (component != null) intent.setClassName("com.android.settings", component);
                try { startActivity(intent); }
                catch (Exception exception) { Toast.makeText(MainActivity.this, "No se pudo abrir: " + exception.getClass().getSimpleName(), Toast.LENGTH_LONG).show(); }
            }
        });
        parent.addView(button);
    }
}
