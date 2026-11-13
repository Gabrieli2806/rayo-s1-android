package local.gabos.share.sender;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GabOS Share for phones: find a projector on the Wi-Fi network, pick photos
 * and videos (or receive them from another app's Share menu), choose Show or
 * Save and send.
 */
public class MainActivity extends Activity {
    private static final int PICK=1;

    private Ui ui;
    private Discovery discovery;
    private SharedPreferences prefs;
    private final List<Discovery.Projector> projectors=new ArrayList<>();
    private Discovery.Projector manual;
    private String selected;
    private final List<Sender.Item> items=new ArrayList<>();
    private boolean show=true;
    private Sender sender;

    private LinearLayout projectorList, modeShow, modeSave;
    private TextView searching, mediaSummary, mediaNames, status, send, cancel, clear;
    private EditText address;
    private Ui.Bar bar;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        ui=new Ui(this);
        prefs=getSharedPreferences("share",MODE_PRIVATE);
        show=!"save".equals(prefs.getString("mode","show"));
        String last=prefs.getString("manual",null);
        if(last!=null)manual=parse(last);
        selected=prefs.getString("selected",null);
        if(Build.VERSION.SDK_INT>=21){
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(0xff0d0f13);getWindow().setNavigationBarColor(0xff101d36);
        }
        build();
        discovery=new Discovery(this,found->{projectors.clear();projectors.addAll(found);
            if(selected==null&&found.size()==1)selected=found.get(0).key();
            renderProjectors();});
        receive(getIntent());
        renderAll();
    }

    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);receive(intent);renderAll();}

    @Override protected void onStart(){super.onStart();discovery.start();}
    @Override protected void onStop(){discovery.stop();super.onStop();}

    @Override protected void onDestroy(){
        if(sender!=null&&isFinishing())sender.cancel();
        super.onDestroy();
    }

    // ---- Layout ----------------------------------------------------------------------------

    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackground(Ui.background());scroll.setVerticalScrollBarEnabled(false);
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(ui.dp(20),ui.dp(32),ui.dp(20),ui.dp(32));
        scroll.addView(column,new ScrollView.LayoutParams(-1,-2));

        column.addView(ui.text(getString(R.string.app_name),34,Ui.INK));
        TextView sub=ui.text(getString(R.string.subtitle),16,Ui.DIM);sub.setPadding(0,ui.dp(8),0,0);sub.setLineSpacing(0,1.15f);column.addView(sub);

        // Projector
        ui.section(column,getString(R.string.section_projector));
        LinearLayout pc=ui.card(column);
        projectorList=new LinearLayout(this);projectorList.setOrientation(LinearLayout.VERTICAL);pc.addView(projectorList);
        searching=ui.text(getString(R.string.searching),15,Ui.DIM);searching.setLineSpacing(0,1.15f);pc.addView(searching);
        LinearLayout manualRow=new LinearLayout(this);manualRow.setGravity(Gravity.CENTER_VERTICAL);manualRow.setPadding(0,ui.dp(14),0,0);
        address=new EditText(this);address.setHint(R.string.manual_hint);address.setHintTextColor(0xff6f7782);address.setTextColor(Ui.INK);address.setTextSize(16);
        address.setSingleLine(true);address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setBackground(ui.surface(Ui.NEUTRAL,14));address.setPadding(ui.dp(14),0,ui.dp(14),0);
        if(manual!=null)address.setText(manual.host+(manual.port==8765?"":":"+manual.port));
        address.setOnEditorActionListener((v,action,e)->{useManual();return true;});
        manualRow.addView(address,new LinearLayout.LayoutParams(0,ui.dp(50),1));
        TextView use=ui.button(getString(R.string.manual_use),false,v->useManual());
        LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-2,ui.dp(50));up.leftMargin=ui.dp(10);manualRow.addView(use,up);
        pc.addView(manualRow);
        TextView again=ui.text(getString(R.string.search_again),15,Ui.ACCENT);again.setPadding(0,ui.dp(16),0,ui.dp(4));
        ui.focusable(again,8);again.setOnClickListener(v->{Ui.pulse(v);discovery.restart();});
        pc.addView(again);

        // Media
        ui.section(column,getString(R.string.section_media));
        LinearLayout mc=ui.card(column);
        TextView choose=ui.button(getString(R.string.choose_media),false,v->pick());
        mc.addView(choose,new LinearLayout.LayoutParams(-1,ui.dp(54)));
        mediaSummary=ui.text("",17,Ui.INK);mediaSummary.setPadding(0,ui.dp(16),0,0);mc.addView(mediaSummary);
        mediaNames=ui.text("",14,Ui.DIM);mediaNames.setPadding(0,ui.dp(8),0,0);mediaNames.setLineSpacing(0,1.2f);mc.addView(mediaNames);
        clear=ui.text(getString(R.string.clear),15,Ui.ACCENT);clear.setPadding(0,ui.dp(14),0,ui.dp(4));
        ui.focusable(clear,8);clear.setOnClickListener(v->{Ui.pulse(v);items.clear();setStatus(getString(R.string.status_idle),false);renderAll();});
        mc.addView(clear);

        // Mode
        ui.section(column,getString(R.string.section_mode));
        LinearLayout modes=new LinearLayout(this);
        modeShow=mode(modes,getString(R.string.mode_show),getString(R.string.mode_show_note),true);
        modeSave=mode(modes,getString(R.string.mode_save),getString(R.string.mode_save_note),false);
        column.addView(modes,new LinearLayout.LayoutParams(-1,-2));

        // Send and status
        send=ui.button(getString(R.string.send),true,v->send());
        send.setTextSize(20);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,ui.dp(60));sp.topMargin=ui.dp(28);column.addView(send,sp);

        LinearLayout sc=new LinearLayout(this);sc.setOrientation(LinearLayout.VERTICAL);sc.setPadding(ui.dp(4),ui.dp(18),ui.dp(4),0);
        status=ui.text(getString(R.string.status_idle),16,Ui.DIM);status.setLineSpacing(0,1.15f);sc.addView(status);
        bar=new Ui.Bar(this);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,ui.dp(8));bp.topMargin=ui.dp(14);sc.addView(bar,bp);
        bar.setVisibility(View.GONE);
        cancel=ui.button(getString(R.string.cancel),false,v->{if(sender!=null)sender.cancel();});
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,ui.dp(50));cp.topMargin=ui.dp(16);sc.addView(cancel,cp);
        cancel.setVisibility(View.GONE);
        column.addView(sc);

        TextView note=ui.text(getString(R.string.same_network),14,Ui.DIM);note.setGravity(Gravity.CENTER);note.setPadding(0,ui.dp(36),0,0);
        column.addView(note,new LinearLayout.LayoutParams(-1,-2));
        TextView footer=ui.text(getString(R.string.footer),13,0xff6f7782);footer.setGravity(Gravity.CENTER);footer.setPadding(0,ui.dp(10),0,0);
        column.addView(footer,new LinearLayout.LayoutParams(-1,-2));
        setContentView(scroll);
    }

    private LinearLayout mode(LinearLayout row,String title,String note,final boolean isShow){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(ui.dp(16),ui.dp(16),ui.dp(16),ui.dp(16));
        card.addView(ui.text(title,19,Ui.INK));
        TextView n=ui.text(note,13,Ui.DIM);n.setPadding(0,ui.dp(6),0,0);n.setLineSpacing(0,1.15f);card.addView(n);
        ui.focusable(card,18);
        card.setOnClickListener(v->{Ui.pulse(v);show=isShow;prefs.edit().putString("mode",show?"show":"save").apply();renderMode();});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);
        if(!isShow)p.leftMargin=ui.dp(12);
        row.addView(card,p);return card;
    }

    // ---- Rendering -------------------------------------------------------------------------

    private void renderAll(){renderProjectors();renderMedia();renderMode();renderSend();}

    private List<Discovery.Projector> allProjectors(){
        List<Discovery.Projector> all=new ArrayList<>(projectors);
        if(manual!=null){
            boolean dup=false;for(Discovery.Projector p:all)if(p.key().equals(manual.key()))dup=true;
            if(!dup)all.add(manual);
        }
        return all;
    }

    private void renderProjectors(){
        projectorList.removeAllViews();
        List<Discovery.Projector> all=allProjectors();
        for(final Discovery.Projector p:all){
            boolean on=p.key().equals(selected);
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(12));
            android.graphics.drawable.GradientDrawable g=ui.surface(on?Ui.CARD_HIGH:Ui.NEUTRAL,14);if(on)g.setStroke(ui.dp(2),Ui.ACCENT);row.setBackground(g);
            View dot=new View(this);dot.setBackground(ui.surface(on?Ui.ACCENT:0xff4a515c,6));
            row.addView(dot,new LinearLayout.LayoutParams(ui.dp(12),ui.dp(12)));
            LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);copy.setPadding(ui.dp(14),0,0,0);
            TextView name=ui.text(p==manual?getString(R.string.manual_name):p.name,18,Ui.INK);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);
            copy.addView(name);
            TextView where=ui.text(p.host+":"+p.port,13,Ui.DIM);where.setPadding(0,ui.dp(4),0,0);copy.addView(where);
            row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            ui.focusable(row,14);
            row.setOnClickListener(v->{Ui.pulse(v);selected=p.key();prefs.edit().putString("selected",selected).apply();renderProjectors();renderSend();});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=ui.dp(10);projectorList.addView(row,lp);
        }
        searching.setText(projectors.isEmpty()?getString(R.string.searching):getString(R.string.searching_more));
        renderSend();
    }

    private void renderMedia(){
        if(items.isEmpty()){
            mediaSummary.setText(R.string.media_none);mediaNames.setVisibility(View.GONE);clear.setVisibility(View.GONE);
        }else{
            long total=0;boolean unknown=false;
            for(Sender.Item i:items){if(i.size<0)unknown=true;else total+=i.size;}
            String size=Formatter.formatShortFileSize(this,total)+(unknown?"+":"");
            mediaSummary.setText(getResources().getQuantityString(R.plurals.media_summary,items.size(),items.size(),size));
            StringBuilder names=new StringBuilder();
            for(int i=0;i<items.size()&&i<5;i++){if(i>0)names.append('\n');names.append(items.get(i).name);}
            if(items.size()>5)names.append('\n').append(getString(R.string.more_items,items.size()-5));
            mediaNames.setText(names);mediaNames.setVisibility(View.VISIBLE);clear.setVisibility(sender==null?View.VISIBLE:View.GONE);
        }
        renderSend();
    }

    private void renderMode(){
        paintMode(modeShow,show);paintMode(modeSave,!show);
    }

    private void paintMode(LinearLayout card,boolean on){
        android.graphics.drawable.GradientDrawable g=ui.surface(on?Ui.CARD_HIGH:Ui.CARD,18);
        if(on)g.setStroke(ui.dp(2),Ui.ACCENT);
        card.setBackground(g);
    }

    private void renderSend(){
        if(send==null)return;
        boolean ready=sender==null&&!items.isEmpty()&&current()!=null;
        Ui.enable(send,ready);
    }

    private Discovery.Projector current(){
        for(Discovery.Projector p:allProjectors())if(p.key().equals(selected))return p;
        return null;
    }

    private void setStatus(String text,boolean error){
        status.setText(text);status.setTextColor(error?Ui.ERROR:Ui.INK);
    }

    // ---- Actions ---------------------------------------------------------------------------

    private void useManual(){
        Discovery.Projector p=parse(address.getText().toString());
        if(p==null){setStatus(getString(R.string.error_bad_address),true);return;}
        manual=p;selected=p.key();
        prefs.edit().putString("manual",p.host+":"+p.port).putString("selected",selected).apply();
        setStatus(getString(R.string.status_idle),false);
        renderProjectors();
    }

    /** Accepts "192.168.1.20", "192.168.1.20:8765", a host name, or an http:// address shown on the projector. */
    private Discovery.Projector parse(String raw){
        String s=raw==null?"":raw.trim();
        if(s.startsWith("http://"))s=s.substring(7);
        int slash=s.indexOf('/');if(slash>=0)s=s.substring(0,slash);
        if(s.isEmpty()||s.contains(" "))return null;
        int port=8765;
        int colon=s.lastIndexOf(':');
        if(colon>0&&s.indexOf(':')==colon){
            try{port=Integer.parseInt(s.substring(colon+1));}catch(NumberFormatException e){return null;}
            s=s.substring(0,colon);
        }
        if(port<=0||port>65535||!s.matches("[0-9A-Za-z.\\-]+"))return null;
        return new Discovery.Projector("manual",s,port);
    }

    private void pick(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        try{startActivityForResult(i,PICK);}
        catch(ActivityNotFoundException e){
            Intent g=new Intent(Intent.ACTION_GET_CONTENT);g.addCategory(Intent.CATEGORY_OPENABLE);g.setType("*/*");
            g.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"});g.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
            try{startActivityForResult(g,PICK);}catch(ActivityNotFoundException none){setStatus(getString(R.string.picker_missing),true);}
        }
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK||result!=RESULT_OK||data==null)return;
        List<Uri> uris=new ArrayList<>();
        ClipData clip=data.getClipData();
        if(clip!=null)for(int i=0;i<clip.getItemCount();i++){Uri u=clip.getItemAt(i).getUri();if(u!=null)uris.add(u);}
        else if(data.getData()!=null)uris.add(data.getData());
        add(uris,null);
        renderAll();
    }

    /** Photos and videos shared from a gallery or another app. */
    private void receive(Intent intent){
        if(intent==null)return;
        String action=intent.getAction();
        List<Uri> uris=new ArrayList<>();
        if(Intent.ACTION_SEND.equals(action)){
            Parcelable p=intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if(p instanceof Uri)uris.add((Uri)p);
        }else if(Intent.ACTION_SEND_MULTIPLE.equals(action)){
            ArrayList<Parcelable> list=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if(list!=null)for(Parcelable p:list)if(p instanceof Uri)uris.add((Uri)p);
        }else return;
        if(uris.isEmpty()&&intent.getClipData()!=null){
            ClipData clip=intent.getClipData();
            for(int i=0;i<clip.getItemCount();i++){Uri u=clip.getItemAt(i).getUri();if(u!=null)uris.add(u);}
        }
        if(sender!=null)return; // Do not change the list while sending.
        items.clear();
        add(uris,intent.getType());
    }

    private void add(List<Uri> uris,String fallbackType){
        int skipped=0;
        ContentResolver resolver=getContentResolver();
        for(Uri uri:uris){
            boolean dup=false;for(Sender.Item i:items)if(i.uri.equals(uri))dup=true;
            if(dup)continue;
            String name=null;long size=-1;
            try(Cursor c=resolver.query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                if(c!=null&&c.moveToFirst()){
                    int n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=c.getColumnIndex(OpenableColumns.SIZE);
                    if(n>=0&&!c.isNull(n))name=c.getString(n);
                    if(s>=0&&!c.isNull(s))size=c.getLong(s);
                }
            }catch(Exception ignored){}
            if(name==null||name.isEmpty())name=uri.getLastPathSegment()!=null?uri.getLastPathSegment():"file";
            String mime=null;
            try{mime=resolver.getType(uri);}catch(Exception ignored){}
            if(mime==null||mime.endsWith("/*")||"application/octet-stream".equals(mime)){
                int dot=name.lastIndexOf('.');
                String guess=dot>=0?MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(Locale.ROOT)):null;
                if(guess!=null)mime=guess;
                else if(fallbackType!=null&&!fallbackType.endsWith("/*"))mime=fallbackType;
            }
            if(mime==null||!(mime.startsWith("image/")||mime.startsWith("video/"))){skipped++;continue;}
            items.add(new Sender.Item(uri,name,mime.toLowerCase(Locale.ROOT),size));
        }
        if(skipped>0)setStatus(getResources().getQuantityString(R.plurals.skipped_items,skipped,skipped),true);
        else if(!items.isEmpty())setStatus(getString(R.string.status_idle),false);
    }

    private void send(){
        Discovery.Projector target=current();
        if(sender!=null||target==null||items.isEmpty())return;
        bar.set(0);bar.setVisibility(View.VISIBLE);cancel.setVisibility(View.VISIBLE);clear.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final android.os.PowerManager.WakeLock wake=((android.os.PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"GabOSShare:send");
        // Phones without Wi-Fi (Ethernet tablets, emulators) have no WifiManager.
        android.net.wifi.WifiManager wm=(android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        final android.net.wifi.WifiManager.WifiLock wifi=wm==null?null:wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,"GabOSShare:send");
        try{wake.acquire(60*60*1000L);if(wifi!=null)wifi.acquire();}catch(RuntimeException ignored){}
        sender=new Sender(this,Discovery.authority(target.host,target.port),senderName(),show,new ArrayList<>(items),new Sender.Callback(){
            @Override public void status(String text,boolean error){setStatus(text,error);}
            @Override public void progress(float f){bar.set(f);}
            @Override public void finished(boolean success){
                sender=null;
                try{if(wake.isHeld())wake.release();if(wifi!=null&&wifi.isHeld())wifi.release();}catch(RuntimeException ignored){}
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                cancel.setVisibility(View.GONE);
                if(success)items.clear();else bar.setVisibility(View.GONE);
                renderMedia();
            }
        });
        renderSend();
        new Thread(sender,"GabOSShare-send").start();
    }

    /** The phone's own name as the projector should show it. */
    private String senderName(){
        String name=null;
        try{if(Build.VERSION.SDK_INT>=25)name=Settings.Global.getString(getContentResolver(),"device_name");}catch(RuntimeException ignored){}
        if(name==null||name.trim().isEmpty()){
            String model=Build.MODEL==null?"":Build.MODEL;
            String maker=Build.MANUFACTURER==null?"":Build.MANUFACTURER;
            name=model.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT))?model:maker+" "+model;
            if(!name.isEmpty())name=Character.toUpperCase(name.charAt(0))+name.substring(1);
        }
        name=name.trim();
        return name.isEmpty()?getString(R.string.default_sender):name;
    }
}
