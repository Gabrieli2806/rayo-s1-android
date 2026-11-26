package local.rayo.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.LocaleList;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import local.gabos.ui.GabosUi;

/**
 * First-boot setup: welcome, language, Wi-Fi, look and a short summary.
 * Home opens it once (until "done" is stored in the "setup" preferences);
 * GabOS Settings can open it again. Every step works with the touch panel
 * pointer and with the D-pad; Back returns to the previous step.
 *
 * The language is applied through the hidden LocalePicker, which needs
 * CHANGE_CONFIGURATION (granted to this package by the GabOS boot script).
 * Locale changes are handled in place (configChanges) so the wizard keeps its
 * step. Wi-Fi uses the legacy WifiManager network API, still open to apps
 * that target API 25.
 */
public class SetupActivity extends Activity {
    public static final String EXTRA_AGAIN="local.gabos.extra.SETUP_AGAIN";
    /** Opens one page ("wifi" or "language") on its own from GabOS Settings; leaving it returns there. */
    public static final String EXTRA_PAGE="local.gabos.extra.SETUP_PAGE";
    private static final String TAG="GabOSSetup", PREFS="setup";
    private static final int WELCOME=0, LANGUAGE=1, WIFI=2, LOOK=3, DONE=4, STEPS=5;
    private static final int IDLE=0, CONNECTING=1, CONNECTED=2, FAILED=3;
    private static final int[] ACCENT_NAMES={R.string.accent_blue,R.string.accent_cyan,R.string.accent_green,R.string.accent_lime,R.string.accent_yellow,
            R.string.accent_orange,R.string.accent_red,R.string.accent_pink,R.string.accent_purple,R.string.accent_silver};
    private static final List<String> SPANISH_REGIONS=Arrays.asList("ES","MX","AR","CO","CL","PE","VE","EC","GT","CU","BO","DO","HN","PY","SV","NI","CR","PR","PA","UY","US");
    private static final List<String> ENGLISH_REGIONS=Arrays.asList("US","GB","CA","AU","NZ","IE","IN","ZA","SG");
    private static final long RESCAN_MS=20000, CONNECT_TIMEOUT_MS=45000;

    private final Handler handler=new Handler(Looper.getMainLooper());
    private GabosUi ui;
    private int step=WELCOME;
    private int single=-1;
    private boolean again, wifiReceiverOn, permissionAsked, resumed;
    private TextView primary;

    // Wi-Fi step state.
    private WifiManager wifi;
    private LinearLayout listBox;
    private TextView wifiStatus;
    private ProgressBar spinner;
    private ScanResult passwordFor;
    private EditText passwordField;
    private TextView passwordError;
    private int linkState=IDLE, linkNetId=-1;
    private String shownLocale;
    private String linkSsid, linkMessage;

    static boolean done(Context c){return c.getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("done",false);}
    private static void markDone(Context c){c.getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("done",true).commit();}

