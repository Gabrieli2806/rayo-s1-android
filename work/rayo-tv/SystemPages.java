package local.rayo.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import local.gabos.ui.GabosUi;

/**
 * GabOS pages for the system settings people use most: Bluetooth, apps,
 * storage and device information, in the same look as GabOS Settings, so the
 * native TV Settings screens are only needed for rare options.
 *
 * Bluetooth connect/disconnect and forget use profile and device methods that
 * are hidden from apps; GabOS installs the launcher as a privileged system app
 * with BLUETOOTH_PRIVILEGED (see the packager) so these calls are allowed.
 */
public class SystemPages extends Activity {
    public static final String EXTRA_PAGE="local.gabos.extra.SYSTEM_PAGE";
    private static final int HID_HOST=4;
    private GabosUi ui;
    private String page;
    private final Handler handler=new Handler(Looper.getMainLooper());

    // Bluetooth state.
    private BluetoothAdapter adapter;
    private final Map<Integer,BluetoothProfile> profiles=new HashMap<>();
    private final LinkedHashMap<String,BluetoothDevice> found=new LinkedHashMap<>();
    private boolean receiverOn;
    private String focusTag;
    /** Rebuilds the page and keeps the focus on the control the user is on. */
    private final Runnable rerender=()->{
        View current=getCurrentFocus();
        render(current!=null&&current.getTag() instanceof String?(String)current.getTag():focusTag);
    };

