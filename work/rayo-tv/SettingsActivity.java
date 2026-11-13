package local.rayo.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
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

/** GabOS Settings: appearance and the projector's own controls, in the Home look. */
public class SettingsActivity extends Activity {
    private static final int[] TIMEOUTS_MIN={5,10,15,30,60,0};
    private static final int[] ACCENT_NAMES={R.string.accent_blue,R.string.accent_cyan,R.string.accent_green,R.string.accent_lime,R.string.accent_yellow,
            R.string.accent_orange,R.string.accent_red,R.string.accent_pink,R.string.accent_purple,R.string.accent_silver};
    private GabosUi ui;
    private View dark;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private int pendingHue;
    private final Runnable applyHue=()->applyTheme(Color.HSVToColor(new float[]{pendingHue,.45f,.97f}),ThemeProvider.light(this),"accentHue");

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);ui=new GabosUi(this);render(null);
    }

    @Override protected void onResume(){super.onResume();if(ui.refreshTheme())render(null);}

    /** Rebuilds the page; focusTag names the control that keeps the focus (after a theme change). */
    private void render(final String focusTag){
        final LinearLayout page=ui.page(getString(R.string.settings_name),getString(R.string.settings_subtitle));
        boolean hw=Projector.available();

        ui.section(page,getString(R.string.section_appearance));
        LinearLayout r=ui.settingRow(page,getString(R.string.mode_title),getString(R.string.mode_summary));
        final boolean light=ThemeProvider.light(this);
        View[] modes=ui.choices(r,new String[]{getString(R.string.mode_dark),getString(R.string.mode_light)},light?1:0,
                i->applyTheme(ThemeProvider.accent(this),i==1,"mode"+i));
        for(int i=0;i<modes.length;i++)modes[i].setTag("mode"+i);
        ui.settingRow(page,getString(R.string.accent_title),getString(R.string.accent_summary));
        {
            LinearLayout swatchRow=ui.row(page,14);swatchRow.setPadding(ui.dp(8),ui.dp(4),0,ui.dp(4));
            int accent=ThemeProvider.accent(this),selected=-1;
            String[] names=new String[GabosUi.ACCENTS.length];
            for(int i=0;i<names.length;i++){names[i]=getString(ACCENT_NAMES[i]);if(GabosUi.ACCENTS[i]==accent)selected=i;}
            View[] swatches=ui.swatches(swatchRow,GabosUi.ACCENTS,names,selected,i->applyTheme(GabosUi.ACCENTS[i],ThemeProvider.light(this),"accent"+i));
            for(int i=0;i<swatches.length;i++)swatches[i].setTag("accent"+i);
            // Any colour: a hue slider beside the presets. Applied after the pointer or D-pad
            // rests, because applying rebuilds the page (and would end a drag in progress).
            float[] hsv=new float[3];Color.colorToHSV(accent,hsv);
            r=ui.settingRow(page,getString(R.string.accent_custom_title),getString(R.string.accent_custom_summary));
            View hue=ui.slider(r,0,355,5,Math.round(hsv[0]/5f)*5%360,v->{pendingHue=v;handler.removeCallbacks(applyHue);handler.postDelayed(applyHue,600);});
            hue.setTag("accentHue");
        }

        ui.section(page,getString(R.string.section_picture));
        if(!hw){ui.settingRow(page,getString(R.string.projector_unavailable),getString(R.string.projector_unavailable_summary));}
        else{
            r=ui.settingRow(page,getString(R.string.brightness_title),getString(R.string.brightness_summary));
            ui.slider(r,10,100,5,Projector.brightness(this),v->Projector.setBrightness(this,v));
            r=ui.settingRow(page,getString(R.string.color_tone_title),getString(R.string.color_tone_summary));
            ui.slider(r,0,7,1,Projector.colorTemperature(this),v->Projector.setColorTemperature(this,v));
            ui.settingRow(page,getString(R.string.placement_title),getString(R.string.placement_summary));
            {
                LinearLayout placement=ui.row(page,14);
                int current=0;
                for(int i=0;i<Projector.PLACEMENT_VALUES.length;i++)if(Projector.PLACEMENT_VALUES[i]==Projector.orientation(this))current=i;
                ui.placements(placement,new String[]{getString(R.string.placement_table),getString(R.string.placement_ceiling),
                                getString(R.string.placement_table_behind),getString(R.string.placement_ceiling_behind)},
                        new boolean[]{false,true,false,true},new boolean[]{false,false,true,true},current,
                        i->Projector.setOrientation(this,Projector.PLACEMENT_VALUES[i]));
            }
            r=ui.settingRow(page,getString(R.string.keystone_title),getString(R.string.keystone_summary));
            ui.slider(r,-40,40,2,Projector.manualKeystone(this),v->Projector.setManualKeystone(this,v));
        }

        ui.section(page,getString(R.string.section_power));
        r=ui.settingRow(page,getString(R.string.inactivity_title),getString(R.string.inactivity_summary));
        int current=Settings.System.getInt(getContentResolver(),Settings.System.SCREEN_OFF_TIMEOUT,900000);
        int selected=current>=Integer.MAX_VALUE/2?TIMEOUTS_MIN.length-1:-1;
        for(int i=0;i<TIMEOUTS_MIN.length-1;i++)if(current==TIMEOUTS_MIN[i]*60000)selected=i;
        ui.choices(r,new String[]{getString(R.string.timeout_5),getString(R.string.timeout_10),getString(R.string.timeout_15),
                getString(R.string.timeout_30),getString(R.string.timeout_60),getString(R.string.timeout_never)},selected,i->{
            int ms=TIMEOUTS_MIN[i]==0?Integer.MAX_VALUE:TIMEOUTS_MIN[i]*60000;
            try{Settings.System.putInt(getContentResolver(),Settings.System.SCREEN_OFF_TIMEOUT,ms);}
            catch(SecurityException e){android.widget.Toast.makeText(this,R.string.timeout_permission,android.widget.Toast.LENGTH_LONG).show();}
        });
        if(hw){
            r=ui.settingRow(page,getString(R.string.light_with_screen_title),getString(R.string.light_with_screen_summary));
            ui.toggle(r,Projector.lightWithScreen(this),v->Projector.setLightWithScreen(this,v==1));
            r=ui.settingRow(page,getString(R.string.audio_only_title),getString(R.string.audio_only_summary));
            ui.tile(r,getString(R.string.audio_only_action),v->lightOff());
        }

        ui.section(page,getString(R.string.section_sound));
        final AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        r=ui.settingRow(page,getString(R.string.volume_title),null);
        ui.slider(r,0,max,1,audio.getStreamVolume(AudioManager.STREAM_MUSIC),v->audio.setStreamVolume(AudioManager.STREAM_MUSIC,v,0));

        ui.section(page,getString(R.string.section_more));
        LinearLayout tiles=ui.row(page,14);
        ui.primaryTile(tiles,getString(R.string.more_wifi),v->open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        ui.tile(tiles,getString(R.string.more_bluetooth),v->open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        ui.tile(tiles,getString(R.string.more_date),v->open(new Intent(Settings.ACTION_DATE_SETTINGS)));
        // Five Home-size tiles are wider than the 1280x720 page at density 192; use two rows.
        tiles=ui.row(page,18);
        ui.tile(tiles,getString(R.string.more_diagnostics),v->open(getPackageManager().getLaunchIntentForPackage("local.gabos.diagnostics")));
        ui.tile(tiles,getString(R.string.more_android),v->open(new Intent(Settings.ACTION_SETTINGS)));

        r=ui.settingRow(page,getString(R.string.setup_again_title),getString(R.string.setup_again_summary));
        ui.tile(r,getString(R.string.setup_again_action),v->open(new Intent(this,SetupActivity.class).putExtra(SetupActivity.EXTRA_AGAIN,true)));

        LinearLayout about=ui.card(page,30);
        about.addView(ui.text(getString(R.string.about_caption),14,GabosUi.DIM));
        TextView version=ui.text(getString(R.string.about_version),24,GabosUi.INK);version.setPadding(0,ui.dp(12),0,0);about.addView(version);
        int battery=((BatteryManager)getSystemService(BATTERY_SERVICE)).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        TextView info=ui.text(Build.DISPLAY+"   ·   Android "+Build.VERSION.RELEASE+"\n"+getString(R.string.about_device,Projector.model())
                +(battery>0?"\n"+getString(R.string.about_battery,battery):""),18,GabosUi.DIM);
        info.setLineSpacing(ui.dp(6),1);info.setPadding(0,ui.dp(10),0,0);about.addView(info);

        page.post(()->{
            View target=focusTag==null?null:page.findViewWithTag(focusTag);
            if(target==null)target=page.findViewWithTag("mode"+(light?1:0));
            if(target!=null)target.requestFocus();
        });
    }

    /** Saves the shared theme, rebuilds this page in it and keeps the focus on the control used. */
    private void applyTheme(int accent,boolean light,String focusTag){
        if(accent==ThemeProvider.accent(this)&&light==ThemeProvider.light(this))return;
        ThemeProvider.save(this,accent,light);
        ui.refreshTheme();render(focusTag);
    }

    private void open(Intent intent){
        try{if(intent==null)throw new ActivityNotFoundException();startActivity(intent);ui.fade();}
        catch(Exception e){android.widget.Toast.makeText(this,R.string.not_available,android.widget.Toast.LENGTH_SHORT).show();}
    }

    private void lightOff(){
        Projector.light(false);
        FrameLayout cover=new FrameLayout(this);cover.setBackgroundColor(0xff000000);cover.setFocusable(true);cover.setClickable(true);
        cover.setOnClickListener(v->lightOn());
        addContentView(cover,new FrameLayout.LayoutParams(-1,-1));cover.requestFocus();dark=cover;
    }
    private void lightOn(){
        if(dark==null)return;
        Projector.light(true);((android.view.ViewGroup)dark.getParent()).removeView(dark);dark=null;render(null);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(dark!=null){if(event.getAction()==KeyEvent.ACTION_UP)lightOn();return true;}
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onPause(){if(dark!=null)lightOn();super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public void onBackPressed(){super.onBackPressed();ui.fade();}
}
