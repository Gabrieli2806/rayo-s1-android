package local.gabos.share;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The approval prompt over whatever is on screen: "<sender> wants to show 3
 * photos", Accept (focused) or Decline. Closes itself when the offer times out
 * or is withdrawn. Back means Decline.
 */
public class ApproveActivity extends Activity {
    static final String EXTRA_ID="id";
    private final Handler handler=new Handler();
    private String id;
    private boolean answered;
    private TextView countdown;
    private Transfer transfer;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        id=getIntent().getStringExtra(EXTRA_ID);
        transfer=ShareService.transfer(id);
        if(transfer==null||transfer.state!=Transfer.AWAITING){finish();return;}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON|WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        build();
        handler.post(tick);
    }

    /**
     * singleTop: a new offer can arrive while the previous prompt is still on top
     * (for example just after it timed out). Switch to the new offer instead of
     * letting the old instance finish and swallow it.
     */
    @Override protected void onNewIntent(android.content.Intent intent){
        super.onNewIntent(intent);
        String next=intent.getStringExtra(EXTRA_ID);
        Transfer t=ShareService.transfer(next);
        if(t==null||t.state!=Transfer.AWAITING||next.equals(id))return;
        setIntent(intent);
        handler.removeCallbacks(tick);
        id=next;transfer=t;answered=false;
        build();
        handler.post(tick);
    }

    private void build(){
        ShareTheme theme=ShareTheme.load(this);
        Ui ui=new Ui(this,theme);
        ui.immersive();
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(theme.scrim);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(ui.dp(40),ui.dp(34),ui.dp(40),ui.dp(30));card.setBackground(theme.surface(ui.dp(16)));card.setElevation(ui.dp(12));
        card.setClipChildren(false);card.setClipToPadding(false);

        TextView app=ui.text(getString(R.string.app_name).toUpperCase(),14,theme.accent);app.setLetterSpacing(.14f);app.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(app);
        TextView title=ui.text(question(),30,theme.ink);title.setGravity(Gravity.CENTER);title.setPadding(0,ui.dp(16),0,0);title.setLineSpacing(0,1.1f);
        card.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView size=ui.text(Formatter.formatShortFileSize(this,transfer.totalBytes())+" · "
            +getString(transfer.show?R.string.approve_show_note:R.string.approve_save_note),18,theme.dim);
        size.setGravity(Gravity.CENTER);size.setPadding(0,ui.dp(12),0,0);card.addView(size,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout buttons=new LinearLayout(this);buttons.setGravity(Gravity.CENTER);buttons.setClipChildren(false);buttons.setClipToPadding(false);
        buttons.setPadding(ui.dp(18),ui.dp(30),0,ui.dp(6));
        TextView accept=ui.button(buttons,getString(R.string.accept),true,v->answer(true));
        ui.button(buttons,getString(R.string.decline),false,v->answer(false));
        card.addView(buttons,new LinearLayout.LayoutParams(-2,-2));
        countdown=ui.text("",15,theme.dim);countdown.setPadding(0,ui.dp(12),0,0);card.addView(countdown);

        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(ui.dp(640),-2,Gravity.CENTER);
        root.addView(card,p);
        setContentView(root);
        accept.requestFocus();
    }

    private String question(){
        int n=transfer.items.length,videos=transfer.videos(),photos=transfer.photos();
        int res;
        if(videos==0)res=transfer.show?R.plurals.ask_show_photos:R.plurals.ask_save_photos;
        else if(photos==0)res=transfer.show?R.plurals.ask_show_videos:R.plurals.ask_save_videos;
        else res=transfer.show?R.plurals.ask_show_items:R.plurals.ask_save_items;
        return getResources().getQuantityString(res,n,transfer.sender,n);
    }

    private final Runnable tick=new Runnable(){public void run(){
        Transfer t=ShareService.transfer(id);
        if(t==null||t.state!=Transfer.AWAITING){answered=true;finish();return;}
        long left=Math.max(0,(t.created+ShareService.DECISION_MS-System.currentTimeMillis()+999)/1000);
        if(countdown!=null)countdown.setText(getString(R.string.approve_countdown,left));
        handler.postDelayed(this,500);
    }};

    private void answer(boolean accept){
        if(answered)return;
        answered=true;
        ShareService.decide(id,accept);
        finish();
    }

    @Override public void onBackPressed(){answer(false);}

    @Override protected void onDestroy(){handler.removeCallbacks(tick);super.onDestroy();}

    @Override public void finish(){super.finish();overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);}
}
