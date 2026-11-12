package local.gabos.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Shared GabOS look for the system's own applications: the Home page geometry,
 * palette, focus ring and quiet fades, so moving between Home and an app reads
 * as one interface. Compiled into each application; no shared process or library.
 */
public final class GabosUi {
    public static final int INK=0xfff1f3f4, DIM=0xff9aa0a6, CARD=0xff1b222d, FOCUS=0xffffffff;
    public static final int BLUE_TOP=0xff1a47a0, BLUE_BOTTOM=0xff0f2f73, WARN=0xffffb36b;
    public static final int[] TINTS={0xff3b4a8f,0xff2f6f73,0xff7a4a2e,0xff5b3f86,0xff2e5f8a,0xff7b3550,0xff46683a,0xff6a5a2c};
    private final Activity activity;

    public GabosUi(Activity activity){this.activity=activity;}

    public int dp(float value){return (int)(value*activity.getResources().getDisplayMetrics().density+.5f);}

    public static GradientDrawable background(){
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff0d0f13,0xff0e131c,0xff101d36});
    }

    /** Use for every start and finish so windows cross-fade instead of sliding. */
    public void fade(){activity.overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);}

    public void immersive(){
        activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    public TextView text(String value,float size,int color){
        TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);return t;
    }

    /** A scrolling page with the Home margins and a title; returns the content column. */
    public LinearLayout page(String title,String subtitle){
        immersive();
        ScrollView scroll=new ScrollView(activity);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);scroll.setFillViewport(true);
        scroll.setBackground(background());
        LinearLayout column=new LinearLayout(activity);column.setOrientation(LinearLayout.VERTICAL);column.setClipChildren(false);column.setClipToPadding(false);
        column.setPadding(dp(58),dp(40),dp(58),dp(40));scroll.addView(column,new ScrollView.LayoutParams(-1,-2));
        TextView heading=text(title,32,INK);column.addView(heading,new LinearLayout.LayoutParams(-1,dp(48)));
        if(subtitle!=null){TextView note=text(subtitle,14,DIM);column.addView(note);}
        activity.setContentView(scroll);return column;
    }

    public LinearLayout row(LinearLayout column,int topDp){
        LinearLayout r=new LinearLayout(activity);r.setClipChildren(false);r.setClipToPadding(false);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(topDp),0,0);column.addView(r,p);return r;
    }

    public TextView section(LinearLayout column,String label){
        TextView t=text(label,12,DIM);t.setLetterSpacing(.12f);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(30),0,0);column.addView(t,p);return t;
    }

    /** The Home focus treatment: white outline, slight growth, raised above neighbours. */
    public void focusable(final View view,final float radius){
        view.setFocusable(true);view.setClickable(true);
        final GradientDrawable ring=new GradientDrawable();ring.setColor(Color.TRANSPARENT);ring.setCornerRadius(dp(radius));ring.setStroke(dp(2),FOCUS);
        view.setOnFocusChangeListener((v,focus)->{
            v.setForeground(focus?ring:null);v.setElevation(focus?dp(8):0);
            v.animate().scaleX(focus?1.06f:1f).scaleY(focus?1.06f:1f).setDuration(110).start();
        });
    }

    /** A Home-sized tile with a label, added to a row. */
    public View tile(LinearLayout row,String label,int top,int bottom,View.OnClickListener action){
        FrameLayout tile=new FrameLayout(activity);
        GradientDrawable fill=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});fill.setCornerRadius(dp(6));tile.setBackground(fill);
        TextView t=text(label,16,INK);t.setGravity(Gravity.CENTER);tile.addView(t,new FrameLayout.LayoutParams(-1,-1));
        tile.setContentDescription(label);focusable(tile,6);tile.setOnClickListener(action);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(148),dp(83));p.setMargins(0,0,dp(18),0);row.addView(tile,p);return tile;
    }

    public View tile(LinearLayout row,String label,View.OnClickListener action){return tile(row,label,0xff25303f,0xff1a222d,action);}

    /** A value card: small caption above a large value, in the Home card surface. */
    public TextView stat(LinearLayout row,String caption,String value){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16),dp(12),dp(16),dp(12));
        GradientDrawable surface=new GradientDrawable();surface.setColor(CARD);surface.setCornerRadius(dp(6));card.setBackground(surface);
        TextView label=text(caption,12,DIM);label.setLetterSpacing(.08f);card.addView(label);
        TextView number=text(value,22,INK);number.setPadding(0,dp(8),0,0);number.setTypeface(Typeface.DEFAULT);card.addView(number);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(214),dp(83));p.setMargins(0,0,dp(18),0);row.addView(card,p);return number;
    }

    /** The wide Home-style information card; returns it for the caller to fill. */
    public LinearLayout card(LinearLayout column,int topDp){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(22),dp(20),dp(22),dp(20));
        GradientDrawable surface=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xff1a2231,0xff18294a});surface.setCornerRadius(dp(6));card.setBackground(surface);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(topDp),0,0);column.addView(card,p);return card;
    }
}
