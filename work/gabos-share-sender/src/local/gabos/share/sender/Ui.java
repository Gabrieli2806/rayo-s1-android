package local.gabos.share.sender;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The GabOS look adapted to a portrait phone: the Home dark gradient, rounded
 * cards, the GabOS accent and large clean type, with touch-sized targets.
 */
final class Ui {
    static final int INK=0xfff1f3f4, DIM=0xffaab0b8, CARD=0xff1b222d, CARD_HIGH=0xff23406e, NEUTRAL=0xff2a2f37,
        ACCENT=0xff8ab4f8, ON_ACCENT=0xff0d0f13, ERROR=0xffff8a80, OK=0xff6fdc8c;

    final Activity activity;
    Ui(Activity activity){this.activity=activity;}

    int dp(float value){return (int)(value*activity.getResources().getDisplayMetrics().density+.5f);}

    static GradientDrawable background(){
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff0d0f13,0xff0e131c,0xff101d36});
    }

    GradientDrawable surface(int color,float radiusDp){
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radiusDp));return g;
    }

    TextView text(CharSequence value,float size,int color){
        TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);return t;
    }

    TextView section(LinearLayout column,String label){
        TextView t=text(label.toUpperCase(),13,DIM);t.setLetterSpacing(.12f);t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(4),dp(28),0,dp(10));column.addView(t,p);return t;
    }

    LinearLayout card(LinearLayout column){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(18),dp(18),dp(18),dp(18));
        card.setBackground(surface(CARD,18));
        column.addView(card,new LinearLayout.LayoutParams(-1,-2));return card;
    }

    /** Focus ring for keyboards and D-pads (Chromebooks, TV remotes); touch uses the press pulse. */
    void focusable(View view,float radius){
        view.setFocusable(true);view.setClickable(true);
        if(Build.VERSION.SDK_INT<23)return;
        final GradientDrawable ring=new GradientDrawable();ring.setColor(Color.TRANSPARENT);ring.setCornerRadius(dp(radius));ring.setStroke(dp(3),0xffffffff);
        view.setOnFocusChangeListener((v,focus)->v.setForeground(focus&&!v.isInTouchMode()?ring:null));
    }

    static void pulse(View v){
        v.animate().cancel();v.setScaleX(.95f);v.setScaleY(.95f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
    }

    /** A pill button. Primary uses the accent; returns the view so callers can enable or disable it. */
    TextView button(String label,boolean primary,View.OnClickListener action){
        TextView b=text(label,18,primary?ON_ACCENT:INK);b.setGravity(Gravity.CENTER);b.setPadding(dp(22),0,dp(22),0);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setBackground(surface(primary?ACCENT:NEUTRAL,28));
        focusable(b,28);b.setOnClickListener(v->{if(!v.isEnabled())return;pulse(v);action.onClick(v);});
        return b;
    }

    static void enable(View v,boolean on){v.setEnabled(on);v.setAlpha(on?1f:.45f);}

    /** A thin rounded progress bar in the accent colour. */
    static final class Bar extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float fraction;
        Bar(Activity a){super(a);}
        void set(float f){fraction=Math.max(0,Math.min(1,f));invalidate();}
        @Override protected void onDraw(Canvas c){
            float h=getHeight(),w=getWidth(),r=h/2;
            paint.setColor(NEUTRAL);c.drawRoundRect(new RectF(0,0,w,h),r,r,paint);
            if(fraction>0){paint.setColor(ACCENT);c.drawRoundRect(new RectF(0,0,Math.max(h,w*fraction),h),r,r,paint);}
        }
    }
}
