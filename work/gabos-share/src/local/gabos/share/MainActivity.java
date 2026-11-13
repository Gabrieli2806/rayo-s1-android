package local.gabos.share;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The launcher tile: how to send from a phone or a browser, with this
 * projector's name, address and the receiver status. Opening it also makes
 * sure the receiver service runs.
 */
public class MainActivity extends Activity {
    private final Handler handler=new Handler();
    private TextView name, address, status, browser;
    private ContentObserver themeObserver;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        ShareService.start(this);
        build();
        themeObserver=new ContentObserver(handler){@Override public void onChange(boolean self){recreate();}};
        try{getContentResolver().registerContentObserver(ShareTheme.URI,false,themeObserver);}catch(RuntimeException ignored){}
        if(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
            try{requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,Manifest.permission.READ_EXTERNAL_STORAGE},1);}catch(RuntimeException ignored){}
        }
    }

    private void build(){
        ShareTheme theme=ShareTheme.load(this);
        Ui ui=new Ui(this,theme);
        ui.immersive();
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(true);scroll.setBackground(theme.background());
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setClipChildren(false);column.setClipToPadding(false);
        column.setPadding(ui.dp(56),ui.dp(36),ui.dp(56),ui.dp(48));
        scroll.addView(column,new ScrollView.LayoutParams(-1,-2));

        column.addView(ui.text(getString(R.string.app_name),40,theme.ink));
        TextView subtitle=ui.text(getString(R.string.main_subtitle),18,theme.dim);subtitle.setPadding(0,ui.dp(8),0,0);column.addView(subtitle);

        LinearLayout stats=row(column,ui,30);
        name=ui.stat(stats,getString(R.string.caption_name),"",1.2f);
        address=ui.stat(stats,getString(R.string.caption_address),"",1f);
        status=ui.stat(stats,getString(R.string.caption_status),"",1.4f);
        ((LinearLayout.LayoutParams)((View)status.getParent()).getLayoutParams()).rightMargin=0;

        ui.section(column,getString(R.string.how_title),36);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(ui.dp(24),ui.dp(20),ui.dp(24),ui.dp(20));
        card.setBackground(theme.surface(ui.dp(8)));
        int[] steps={R.string.how_step1,R.string.how_step2,R.string.how_step3};
        for(int i=0;i<steps.length;i++){
            LinearLayout line=new LinearLayout(this);line.setPadding(0,i==0?0:ui.dp(14),0,0);
            TextView number=ui.text(String.valueOf(i+1),20,theme.accent);number.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            line.addView(number,new LinearLayout.LayoutParams(ui.dp(34),-2));
            line.addView(ui.text(getString(steps[i]),20,theme.ink),new LinearLayout.LayoutParams(0,-2,1));
            card.addView(line);
        }
        column.addView(card,new LinearLayout.LayoutParams(-1,-2));
        browser=ui.text("",18,theme.dim);browser.setPadding(0,ui.dp(16),0,0);column.addView(browser);
        TextView privacy=ui.text(getString(R.string.privacy_note),16,theme.dim);privacy.setPadding(0,ui.dp(10),0,0);column.addView(privacy);

        LinearLayout actions=row(column,ui,28);
        View first=null;
        if(!canDrawOverlays()){
            first=ui.button(actions,getString(R.string.allow_prompts),true,v->openOverlaySettings());
        }
        View close=ui.button(actions,getString(R.string.close),first==null,v->finish());
        if(first==null)first=close;

        TextView footer=ui.text(getString(R.string.footer),14,theme.dim);footer.setPadding(0,ui.dp(36),0,0);column.addView(footer);
        setContentView(scroll);
        first.requestFocus();
        refresh();
    }

    private LinearLayout row(LinearLayout column,Ui ui,int topDp){
        LinearLayout r=new LinearLayout(this);r.setClipChildren(false);r.setClipToPadding(false);r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,ui.dp(topDp),0,0);column.addView(r,p);return r;
    }

    private boolean canDrawOverlays(){
        try{return Settings.canDrawOverlays(this);}catch(RuntimeException e){return true;}
    }

    private void openOverlaySettings(){
        try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}
        catch(RuntimeException e){
            android.widget.Toast.makeText(this,R.string.overlay_unavailable,android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private final Runnable ticker=new Runnable(){public void run(){refresh();handler.postDelayed(this,1000);}};

    private void refresh(){
        if(name==null)return;
        name.setText(Net.deviceName(this));
        String ip=Net.localAddress();
        address.setText(ip==null?getString(R.string.no_network):ip+":"+ShareService.PORT);
        status.setText(ShareService.status(this));
        browser.setText(ip==null?getString(R.string.connect_wifi):getString(R.string.browser_hint,"http://"+ip+":"+ShareService.PORT));
    }

    @Override protected void onResume(){
        super.onResume();
        handler.post(ticker);
        // If a Show transfer finished while its viewer could not open, show it now.
        Transfer pending=ShareService.pendingShow();
        if(pending!=null){
            startActivity(new Intent(this,ViewerActivity.class).putExtra(ViewerActivity.EXTRA_ID,pending.id));
        }
    }

    @Override protected void onPause(){handler.removeCallbacks(ticker);super.onPause();}

    @Override protected void onDestroy(){
        if(themeObserver!=null)try{getContentResolver().unregisterContentObserver(themeObserver);}catch(RuntimeException ignored){}
        super.onDestroy();
    }

    @Override public void finish(){super.finish();overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);}
}
