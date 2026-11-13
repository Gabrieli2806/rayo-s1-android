package local.rayo.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import local.gabos.ui.GabosUi;

/** GabOS Settings: the projector's own controls, in the Home look. */
public class SettingsActivity extends Activity {
    private static final int[] TIMEOUTS_MIN={5,10,15,30,60,0};
    private GabosUi ui;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private View dark;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);ui=new GabosUi(this);render();
    }

    private void render(){
        LinearLayout page=ui.page("GabOS Settings","Projector, light and sound. Network, apps and accounts are in Android settings.");
        boolean hw=Projector.available();

        ui.section(page,"PICTURE");
        if(!hw){ui.settingRow(page,"Projector controls unavailable","The optical engine driver did not respond.");}
        else{
            LinearLayout r=ui.settingRow(page,"Brightness","Lower values are quieter and cooler, and use less battery.");
            View first=ui.slider(r,10,100,5,Projector.brightness(this),v->Projector.setBrightness(this,v));
            first.post(first::requestFocus);
            r=ui.settingRow(page,"Color tone","0 is the factory setting. Higher values change the white balance.");
            ui.slider(r,0,7,1,Projector.colorTemperature(this),v->Projector.setColorTemperature(this,v));
            r=ui.settingRow(page,"Automatic orientation","Flips the image using the tilt sensor.");
            ui.toggle(r,Projector.autoOrientation(this),v->{Projector.setAutoOrientation(this,v==1);render();});
            if(!Projector.autoOrientation(this)){
                r=ui.settingRow(page,"Orientation","Choose how the projector is placed. Position 3 is the factory default.");
                ui.choices(r,new String[]{"1","2","3","4"},Projector.orientation(this)-1,v->Projector.setOrientation(this,v+1));
            }
            r=ui.settingRow(page,"Automatic keystone","Straightens the image when the projector is tilted.");
            ui.toggle(r,Projector.autoKeystone(this),v->Projector.setAutoKeystone(this,v==1));
        }

        ui.section(page,"POWER AND LIGHT");
        LinearLayout r=ui.settingRow(page,"Turn off after inactivity","The screen and the light turn off when nothing is used for this long.");
        int current=Settings.System.getInt(getContentResolver(),Settings.System.SCREEN_OFF_TIMEOUT,900000);
        int selected=current>=Integer.MAX_VALUE/2?TIMEOUTS_MIN.length-1:-1;
        for(int i=0;i<TIMEOUTS_MIN.length-1;i++)if(current==TIMEOUTS_MIN[i]*60000)selected=i;
        ui.choices(r,new String[]{"5 min","10 min","15 min","30 min","1 h","Never"},selected,i->{
            int ms=TIMEOUTS_MIN[i]==0?Integer.MAX_VALUE:TIMEOUTS_MIN[i]*60000;
            try{Settings.System.putInt(getContentResolver(),Settings.System.SCREEN_OFF_TIMEOUT,ms);}
            catch(SecurityException e){android.widget.Toast.makeText(this,"Permission to change the timeout is missing",android.widget.Toast.LENGTH_LONG).show();}
        });
        if(hw){
            r=ui.settingRow(page,"Light off with the screen","Turns the lamp off completely while the screen is off.");
            ui.toggle(r,Projector.lightWithScreen(this),v->Projector.setLightWithScreen(this,v==1));
            r=ui.settingRow(page,"Audio only","Turns the light off now and keeps playing sound. Press any key to turn it back on.");
            ui.tile(r,"Light off",v->lightOff());
        }

        ui.section(page,"SOUND");
        final AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        r=ui.settingRow(page,"Volume",null);
        ui.slider(r,0,max,1,audio.getStreamVolume(AudioManager.STREAM_MUSIC),v->audio.setStreamVolume(AudioManager.STREAM_MUSIC,v,0));
        r=ui.settingRow(page,"Prevent speaker pop","Keeps the speaker ready while the screen is on so sounds start cleanly.");
        ui.toggle(r,Projector.antiPop(this),v->{Projector.setAntiPop(this,v==1);SystemService.refresh(this);});

        ui.section(page,"MORE");
        LinearLayout tiles=ui.row(page,14);
        ui.tile(tiles,"Wi-Fi",GabosUi.BLUE_TOP,GabosUi.BLUE_BOTTOM,v->open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        ui.tile(tiles,"Bluetooth",v->open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        ui.tile(tiles,"Date and time",v->open(new Intent(Settings.ACTION_DATE_SETTINGS)));
        ui.tile(tiles,"Diagnostics",v->open(getPackageManager().getLaunchIntentForPackage("local.gabos.diagnostics")));
        ui.tile(tiles,"Android settings",v->open(new Intent(Settings.ACTION_SETTINGS)));

        LinearLayout about=ui.card(page,30);
        about.addView(ui.text("ABOUT",14,GabosUi.DIM));
        int battery=((BatteryManager)getSystemService(BATTERY_SERVICE)).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        TextView info=ui.text(Build.DISPLAY+"   -   Android "+Build.VERSION.RELEASE+"\nCanon Rayo S1   -   optical engine "+Projector.model()
                +(battery>0?"\nBattery "+battery+"%":""),18,GabosUi.INK);
        info.setLineSpacing(ui.dp(6),1);info.setPadding(0,ui.dp(12),0,0);about.addView(info);
    }

    private void open(Intent intent){
        try{if(intent==null)throw new ActivityNotFoundException();startActivity(intent);ui.fade();}
        catch(Exception e){android.widget.Toast.makeText(this,"Not available",android.widget.Toast.LENGTH_SHORT).show();}
    }

    private void lightOff(){
        Projector.light(false);
        FrameLayout cover=new FrameLayout(this);cover.setBackgroundColor(0xff000000);cover.setFocusable(true);cover.setClickable(true);
        cover.setOnClickListener(v->lightOn());
        addContentView(cover,new FrameLayout.LayoutParams(-1,-1));cover.requestFocus();dark=cover;
    }
    private void lightOn(){
        if(dark==null)return;
        Projector.light(true);((android.view.ViewGroup)dark.getParent()).removeView(dark);dark=null;render();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(dark!=null){if(event.getAction()==KeyEvent.ACTION_UP)lightOn();return true;}
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onPause(){if(dark!=null)lightOn();super.onPause();}
    @Override public void onBackPressed(){super.onBackPressed();ui.fade();}
}