    private final BroadcastReceiver wifiReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            String action=intent.getAction();
            if(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)){refreshNetworks();}
            else if(WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)){
                if(wifi.isWifiEnabled())startScan();
                refreshNetworks();
            }else if(WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)){
                NetworkInfo info=intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if(info!=null&&info.isConnected()&&linkState==CONNECTING&&joinedTarget())setLink(CONNECTED,null);
                else updateWifiStatus();
            }else if(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(action)){
                if(linkState==CONNECTING&&intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR,-1)==WifiManager.ERROR_AUTHENTICATING)
                    fail(getString(R.string.setup_wifi_wrong_password,linkSsid));
            }
        }
    };
    private final Runnable rescan=new Runnable(){public void run(){
        if(step!=WIFI||!resumed)return;
        if(passwordFor==null&&linkState!=CONNECTING)startScan();
        handler.postDelayed(this,RESCAN_MS);
    }};
    private final Runnable connectTimeout=new Runnable(){public void run(){
        if(linkState!=CONNECTING)return;
        // The Wi-Fi receiver is off once the user leaves the Wi-Fi step, so the success
        // broadcast may have been missed: never remove a network that did connect.
        if(wifiConnected()&&joinedTarget()){setLink(CONNECTED,null);return;}
        fail(getString(R.string.setup_wifi_failed,linkSsid));
    }};
    private final Runnable localeFallback=new Runnable(){public void run(){render(null);}};

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        ui=new GabosUi(this);
        wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        again=getIntent().getBooleanExtra(EXTRA_AGAIN,false);
        String page=getIntent().getStringExtra(EXTRA_PAGE);
        single="wifi".equals(page)?WIFI:"language".equals(page)?LANGUAGE:-1;
        if(single>=0){again=true;step=single;}
        else if(state!=null)step=Math.max(WELCOME,Math.min(DONE,state.getInt("step",WELCOME)));
        // The password field sits near the top; pan instead of resizing under the keyboard.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN|WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        render(null);
    }

    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("step",step);}

    @Override protected void onResume(){
        super.onResume();resumed=true;
        if(ui.refreshTheme())render(null);
        else if(step==WIFI)enterWifi();
    }

    @Override protected void onPause(){
        resumed=false;handler.removeCallbacks(rescan);stopWifiReceiver();
        super.onPause();
    }

    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}

    /**
     * Language changes arrive here (configChanges) so the wizard keeps its step.
     * Other handled changes (keyboard, pointer) keep the page, and any typed password.
     */
    @Override public void onConfigurationChanged(Configuration config){
        super.onConfigurationChanged(config);
        if(currentLocale().toLanguageTag().equals(shownLocale))return;
        handler.removeCallbacks(localeFallback);
        render(null);
    }

    @Override public void onBackPressed(){
        if(step==WIFI&&passwordFor!=null){passwordFor=null;render(null);return;}
        if(single>=0){go(-1);return;}
        if(step>WELCOME){go(step-1);return;}
        // First boot: setup is left with "Skip setup" or Home; a rerun can simply close.
        if(again){finish();ui.fade();}
    }

    private int dp(float v){return ui.dp(v);}

    private void go(int next){
        if(step==WIFI&&next!=WIFI){passwordFor=null;handler.removeCallbacks(rescan);stopWifiReceiver();}
        if(single>=0&&next!=single){finish();ui.fade();return;}
        step=next;render(null);
    }

    private void finishSetup(){
        markDone(this);
        try{startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));}catch(Exception ignored){}
        finish();ui.fade();
    }

    // ------------------------------------------------------------ page frame

    /** Builds the whole page for the current step; focusTag keeps the focus on a control after a rebuild. */
    private void render(final String focusTag){
        listBox=null;wifiStatus=null;spinner=null;passwordField=null;passwordError=null;primary=null;
        shownLocale=currentLocale().toLanguageTag();
        ui.immersive();
        FrameLayout root=new FrameLayout(this);root.setBackground(GabosUi.background());
        getWindow().setBackgroundDrawable(GabosUi.background());
        LinearLayout frame=new LinearLayout(this);frame.setOrientation(LinearLayout.VERTICAL);frame.setClipChildren(false);frame.setClipToPadding(false);
        frame.setPadding(dp(56),dp(28),dp(56),dp(24));
        frame.addView(header(),new LinearLayout.LayoutParams(-1,dp(52)));

        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);scroll.setFillViewport(true);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setClipChildren(false);body.setClipToPadding(false);
        body.setPadding(dp(4),dp(14),dp(4),dp(18));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        frame.addView(ui.assist(scroll),new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL);footer.setClipChildren(false);footer.setClipToPadding(false);
        if(step>WELCOME&&single<0){button(footer,getString(R.string.setup_back),false,v->onBackPressed());}
        footer.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        frame.addView(footer,new LinearLayout.LayoutParams(-1,dp(68)));
        root.addView(frame,new FrameLayout.LayoutParams(-1,-1));

        View initial;
        switch(step){
            case LANGUAGE:initial=buildLanguage(body,footer);break;
            case WIFI:initial=buildWifi(body,footer);break;
            case LOOK:initial=buildLook(body,footer);break;
            case DONE:initial=buildDone(body,footer);break;
            default:initial=buildWelcome(body,footer);
        }
        setContentView(root);
        body.setAlpha(0f);body.animate().alpha(1f).setDuration(220).start();
        View target=focusTag==null?null:root.findViewWithTag(focusTag);
        final View focus=target!=null?target:initial!=null?initial:primary;
        if(focus!=null)focus.post(focus::requestFocus);
        if(step==WIFI&&resumed)enterWifi();
    }

    /** "GabOS setup", progress dots and the battery ring. */
    private View header(){
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=ui.text(getString(single>=0?R.string.settings_brand:R.string.setup_brand),15,GabosUi.DIM);brand.setLetterSpacing(.14f);brand.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(brand);
        LinearLayout dots=new LinearLayout(this);dots.setGravity(Gravity.CENTER_VERTICAL);
        for(int i=0;i<STEPS&&single<0;i++){
            View dot=new View(this);GradientDrawable g=new GradientDrawable();g.setCornerRadius(dp(5));
            g.setColor(i==step?GabosUi.ACCENT:i<step?GabosUi.alpha(GabosUi.ACCENT,0x88):GabosUi.TRACK);dot.setBackground(g);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(i==step?28:10),dp(10));p.setMargins(dp(8),0,0,0);dots.addView(dot,p);
        }
        LinearLayout.LayoutParams dotsParams=new LinearLayout.LayoutParams(-2,-2);dotsParams.setMargins(dp(18),0,0,0);header.addView(dots,dotsParams);
        header.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        header.addView(ui.new BatteryRing(),new LinearLayout.LayoutParams(dp(50),dp(50)));
        return header;
    }

    private void title(LinearLayout body,String title,String detail){
        body.addView(ui.text(title,40,GabosUi.INK));
        if(detail!=null){TextView d=ui.text(detail,19,GabosUi.DIM);d.setLineSpacing(dp(4),1);d.setPadding(0,dp(10),0,0);body.addView(d);}
    }

    /** A pill button; primary buttons use the accent and become the focus default. */
    private TextView button(LinearLayout row,String label,boolean isPrimary,View.OnClickListener action){
        TextView b=ui.text(label,20,isPrimary?GabosUi.ON_ACCENT:GabosUi.INK);b.setGravity(Gravity.CENTER);b.setPadding(dp(28),0,dp(28),0);b.setMinWidth(dp(130));
        GradientDrawable g=new GradientDrawable();g.setCornerRadius(dp(26));g.setColor(isPrimary?GabosUi.ACCENT:GabosUi.PILL);b.setBackground(g);
        b.setContentDescription(label);ui.focusable(b,26);b.setOnClickListener(v->{GabosUi.pulse(v);action.onClick(v);});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(52));p.setMargins(dp(14),0,0,0);row.addView(b,p);
        if(isPrimary)primary=b;
        return b;
    }

    // ------------------------------------------------------------ 1. welcome

    private View buildWelcome(LinearLayout body,LinearLayout footer){
        LinearLayout hero=new LinearLayout(this);hero.setGravity(Gravity.CENTER_VERTICAL);hero.setClipChildren(false);
        hero.addView(new Mark(),new LinearLayout.LayoutParams(dp(150),dp(150)));
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);copy.setPadding(dp(36),0,0,0);
        copy.addView(ui.text(getString(R.string.setup_welcome_title),48,GabosUi.INK));
        TextView version=ui.text(getString(R.string.setup_welcome_version),17,GabosUi.ON_ACCENT);version.setPadding(dp(14),dp(7),dp(14),dp(7));
        GradientDrawable pill=new GradientDrawable();pill.setCornerRadius(dp(16));pill.setColor(GabosUi.ACCENT);version.setBackground(pill);
        LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-2,-2);vp.setMargins(0,dp(14),0,0);copy.addView(version,vp);
        TextView text=ui.text(getString(R.string.setup_welcome_body),20,GabosUi.DIM);text.setLineSpacing(dp(5),1);text.setPadding(0,dp(18),0,0);copy.addView(text);
        hero.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.setMargins(0,dp(26),0,0);body.addView(hero,hp);

        LinearLayout card=ui.card(body,34);
        TextView hint=ui.text(getString(R.string.setup_welcome_hint),18,GabosUi.INK);hint.setLineSpacing(dp(4),1);card.addView(hint);
        TextView site=ui.text(getString(R.string.setup_welcome_site),16,GabosUi.DIM);site.setLetterSpacing(.06f);site.setPadding(0,dp(12),0,0);card.addView(site);

        if(!again)button(footer,getString(R.string.setup_skip_all),false,v->finishSetup());
        return button(footer,getString(R.string.setup_get_started),true,v->go(LANGUAGE));
    }

    // ------------------------------------------------------------ 2. language

    private Locale currentLocale(){
        try{return getResources().getConfiguration().getLocales().get(0);}catch(Exception e){return Locale.getDefault();}
    }

    private Locale localeFor(boolean spanish){
        String country=currentLocale().getCountry();
        if(spanish)return new Locale("es",SPANISH_REGIONS.contains(country)?country:"US");
        return new Locale("en",ENGLISH_REGIONS.contains(country)?country:"US");
    }

    private View buildLanguage(LinearLayout body,LinearLayout footer){
        title(body,getString(R.string.setup_language_title),getString(R.string.setup_language_body));
        boolean spanish="es".equals(currentLocale().getLanguage());
        LinearLayout row=new LinearLayout(this);row.setClipChildren(false);row.setClipToPadding(false);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(dp(8),dp(30),0,0);body.addView(row,rp);
        View english=languageTile(row,"English","EN",!spanish,()->chooseLanguage(false));
        View espanol=languageTile(row,"Español","ES",spanish,()->chooseLanguage(true));
        button(footer,getString(single>=0?R.string.setup_page_done:R.string.setup_next),true,v->go(WIFI));
        return spanish?espanol:english;
    }

    private View languageTile(LinearLayout row,String name,String code,boolean current,final Runnable action){
        LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setGravity(Gravity.CENTER_VERTICAL);tile.setPadding(dp(30),0,dp(30),0);
        int[] tones=current?new int[]{GabosUi.BLUE_TOP,GabosUi.BLUE_BOTTOM}:new int[]{GabosUi.TILE_TOP,GabosUi.TILE_BOTTOM};
        GradientDrawable fill=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,tones);fill.setCornerRadius(dp(10));
        if(GabosUi.LIGHT)fill.setStroke(Math.max(1,dp(1)),0x1a000000);tile.setBackground(fill);
        TextView c=ui.text(code,18,GabosUi.mix(GabosUi.INK,GabosUi.DIM,.5f));c.setLetterSpacing(.16f);c.setTypeface(Typeface.DEFAULT_BOLD);tile.addView(c);
        TextView n=ui.text(name,38,GabosUi.INK);n.setPadding(0,dp(12),0,0);tile.addView(n);
        if(current){TextView now=ui.text(getString(R.string.setup_current),16,GabosUi.mix(GabosUi.INK,GabosUi.DIM,.4f));now.setPadding(0,dp(10),0,0);tile.addView(now);}
        tile.setContentDescription(name);tile.setTag("lang"+code);ui.focusable(tile,10);
        tile.setOnClickListener(v->{GabosUi.pulse(v);action.run();});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(330),dp(180));p.setMargins(0,0,dp(28),0);row.addView(tile,p);return tile;
    }

    private void chooseLanguage(boolean spanish){
        Locale target=localeFor(spanish);
        if(target.getLanguage().equals(currentLocale().getLanguage())){go(WIFI);return;}
        // From Settings the language page stays open in the new language.
        if(single<0)step=WIFI;
        if(applyLocale(target)){
            // onConfigurationChanged re-renders in the new language; render anyway if it never arrives.
            handler.removeCallbacks(localeFallback);handler.postDelayed(localeFallback,1500);
        }else{
            render(null);
            Toast.makeText(this,R.string.setup_language_failed,Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_LOCALE_SETTINGS));
        }
    }

    /** System-wide locale through the hidden LocalePicker (needs CHANGE_CONFIGURATION). */
    private boolean applyLocale(Locale locale){
        try{
            Class<?> picker=Class.forName("com.android.internal.app.LocalePicker");
            try{picker.getMethod("updateLocale",Locale.class).invoke(null,locale);}
            catch(NoSuchMethodException missing){picker.getMethod("updateLocales",LocaleList.class).invoke(null,new LocaleList(locale));}
            Log.i(TAG,"Locale set to "+locale.toLanguageTag());
            return true;
        }catch(Throwable t){
            Log.w(TAG,"Locale change failed: "+t);
            return false;
        }
    }

    // ------------------------------------------------------------ 3. Wi-Fi

    private View buildWifi(LinearLayout body,LinearLayout footer){
        if(passwordFor!=null)return buildPassword(body,footer);
        title(body,getString(R.string.setup_wifi_title),getString(R.string.setup_wifi_body));
        LinearLayout status=new LinearLayout(this);status.setGravity(Gravity.CENTER_VERTICAL);
        spinner=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(GabosUi.ACCENT));
        status.addView(spinner,new LinearLayout.LayoutParams(dp(26),dp(26)));
        wifiStatus=ui.text("",19,GabosUi.INK);wifiStatus.setPadding(dp(12),0,0,0);status.addView(wifiStatus,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(40));sp.setMargins(0,dp(16),0,0);body.addView(status,sp);
        listBox=new LinearLayout(this);listBox.setOrientation(LinearLayout.VERTICAL);listBox.setClipChildren(false);listBox.setClipToPadding(false);
        listBox.setPadding(dp(26),0,dp(26),0);
        body.addView(listBox,new LinearLayout.LayoutParams(-1,-2));

        button(footer,getString(R.string.setup_wifi_rescan),false,v->{startScan();updateWifiStatus();});
        button(footer,getString(R.string.setup_wifi_more),false,v->open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        button(footer,getString(single>=0?R.string.setup_page_done:R.string.setup_skip),true,v->go(LOOK));
        View first=refreshNetworks();
        updateWifiStatus();
        return wifiConnected()||first==null?primary:first;
    }

    private void enterWifi(){
        if(!wifiReceiverOn){
            IntentFilter filter=new IntentFilter();
            filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
            registerReceiver(wifiReceiver,filter);wifiReceiverOn=true;
        }
        try{if(!wifi.isWifiEnabled())wifi.setWifiEnabled(true);}catch(Exception e){Log.w(TAG,"Wi-Fi enable failed: "+e);}
        if(!hasLocationPermission()&&!permissionAsked){
            permissionAsked=true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},1);
        }
        startScan();refreshNetworks();updateWifiStatus();
        handler.removeCallbacks(rescan);handler.postDelayed(rescan,RESCAN_MS);
    }

    private void stopWifiReceiver(){
        if(wifiReceiverOn){try{unregisterReceiver(wifiReceiver);}catch(IllegalArgumentException ignored){}wifiReceiverOn=false;}
    }

    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        if(step==WIFI){startScan();refreshNetworks();updateWifiStatus();}
    }

    private boolean hasLocationPermission(){
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                ||checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private boolean locationOn(){
        try{return Settings.Secure.getInt(getContentResolver(),Settings.Secure.LOCATION_MODE,Settings.Secure.LOCATION_MODE_OFF)!=Settings.Secure.LOCATION_MODE_OFF;}
        catch(Exception e){return true;}
    }

    private void startScan(){
        try{if(wifi.isWifiEnabled())wifi.startScan();}catch(Exception e){Log.w(TAG,"Scan failed: "+e);}
    }

    /** Nearby networks: one entry per name, strongest first. */
    private List<ScanResult> networks(){
        HashMap<String,ScanResult> best=new HashMap<>();
        try{
            List<ScanResult> results=wifi.getScanResults();
            if(results!=null)for(ScanResult r:results){
                if(r.SSID==null||r.SSID.trim().isEmpty())continue;
                ScanResult known=best.get(r.SSID);
                if(known==null||r.level>known.level)best.put(r.SSID,r);
            }
        }catch(Exception e){Log.w(TAG,"Scan results unavailable: "+e);}
        List<ScanResult> list=new ArrayList<>(best.values());
        Collections.sort(list,(a,b)->b.level-a.level);
        return list.size()>24?list.subList(0,24):list;
    }

    /** Rebuilds the network list in place, keeping the focus on the same network; returns the first row. */
    private View refreshNetworks(){
        if(listBox==null)return null;
        View focused=getCurrentFocus();
        String focusTag=focused!=null&&focused.getTag() instanceof String&&((String)focused.getTag()).startsWith("net:")?(String)focused.getTag():null;
        boolean focusInList=focusTag!=null;
        listBox.removeAllViews();
        String current=currentSsid();
        View first=null;
        for(ScanResult r:networks()){
            View row=networkRow(r,current);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(64));p.setMargins(0,dp(10),0,0);listBox.addView(row,p);
            if(first==null)first=row;
        }
        if(first==null){
            TextView empty=ui.text(emptyMessage(),18,GabosUi.DIM);empty.setLineSpacing(dp(4),1);empty.setPadding(0,dp(14),0,0);listBox.addView(empty);
        }
        if(focusInList){
            View same=listBox.findViewWithTag(focusTag);
            View target=same!=null?same:first!=null?first:primary;
            if(target!=null)target.requestFocus();
        }
        return first;
    }

    private String emptyMessage(){
        if(!wifi.isWifiEnabled())return getString(R.string.setup_wifi_turning_on);
        if(!hasLocationPermission())return getString(R.string.setup_wifi_permission);
        if(!locationOn())return getString(R.string.setup_wifi_location);
        return getString(R.string.setup_wifi_none);
    }

    private static boolean secured(ScanResult r){
        String c=r.capabilities==null?"":r.capabilities;
        return c.contains("WEP")||c.contains("PSK")||c.contains("SAE")||c.contains("EAP");
    }

    private View networkRow(final ScanResult r,String current){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(20),0,dp(22),0);
        row.setBackground(ui.surface(GabosUi.CARD,10));
        row.addView(new Signal(WifiManager.calculateSignalLevel(r.level,4)),new LinearLayout.LayoutParams(dp(30),dp(26)));
        TextView name=ui.text(r.SSID,22,GabosUi.INK);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setPadding(dp(18),0,dp(12),0);
        row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        String state=null;int stateColor=GabosUi.DIM;
        if(r.SSID.equals(linkSsid)&&linkState==CONNECTING){state=getString(R.string.setup_wifi_connecting_tag);stateColor=GabosUi.ACCENT;}
        else if(r.SSID.equals(current)){state=getString(R.string.setup_wifi_connected_tag);stateColor=GabosUi.ACCENT;}
        if(state!=null){TextView s=ui.text(state,17,stateColor);s.setPadding(0,0,dp(14),0);row.addView(s);}
        boolean locked=secured(r);
        TextView kind=ui.text(getString(locked?R.string.setup_wifi_secured:R.string.setup_wifi_open),16,GabosUi.DIM);kind.setPadding(0,0,dp(10),0);row.addView(kind);
        if(locked)row.addView(new Lock(),new LinearLayout.LayoutParams(dp(20),dp(24)));
        row.setContentDescription(r.SSID+", "+kind.getText());
        row.setTag("net:"+r.SSID);ui.focusable(row,10);
        row.setOnClickListener(v->{GabosUi.pulse(v);selectNetwork(r);});
        return row;
    }

    private void selectNetwork(ScanResult r){
        String caps=r.capabilities==null?"":r.capabilities;
        if(r.SSID.equals(currentSsid())){linkSsid=r.SSID;setLink(CONNECTED,null);return;}
        if(caps.contains("EAP")){
            Toast.makeText(this,getString(R.string.setup_wifi_enterprise,r.SSID),Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_WIFI_SETTINGS));return;
        }
        if(!secured(r)){connect(r,null);return;}
        passwordFor=r;render(null);
    }

    private View buildPassword(LinearLayout body,LinearLayout footer){
        final ScanResult target=passwordFor;
        title(body,getString(R.string.setup_wifi_password_title,target.SSID),null);
        passwordField=new EditText(this);
        passwordField.setSingleLine(true);passwordField.setTextSize(24);passwordField.setTextColor(GabosUi.INK);passwordField.setHintTextColor(GabosUi.DIM);
        passwordField.setHint(R.string.setup_wifi_password_hint);
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        passwordField.setPadding(dp(22),0,dp(22),0);
        passwordField.setBackground(ui.surface(GabosUi.CARD,10));
        passwordField.setTag("password");ui.focusable(passwordField,10);
        // ui.focusable makes it clickable; reopen the keyboard on each click/OK.
        passwordField.setOnClickListener(v->showKeyboard());
        passwordField.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_DONE||event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP){submitPassword(target);return true;}
            return false;
        });
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(dp(640),dp(64));fp.setMargins(dp(8),dp(28),0,0);body.addView(passwordField,fp);

        LinearLayout show=new LinearLayout(this);show.setGravity(Gravity.CENTER_VERTICAL);show.setClipChildren(false);
        show.addView(ui.text(getString(R.string.setup_wifi_show_password),19,GabosUi.INK));
        ui.toggle(show,false,on->{
            int selection=passwordField.getSelectionEnd();
            passwordField.setInputType(InputType.TYPE_CLASS_TEXT|(on==1?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:InputType.TYPE_TEXT_VARIATION_PASSWORD));
            passwordField.setSelection(Math.max(0,Math.min(selection,passwordField.length())));
        });
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,-2);sp.setMargins(dp(8),dp(22),0,0);body.addView(show,sp);

        passwordError=ui.text("",18,GabosUi.WARN);passwordError.setPadding(dp(8),dp(18),0,0);passwordError.setVisibility(View.GONE);body.addView(passwordError);

        button(footer,getString(R.string.setup_wifi_connect),true,v->submitPassword(target));
        passwordField.postDelayed(this::showKeyboard,350);
        return passwordField;
    }

    private void showKeyboard(){
        if(passwordField==null)return;
        passwordField.requestFocus();
        InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(imm!=null)imm.showSoftInput(passwordField,InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard(){
        InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        View v=getCurrentFocus();
        if(imm!=null&&v!=null)imm.hideSoftInputFromWindow(v.getWindowToken(),0);
    }

    private void submitPassword(ScanResult target){
        String pass=passwordField==null?"":passwordField.getText().toString();
        String caps=target.capabilities==null?"":target.capabilities;
        boolean wep=caps.contains("WEP")&&!caps.contains("PSK")&&!caps.contains("SAE");
        if(!wep&&(pass.length()<8||pass.length()>64)||wep&&pass.isEmpty()){
            if(passwordError!=null){passwordError.setText(R.string.setup_wifi_short);passwordError.setVisibility(View.VISIBLE);}
            return;
        }
        hideKeyboard();
        passwordFor=null;
        connect(target,pass);
    }

    private static String quoted(String s){return "\""+s+"\"";}
    private static boolean hex(String s){return s.matches("[0-9A-Fa-f]+");}

    /** Adds the network and switches to it; progress arrives through the Wi-Fi broadcasts. */
    @SuppressWarnings("deprecation")
    private void connect(ScanResult r,String pass){
        String caps=r.capabilities==null?"":r.capabilities;
        WifiConfiguration c=new WifiConfiguration();
        c.SSID=quoted(r.SSID);
        if(pass==null){
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        }else if(caps.contains("PSK")){
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            c.preSharedKey=pass.length()==64&&hex(pass)?pass:quoted(pass);
        }else if(caps.contains("SAE")){
            // WPA3-only: WifiConfiguration.setSecurityParams(SECURITY_TYPE_SAE=4) exists from API 30.
            try{WifiConfiguration.class.getMethod("setSecurityParams",int.class).invoke(c,4);}
            catch(Throwable t){Log.w(TAG,"WPA3 setup unavailable: "+t);}
            c.preSharedKey=quoted(pass);
        }else{
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
            int n=pass.length();
            c.wepKeys[0]=(n==10||n==26||n==58)&&hex(pass)?pass:quoted(pass);
            c.wepTxKeyIndex=0;
        }
        linkSsid=r.SSID;
        int id=-1;
        try{id=wifi.addNetwork(c);}catch(Exception e){Log.w(TAG,"addNetwork failed: "+e);}
        if(id<0){linkNetId=-1;render(null);fail(getString(R.string.setup_wifi_failed,r.SSID));return;}
        linkNetId=id;
        try{wifi.disconnect();wifi.enableNetwork(id,true);wifi.reconnect();}catch(Exception e){Log.w(TAG,"enableNetwork failed: "+e);}
        Log.i(TAG,"Connecting to network id "+id);
        linkState=CONNECTING;linkMessage=null;
        handler.removeCallbacks(connectTimeout);handler.postDelayed(connectTimeout,CONNECT_TIMEOUT_MS);
        render(null);
    }

    private void fail(String message){
        handler.removeCallbacks(connectTimeout);
        if(linkNetId>=0){try{wifi.removeNetwork(linkNetId);}catch(Exception ignored){}linkNetId=-1;}
        setLink(FAILED,message);
    }

    private void setLink(int state,String message){
        if(state==CONNECTED)handler.removeCallbacks(connectTimeout);
        linkState=state;linkMessage=message;
        refreshNetworks();updateWifiStatus();
        if(state==CONNECTED&&primary!=null&&step==WIFI&&passwordFor==null)primary.requestFocus();
    }

    /** The joined network is the one being set up (the name may be hidden without location access). */
    private boolean joinedTarget(){
        try{
            WifiInfo info=wifi.getConnectionInfo();if(info==null)return false;
            if(linkNetId>=0&&info.getNetworkId()==linkNetId)return true;
            String ssid=currentSsid();
            return ssid!=null&&(ssid.isEmpty()||ssid.equals(linkSsid));
        }catch(Exception e){return false;}
    }

    /** Connected network name; "" when connected but the name is hidden; null when not on Wi-Fi. */
    @SuppressWarnings("deprecation")
    private String currentSsid(){
        try{
            NetworkInfo n=((ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE)).getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if(n==null||!n.isConnected())return null;
            WifiInfo info=wifi.getConnectionInfo();
            String s=info==null?null:info.getSSID();
            if(s==null||s.isEmpty()||"<unknown ssid>".equals(s))return "";
            if(s.length()>1&&s.startsWith("\"")&&s.endsWith("\""))s=s.substring(1,s.length()-1);
            return s;
        }catch(Exception e){return null;}
    }

    private boolean wifiConnected(){return currentSsid()!=null;}

    private void updateWifiStatus(){
        if(wifiStatus==null)return;
        String current=currentSsid();
        String text;int color=GabosUi.INK;boolean busy=false;
        if(linkState==CONNECTING){text=getString(R.string.setup_wifi_connecting,linkSsid);busy=true;}
        else if(linkState==FAILED&&linkMessage!=null&&current==null){text=linkMessage;color=GabosUi.WARN;}
        else if(current!=null){text=current.isEmpty()?getString(R.string.setup_wifi_connected_unnamed):getString(R.string.setup_wifi_connected,current);color=GabosUi.ACCENT;}
        else if(linkState==FAILED&&linkMessage!=null){text=linkMessage;color=GabosUi.WARN;}
        else if(!wifi.isWifiEnabled()){text=getString(R.string.setup_wifi_turning_on);busy=true;}
        else{text=getString(R.string.setup_wifi_scanning);busy=hasLocationPermission()&&locationOn();}
        wifiStatus.setText(text);wifiStatus.setTextColor(color);
        if(spinner!=null)spinner.setVisibility(busy?View.VISIBLE:View.GONE);
        if(primary!=null){primary.setText(single>=0?R.string.setup_page_done:current!=null?R.string.setup_next:R.string.setup_skip);primary.setContentDescription(primary.getText());}
    }

    // ------------------------------------------------------------ 4. look

    private View buildLook(LinearLayout body,LinearLayout footer){
        title(body,getString(R.string.setup_look_title),getString(R.string.setup_look_body));
        LinearLayout r=ui.settingRow(body,getString(R.string.mode_title),getString(R.string.mode_summary));
        final boolean light=ThemeProvider.light(this);
        View[] modes=ui.choices(r,new String[]{getString(R.string.mode_dark),getString(R.string.mode_light)},light?1:0,
                i->applyTheme(ThemeProvider.accent(this),i==1,"mode"+i));
        for(int i=0;i<modes.length;i++)modes[i].setTag("mode"+i);
        ui.settingRow(body,getString(R.string.accent_title),getString(R.string.accent_summary));
        LinearLayout swatchRow=ui.row(body,14);swatchRow.setPadding(dp(8),dp(4),0,dp(4));
        int accent=ThemeProvider.accent(this),selected=-1;
        String[] names=new String[GabosUi.ACCENTS.length];
        for(int i=0;i<names.length;i++){names[i]=getString(ACCENT_NAMES[i]);if(GabosUi.ACCENTS[i]==accent)selected=i;}
        View[] swatches=ui.swatches(swatchRow,GabosUi.ACCENTS,names,selected,i->applyTheme(GabosUi.ACCENTS[i],ThemeProvider.light(this),"accent"+i));
        for(int i=0;i<swatches.length;i++)swatches[i].setTag("accent"+i);
        return button(footer,getString(R.string.setup_next),true,v->go(DONE));
    }

    private void applyTheme(int accent,boolean light,String focusTag){
        if(accent==ThemeProvider.accent(this)&&light==ThemeProvider.light(this))return;
        ThemeProvider.save(this,accent,light);
        ui.refreshTheme();render(focusTag);
    }

    private String accentName(){
        int accent=ThemeProvider.accent(this);
        for(int i=0;i<GabosUi.ACCENTS.length;i++)if(GabosUi.ACCENTS[i]==accent)return getString(ACCENT_NAMES[i]);
        return getString(R.string.accent_custom_title);
    }

    // ------------------------------------------------------------ 5. done

    private View buildDone(LinearLayout body,LinearLayout footer){
        title(body,getString(R.string.setup_done_title),getString(R.string.setup_done_body));
        LinearLayout card=ui.card(body,28);
        String ssid=currentSsid();
        summary(card,getString(R.string.setup_done_language),"es".equals(currentLocale().getLanguage())?"Español":"English",true);
        summary(card,getString(R.string.setup_done_wifi),ssid==null?getString(R.string.setup_done_not_connected)
                :ssid.isEmpty()?getString(R.string.setup_wifi_connected_unnamed):ssid,false);
        String look=getString(ThemeProvider.light(this)?R.string.mode_light:R.string.mode_dark);
        String accent=accentName();
        summary(card,getString(R.string.setup_done_look),accent.isEmpty()?look:look+"  ·  "+accent,false);
        TextView site=ui.text(getString(R.string.setup_welcome_site),16,GabosUi.DIM);site.setLetterSpacing(.06f);site.setPadding(dp(4),dp(22),0,0);body.addView(site);
        return button(footer,getString(R.string.setup_start),true,v->finishSetup());
    }

    private void summary(LinearLayout card,String label,String value,boolean first){
        LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);
        TextView l=ui.text(label,18,GabosUi.DIM);line.addView(l,new LinearLayout.LayoutParams(dp(200),-2));
        TextView v=ui.text(value,24,GabosUi.INK);v.setSingleLine(true);v.setEllipsize(android.text.TextUtils.TruncateAt.END);line.addView(v,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,first?0:dp(16),0,0);card.addView(line,p);
    }

    // ------------------------------------------------------------ helpers

    private void open(Intent intent){
        try{startActivity(intent);ui.fade();}
        catch(ActivityNotFoundException e){Toast.makeText(this,R.string.not_available,Toast.LENGTH_SHORT).show();}
        catch(Exception e){Toast.makeText(this,R.string.not_available,Toast.LENGTH_SHORT).show();}
    }

    /** The GabOS mark on the welcome page: an accent ring with a lens and light beam. */
    private class Mark extends View{
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Mark(){super(SetupActivity.this);}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight(),r=Math.min(w,h)/2f;
            p.setStyle(Paint.Style.FILL);p.setColor(GabosUi.alpha(GabosUi.ACCENT,0x2a));c.drawCircle(w/2,h/2,r,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(r*.08f);p.setColor(GabosUi.ACCENT);c.drawCircle(w/2,h/2,r*.78f,p);
            p.setStyle(Paint.Style.FILL);p.setColor(GabosUi.ACCENT);c.drawCircle(w/2,h/2,r*.34f,p);
            p.setColor(GabosUi.LIGHT?0xffffffff:GabosUi.BACKGROUND[0]);c.drawCircle(w/2-r*.1f,h/2-r*.1f,r*.1f,p);
        }
    }

    /** Four rising bars; level is 0..3. */
    private class Signal extends View{
        final int level;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Signal(int level){super(SetupActivity.this);this.level=level;}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight(),bar=w/5.5f,gap=(w-bar*4)/3;
            for(int i=0;i<4;i++){
                float bh=h*(.3f+.7f*i/3f),x=i*(bar+gap);
                p.setColor(i<=level?GabosUi.INK:GabosUi.alpha(GabosUi.INK,0x44));
                c.drawRoundRect(new RectF(x,h-bh,x+bar,h),bar/3,bar/3,p);
            }
        }
    }

    /** A small padlock for secured networks. */
    private class Lock extends View{
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Lock(){super(SetupActivity.this);}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight();
            p.setColor(GabosUi.DIM);p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(new RectF(w*.08f,h*.44f,w*.92f,h),w*.14f,w*.14f,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w*.13f);
            Path shackle=new Path();shackle.moveTo(w*.28f,h*.46f);shackle.lineTo(w*.28f,h*.3f);
            shackle.arcTo(new RectF(w*.28f,h*.06f,w*.72f,h*.5f),180,180,false);shackle.lineTo(w*.72f,h*.46f);c.drawPath(shackle,p);
        }
    }
}