    private final BroadcastReceiver bluetoothReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            String action=intent.getAction();
            if(BluetoothDevice.ACTION_FOUND.equals(action)){
                BluetoothDevice device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if(device!=null&&device.getBondState()!=BluetoothDevice.BOND_BONDED)found.put(device.getAddress(),device);
            }
            // Coalesce bursts of discovery and state broadcasts into one rebuild.
            handler.removeCallbacks(rerender);handler.postDelayed(rerender,400);
        }
    };

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        ui=new GabosUi(this);
        page=getIntent().getStringExtra(EXTRA_PAGE);
        if(page==null)page="about";
        if("bluetooth".equals(page))startBluetooth();
        render(null);
    }

    @Override protected void onResume(){super.onResume();if(ui.refreshTheme())render(null);}

    @Override protected void onDestroy(){
        handler.removeCallbacksAndMessages(null);
        if(receiverOn){try{unregisterReceiver(bluetoothReceiver);}catch(Exception ignored){}receiverOn=false;}
        if(adapter!=null){
            try{adapter.cancelDiscovery();}catch(Exception ignored){}
            for(Map.Entry<Integer,BluetoothProfile> p:profiles.entrySet())try{adapter.closeProfileProxy(p.getKey(),p.getValue());}catch(Exception ignored){}
        }
        super.onDestroy();
    }

    @Override public void onBackPressed(){super.onBackPressed();ui.fade();}

    private void render(String focus){
        switch(page){
            case "bluetooth":renderBluetooth(focus);break;
            case "apps":renderApps(focus);break;
            case "storage":renderStorage();break;
            default:renderAbout();
        }
    }

    // ------------------------------------------------------------ shared pieces

    /** A compact pill button at the end of a settings row. */
    private View button(LinearLayout row,String label,boolean accent,String tag,View.OnClickListener action){
        TextView pill=ui.text(label,17,accent?GabosUi.ON_ACCENT:GabosUi.INK);pill.setGravity(Gravity.CENTER);pill.setPadding(ui.dp(20),0,ui.dp(20),0);
        GradientDrawable g=new GradientDrawable();g.setCornerRadius(ui.dp(22));g.setColor(accent?GabosUi.ACCENT:GabosUi.PILL);pill.setBackground(g);
        pill.setContentDescription(label);if(tag!=null)pill.setTag(tag);ui.focusable(pill,22);
        pill.setOnClickListener(v->{GabosUi.pulse(v);action.onClick(v);});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,ui.dp(44));p.setMargins(ui.dp(12),0,0,0);row.addView(pill,p);return pill;
    }

    private void info(LinearLayout page,String title,String value){
        LinearLayout row=ui.settingRow(page,title,null);
        TextView v=ui.text(value,19,GabosUi.DIM);v.setGravity(Gravity.END);row.addView(v,new LinearLayout.LayoutParams(-2,-2));
    }

    private void focus(LinearLayout page,String tag,View fallback){
        page.post(()->{
            View target=tag==null?null:page.findViewWithTag(tag);
            if(target==null)target=fallback;
            if(target!=null)target.requestFocus();
        });
    }

    private void open(Intent intent){
        try{startActivity(intent);ui.fade();}
        catch(Exception e){Toast.makeText(this,R.string.not_available,Toast.LENGTH_SHORT).show();}
    }

    private static Object call(Object target,String name,Class<?>[] types,Object... args){
        try{Method m=target.getClass().getMethod(name,types);return m.invoke(target,args);}
        catch(Exception e){return null;}
    }

    // ------------------------------------------------------------ Bluetooth

    private void startBluetooth(){
        adapter=BluetoothAdapter.getDefaultAdapter();
        if(adapter==null)return;
        IntentFilter filter=new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(bluetoothReceiver,filter);receiverOn=true;
        BluetoothProfile.ServiceListener listener=new BluetoothProfile.ServiceListener(){
            @Override public void onServiceConnected(int profile,BluetoothProfile proxy){profiles.put(profile,proxy);handler.post(rerender);}
            @Override public void onServiceDisconnected(int profile){profiles.remove(profile);}
        };
        for(int profile:new int[]{BluetoothProfile.A2DP,BluetoothProfile.HEADSET,HID_HOST})
            try{adapter.getProfileProxy(this,listener,profile);}catch(Exception ignored){}
        if(adapter.isEnabled())discover();
    }

    private void discover(){
        if(adapter==null||!adapter.isEnabled())return;
        found.clear();
        try{if(adapter.isDiscovering())adapter.cancelDiscovery();adapter.startDiscovery();}catch(Exception ignored){}
    }

    private boolean connected(BluetoothDevice device){
        Object value=call(device,"isConnected",new Class<?>[0]);
        if(value instanceof Boolean)return (Boolean)value;
        for(BluetoothProfile p:profiles.values())
            try{if(p.getConnectionState(device)==BluetoothProfile.STATE_CONNECTED)return true;}catch(Exception ignored){}
        return false;
    }

    /** Profiles the device offers that GabOS can connect: audio (A2DP, headset) and input (HID). */
    private List<Integer> profilesFor(BluetoothDevice device){
        List<Integer> result=new ArrayList<>();
        BluetoothClass type=device.getBluetoothClass();
        int major=type==null?BluetoothClass.Device.Major.UNCATEGORIZED:type.getMajorDeviceClass();
        if(major==BluetoothClass.Device.Major.PERIPHERAL)result.add(HID_HOST);
        else if(major==BluetoothClass.Device.Major.AUDIO_VIDEO){result.add(BluetoothProfile.A2DP);result.add(BluetoothProfile.HEADSET);}
        else{result.add(BluetoothProfile.A2DP);result.add(HID_HOST);}
        return result;
    }

    private void setConnected(BluetoothDevice device,boolean on){
        boolean any=false;
        for(int id:profilesFor(device)){
            BluetoothProfile proxy=profiles.get(id);if(proxy==null)continue;
            Object ok=call(proxy,on?"connect":"disconnect",new Class<?>[]{BluetoothDevice.class},device);
            if(Boolean.TRUE.equals(ok))any=true;
        }
        Toast.makeText(this,any?(on?R.string.bt_connecting:R.string.bt_disconnecting):R.string.bt_action_failed,Toast.LENGTH_SHORT).show();
        handler.postDelayed(rerender,1500);
    }

    private String kind(BluetoothDevice device){
        BluetoothClass type=device.getBluetoothClass();
        if(type==null)return getString(R.string.bt_kind_device);
        switch(type.getMajorDeviceClass()){
            case BluetoothClass.Device.Major.AUDIO_VIDEO:return getString(R.string.bt_kind_audio);
            case BluetoothClass.Device.Major.PERIPHERAL:return getString(R.string.bt_kind_input);
            case BluetoothClass.Device.Major.PHONE:return getString(R.string.bt_kind_phone);
            case BluetoothClass.Device.Major.COMPUTER:return getString(R.string.bt_kind_computer);
            default:return getString(R.string.bt_kind_device);
        }
    }

    private String name(BluetoothDevice device){
        String name=null;
        try{name=device.getName();}catch(Exception ignored){}
        return name==null||name.trim().isEmpty()?device.getAddress():name;
    }

    private void renderBluetooth(String focus){
        focusTag=focus;
        LinearLayout page=ui.page(getString(R.string.more_bluetooth),getString(R.string.bt_subtitle));
        if(adapter==null){ui.settingRow(page,getString(R.string.bt_missing),null);return;}
        boolean on=adapter.isEnabled();
        LinearLayout row=ui.settingRow(page,getString(R.string.bt_power),on?adapter.getName():getString(R.string.bt_off_summary));
        View power=ui.toggle(row,on,v->{try{if(v==1)adapter.enable();else adapter.disable();}catch(Exception ignored){}
            handler.postDelayed(()->{if(adapter.isEnabled())discover();render("power");},2500);});
        power.setTag("power");
        View first=power;
        if(on){
            List<BluetoothDevice> paired=new ArrayList<>(adapter.getBondedDevices());
            ui.section(page,getString(R.string.bt_paired));
            if(paired.isEmpty())ui.settingRow(page,getString(R.string.bt_none_paired),getString(R.string.bt_none_paired_summary));
            for(final BluetoothDevice device:paired){
                boolean link=connected(device);
                LinearLayout r=ui.settingRow(page,name(device),kind(device)+"  ·  "+getString(link?R.string.bt_connected:R.string.bt_not_connected));
                String key=device.getAddress();
                button(r,getString(link?R.string.bt_disconnect:R.string.bt_connect),!link,"c"+key,v->setConnected(device,!link));
                button(r,getString(R.string.bt_forget),false,"f"+key,v->{
                    Object ok=call(device,"removeBond",new Class<?>[0]);
                    if(!Boolean.TRUE.equals(ok))Toast.makeText(this,R.string.bt_action_failed,Toast.LENGTH_SHORT).show();
                    handler.postDelayed(rerender,800);
                });
            }
            ui.section(page,getString(R.string.bt_available));
            boolean searching=adapter.isDiscovering();
            row=ui.settingRow(page,getString(searching?R.string.bt_searching:R.string.bt_search),getString(R.string.bt_search_summary));
            button(row,getString(R.string.bt_search_again),false,"scan",v->{discover();render("scan");});
            for(final BluetoothDevice device:found.values()){
                if(device.getBondState()==BluetoothDevice.BOND_BONDED)continue;
                boolean bonding=device.getBondState()==BluetoothDevice.BOND_BONDING;
                LinearLayout r=ui.settingRow(page,name(device),kind(device));
                button(r,getString(bonding?R.string.bt_pairing:R.string.bt_pair),true,"p"+device.getAddress(),v->{
                    try{adapter.cancelDiscovery();}catch(Exception ignored){}
                    if(!device.createBond())Toast.makeText(this,R.string.bt_action_failed,Toast.LENGTH_SHORT).show();
                    handler.postDelayed(rerender,600);
                });
            }
            TextView hint=ui.text(getString(R.string.bt_remote_hint),16,GabosUi.DIM);hint.setPadding(ui.dp(8),ui.dp(18),0,0);page.addView(hint);
        }
        focus(page,focus,first);
    }

    // ------------------------------------------------------------ Apps

    private void renderApps(String focus){
        LinearLayout page=ui.page(getString(R.string.more_apps),getString(R.string.apps_subtitle));
        PackageManager pm=getPackageManager();
        List<ApplicationInfo> user=new ArrayList<>(),system=new ArrayList<>();
        Intent main=new Intent(Intent.ACTION_MAIN);
        java.util.HashSet<String> launchable=new java.util.HashSet<>();
        for(String category:new String[]{Intent.CATEGORY_LAUNCHER,Intent.CATEGORY_LEANBACK_LAUNCHER}){
            for(ResolveInfo r:pm.queryIntentActivities(new Intent(main).addCategory(category),0))launchable.add(r.activityInfo.packageName);
        }
        for(ApplicationInfo app:pm.getInstalledApplications(0)){
            if(!launchable.contains(app.packageName)||app.packageName.equals(getPackageName()))continue;
            if((app.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0)system.add(app);else user.add(app);
        }
        Collections.sort(user,new ApplicationInfo.DisplayNameComparator(pm));Collections.sort(system,new ApplicationInfo.DisplayNameComparator(pm));
        View first=null;
        ui.section(page,getString(R.string.apps_installed));
        if(user.isEmpty())ui.settingRow(page,getString(R.string.apps_none),null);
        for(ApplicationInfo app:user){View v=appRow(page,pm,app,true);if(first==null)first=v;}
        ui.section(page,getString(R.string.apps_system));
        for(ApplicationInfo app:system){View v=appRow(page,pm,app,false);if(first==null)first=v;}
        focus(page,focus,first);
    }

    private View appRow(LinearLayout page,PackageManager pm,final ApplicationInfo app,boolean removable){
        String version="";
        try{PackageInfo info=pm.getPackageInfo(app.packageName,0);version=info.versionName==null?"":info.versionName;}catch(Exception ignored){}
        LinearLayout row=ui.settingRow(page,app.loadLabel(pm).toString(),version.isEmpty()?app.packageName:getString(R.string.apps_version,version));
        View open=button(row,getString(R.string.apps_open),true,"o"+app.packageName,v->{
            Intent i=pm.getLeanbackLaunchIntentForPackage(app.packageName);if(i==null)i=pm.getLaunchIntentForPackage(app.packageName);
            if(i!=null)open(i);
        });
        // Stop, clear data and permissions stay in the system's app details screen.
        button(row,getString(R.string.apps_details),false,"d"+app.packageName,
                v->open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+app.packageName))));
        if(removable)button(row,getString(R.string.apps_uninstall),false,"u"+app.packageName,
                v->open(new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+app.packageName))));
        return open;
    }

    // ------------------------------------------------------------ Storage

    private void renderStorage(){
        LinearLayout page=ui.page(getString(R.string.more_storage),getString(R.string.storage_subtitle));
        StatFs data=new StatFs(Environment.getDataDirectory().getPath());
        long total=data.getTotalBytes(),free=data.getAvailableBytes(),used=total-free;
        LinearLayout card=ui.card(page,20);
        card.addView(ui.text(getString(R.string.storage_internal),14,GabosUi.DIM));
        TextView big=ui.text(getString(R.string.storage_free,Formatter.formatShortFileSize(this,free)),30,GabosUi.INK);big.setPadding(0,ui.dp(10),0,ui.dp(12));card.addView(big);
        card.addView(new Bar(used/(float)Math.max(1,total)),new LinearLayout.LayoutParams(-1,ui.dp(12)));
        TextView detail=ui.text(getString(R.string.storage_used_of,Formatter.formatShortFileSize(this,used),Formatter.formatShortFileSize(this,total)),16,GabosUi.DIM);
        detail.setPadding(0,ui.dp(10),0,0);card.addView(detail);
        ui.section(page,getString(R.string.storage_folders));
        File shared=Environment.getExternalStorageDirectory();
        for(String[] folder:new String[][]{{Environment.DIRECTORY_MOVIES,getString(R.string.storage_movies)},{Environment.DIRECTORY_PICTURES,getString(R.string.storage_pictures)},
                {Environment.DIRECTORY_MUSIC,getString(R.string.storage_music)},{Environment.DIRECTORY_DOWNLOADS,getString(R.string.storage_downloads)}})
            info(page,folder[1],Formatter.formatShortFileSize(this,size(new File(shared,folder[0]),0)));
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
        ui.section(page,getString(R.string.storage_memory));
        info(page,getString(R.string.storage_memory_free),getString(R.string.storage_used_of,Formatter.formatShortFileSize(this,memory.availMem),Formatter.formatShortFileSize(this,memory.totalMem)));
        LinearLayout row=ui.settingRow(page,getString(R.string.storage_files),getString(R.string.storage_files_summary));
        View files=button(row,getString(R.string.apps_open),true,"files",v->{Intent i=getPackageManager().getLaunchIntentForPackage("com.android.documentsui");if(i!=null)open(i);});
        focus(page,null,files);
    }

    private static long size(File file,int depth){
        if(file==null||depth>12)return 0;
        if(file.isFile())return file.length();
        File[] children=file.listFiles();long total=0;
        if(children!=null)for(File child:children)total+=size(child,depth+1);
        return total;
    }

    private class Bar extends View{
        final float fraction;final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        Bar(float fraction){super(SystemPages.this);this.fraction=Math.max(0,Math.min(1,fraction));}
        @Override protected void onDraw(android.graphics.Canvas c){
            float h=getHeight(),r=h/2;
            paint.setColor(GabosUi.TRACK);c.drawRoundRect(0,0,getWidth(),h,r,r,paint);
            paint.setColor(fraction>.9f?GabosUi.WARN:GabosUi.ACCENT);c.drawRoundRect(0,0,Math.max(h,getWidth()*fraction),h,r,r,paint);
        }
    }

    // ------------------------------------------------------------ About

    private void renderAbout(){
        LinearLayout page=ui.page(getString(R.string.more_device_info),null);
        LinearLayout card=ui.card(page,20);
        card.addView(ui.text(getString(R.string.about_caption),14,GabosUi.DIM));
        TextView version=ui.text(getString(R.string.about_version),28,GabosUi.INK);version.setPadding(0,ui.dp(10),0,0);card.addView(version);
        info(page,getString(R.string.about_model),getString(R.string.about_device,Projector.model()));
        info(page,getString(R.string.about_android),Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")");
        info(page,getString(R.string.about_build),Build.DISPLAY);
        info(page,getString(R.string.about_kernel),System.getProperty("os.version","?"));
        info(page,getString(R.string.about_address),address());
        long up=SystemClock.elapsedRealtime()/60000;
        info(page,getString(R.string.about_uptime),getString(R.string.about_uptime_value,up/60,up%60));
        TextView site=ui.text("g2806.net",18,GabosUi.ACCENT);site.setPadding(ui.dp(8),ui.dp(22),0,0);page.addView(site);
    }

    private String address(){
        try{
            ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            Network network=cm.getActiveNetwork();LinkProperties link=network==null?null:cm.getLinkProperties(network);
            if(link!=null)for(LinkAddress a:link.getLinkAddresses())if(a.getAddress() instanceof java.net.Inet4Address)return a.getAddress().getHostAddress();
        }catch(Exception ignored){}
        return getString(R.string.status_offline);
    }
}
