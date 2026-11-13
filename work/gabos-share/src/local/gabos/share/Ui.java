package local.gabos.share;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The small part of the GabOS look this app needs (copied from the shared
 * GabosUi kit so the app stays self-contained): Home margins, rounded cards,
 * the white focus ring with slight growth and quiet fades, coloured from the
 * shared theme.
 */
final class Ui {
    final Activity activity;
    final ShareTheme theme;

    Ui(Activity activity,ShareTheme theme){this.activity=activity;this.theme=theme;}

    int dp(float value){return (int)(value*activity.getResources().getDisplayMetrics().density+.5f);}

    void fade(){activity.overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);}

    void immersive(){
        activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    TextView text(CharSequence value,float size,int color){
        TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);return t;
    }

    TextView section(LinearLayout column,String label,int topDp){
        TextView t=text(label.toUpperCase(),15,theme.dim);t.setLetterSpacing(.12f);t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(topDp),0,0);column.addView(t,p);return t;
    }

    /** The Home focus treatment: outline, slight growth, raised above neighbours. */
    void focusable(View view,float radius){
        view.setFocusable(true);view.setClickable(true);
        final GradientDrawable ring=new GradientDrawable();ring.setColor(Color.TRANSPARENT);ring.setCornerRadius(dp(radius));ring.setStroke(dp(3),theme.focus);
        view.setOnFocusChangeListener((v,focus)->{
            v.setForeground(focus?ring:null);v.setElevation(focus?dp(8):0);
            v.animate().scaleX(focus?1.05f:1f).scaleY(focus?1.05f:1f).setDuration(110).start();
        });
    }

    static void pulse(View v){
        v.animate().cancel();v.setScaleX(.94f);v.setScaleY(.94f);
        v.animate().scaleX(v.isFocused()?1.05f:1f).scaleY(v.isFocused()?1.05f:1f).setDuration(160).start();
    }

    /** A pill button; primary ones use the accent colour. */
    TextView button(LinearLayout row,String label,boolean primary,View.OnClickListener action){
        TextView b=text(label,20,primary?theme.onAccent:theme.ink);b.setGravity(Gravity.CENTER);b.setPadding(dp(30),0,dp(30),0);
        GradientDrawable g=new GradientDrawable();g.setCornerRadius(dp(26));g.setColor(primary?theme.accent:theme.neutral);b.setBackground(g);
        focusable(b,26);b.setOnClickListener(v->{pulse(v);action.onClick(v);});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(52));p.setMargins(0,0,dp(18),0);row.addView(b,p);return b;
    }

    /** A value card: small caption above a large value. */
    TextView stat(LinearLayout row,String caption,String value,float weight){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20),dp(14),dp(20),dp(14));card.setBackground(theme.surface(dp(8)));
        TextView label=text(caption,14,theme.dim);label.setLetterSpacing(.08f);card.addView(label);
        TextView number=text(value,24,theme.ink);number.setPadding(0,dp(10),0,0);number.setSingleLine(true);number.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(number);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(106),weight);p.setMargins(0,0,dp(22),0);row.addView(card,p);return number;
    }
}
