package local.gabos.ui;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Shared GabOS look for the system's own applications: the Home page geometry,
 * palette, focus ring and quiet fades, so moving between Home and an app reads
 * as one interface. Sizes are chosen for reading a projected image from a sofa.
 * Compiled into each application; no shared process or library.
 */
public final class GabosUi {
    public static final int INK=0xfff1f3f4, DIM=0xffaab0b8, CARD=0xff1b222d, FOCUS=0xffffffff, ACCENT=0xff8ab4f8;
    public static final int BLUE_TOP=0xff1a47a0, BLUE_BOTTOM=0xff0f2f73, WARN=0xffffb36b;
    public static final int[] TINTS={0xff3b4a8f,0xff2f6f73,0xff7a4a2e,0xff5b3f86,0xff2e5f8a,0xff7b3550,0xff46683a,0xff6a5a2c};
    public static final int TILE_W=188, TILE_H=106, GAP=22;
    private final Activity activity;

    public interface IntChange{void onChange(int value);}

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
        column.setPadding(dp(56),dp(36),dp(56),dp(48));scroll.addView(column,new ScrollView.LayoutParams(-1,-2));
        TextView heading=text(title,40,INK);column.addView(heading,new LinearLayout.LayoutParams(-1,dp(56)));
        if(subtitle!=null){TextView note=text(subtitle,18,DIM);note.setPadding(0,dp(4),0,0);column.addView(note);}
        activity.setContentView(scroll);return column;
    }

    public LinearLayout row(LinearLayout column,int topDp){
        LinearLayout r=new LinearLayout(activity);r.setClipChildren(false);r.setClipToPadding(false);r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(topDp),0,0);column.addView(r,p);return r;
    }

    public TextView section(LinearLayout column,String label){
        TextView t=text(label,15,DIM);t.setLetterSpacing(.12f);t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(36),0,0);column.addView(t,p);return t;
    }

    /** The Home focus treatment: white outline, slight growth, raised above neighbours. */
    public void focusable(final View view,final float radius){
        view.setFocusable(true);view.setClickable(true);
        final GradientDrawable ring=new GradientDrawable();ring.setColor(Color.TRANSPARENT);ring.setCornerRadius(dp(radius));ring.setStroke(dp(3),FOCUS);
        view.setOnFocusChangeListener((v,focus)->{
            v.setForeground(focus?ring:null);v.setElevation(focus?dp(8):0);
            v.animate().scaleX(focus?1.05f:1f).scaleY(focus?1.05f:1f).setDuration(110).start();
        });
    }

    /** Brief press confirmation for clickable surfaces. */
    public static void pulse(View v){
        v.animate().cancel();v.setScaleX(.94f);v.setScaleY(.94f);
        v.animate().scaleX(v.isFocused()?1.05f:1f).scaleY(v.isFocused()?1.05f:1f).setDuration(160).start();
    }

    /** A Home-sized tile with a label, added to a row. */
    public View tile(LinearLayout row,String label,int top,int bottom,View.OnClickListener action){
        FrameLayout tile=new FrameLayout(activity);
        GradientDrawable fill=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});fill.setCornerRadius(dp(8));tile.setBackground(fill);
        TextView t=text(label,20,INK);t.setGravity(Gravity.CENTER);t.setPadding(dp(8),0,dp(8),0);tile.addView(t,new FrameLayout.LayoutParams(-1,-1));
        tile.setContentDescription(label);focusable(tile,8);tile.setOnClickListener(v->{pulse(v);action.onClick(v);});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(TILE_W),dp(TILE_H));p.setMargins(0,0,dp(GAP),0);row.addView(tile,p);return tile;
    }

    public View tile(LinearLayout row,String label,View.OnClickListener action){return tile(row,label,0xff25303f,0xff1a222d,action);}

    /** A value card: small caption above a large value, in the Home card surface. */
    public TextView stat(LinearLayout row,String caption,String value){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18),dp(12),dp(18),dp(12));
        GradientDrawable surface=new GradientDrawable();surface.setColor(CARD);surface.setCornerRadius(dp(8));card.setBackground(surface);
        TextView label=text(caption,14,DIM);label.setLetterSpacing(.08f);card.addView(label);
        TextView number=text(value,26,INK);number.setPadding(0,dp(10),0,0);number.setSingleLine(true);card.addView(number);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(TILE_H),1);p.setMargins(0,0,dp(GAP),0);row.addView(card,p);return number;
    }

    /** The wide Home-style information card; returns it for the caller to fill. */
    public LinearLayout card(LinearLayout column,int topDp){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(24),dp(22),dp(24),dp(22));
        GradientDrawable surface=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xff1a2231,0xff18294a});surface.setCornerRadius(dp(8));card.setBackground(surface);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(topDp),0,0);column.addView(card,p);return card;
    }

    /** A full-width settings row: title and summary on the left, a control on the right. */
    public LinearLayout settingRow(LinearLayout column,String title,String summary){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(24),dp(14),dp(24),dp(14));
        row.setClipChildren(false);row.setClipToPadding(false);
        GradientDrawable surface=new GradientDrawable();surface.setColor(CARD);surface.setCornerRadius(dp(8));row.setBackground(surface);
        LinearLayout copy=new LinearLayout(activity);copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(title,22,INK));
        if(summary!=null){TextView s=text(summary,16,DIM);s.setPadding(0,dp(6),0,0);copy.addView(s);}
        row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(12),0,0);column.addView(row,p);return row;
    }

    /** Slider for pointer and D-pad: left/right steps, OK does nothing, pointer sets directly. */
    public View slider(LinearLayout row,int min,int max,int step,int value,final IntChange change){
        final SliderView s=new SliderView(min,max,step,value,change);
        focusable(s,20);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(420),dp(48));p.setMargins(dp(24),0,0,0);row.addView(s,p);return s;
    }

    /** On/off switch as a pill; returns the view for focus handling. */
    public View toggle(LinearLayout row,boolean on,final IntChange change){
        final boolean[] state={on};
        final TextView pill=text(on?"On":"Off",18,on?0xff0d0f13:INK);pill.setGravity(Gravity.CENTER);
        final Runnable paint=()->{GradientDrawable g=new GradientDrawable();g.setCornerRadius(dp(22));g.setColor(state[0]?ACCENT:0xff2a2f37);pill.setBackground(g);
            pill.setText(state[0]?"On":"Off");pill.setTextColor(state[0]?0xff0d0f13:INK);};
        paint.run();focusable(pill,22);
        pill.setOnClickListener(v->{pulse(v);state[0]=!state[0];paint.run();change.onChange(state[0]?1:0);});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(120),dp(44));p.setMargins(dp(24),0,0,0);row.addView(pill,p);return pill;
    }

    /** A row of mutually exclusive pills. Returns the pill views. */
    public View[] choices(LinearLayout row,String[] labels,int selected,final IntChange change){
        final TextView[] pills=new TextView[labels.length];final int[] current={selected};
        final Runnable paint=()->{for(int i=0;i<pills.length;i++){GradientDrawable g=new GradientDrawable();g.setCornerRadius(dp(22));
            g.setColor(i==current[0]?ACCENT:0xff2a2f37);pills[i].setBackground(g);pills[i].setTextColor(i==current[0]?0xff0d0f13:INK);}};
        for(int i=0;i<labels.length;i++){
            final int index=i;TextView pill=text(labels[i],17,INK);pill.setGravity(Gravity.CENTER);pill.setPadding(dp(18),0,dp(18),0);pills[i]=pill;
            focusable(pill,22);pill.setOnClickListener(v->{pulse(v);current[0]=index;paint.run();change.onChange(index);});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(44));p.setMargins(dp(12),0,0,0);row.addView(pill,p);
        }
        paint.run();return pills;
    }

    private final class SliderView extends View{
        final int min,max,step;int value;final IntChange change;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        SliderView(int min,int max,int step,int value,IntChange change){super(activity);this.min=min;this.max=max;this.step=step;this.value=value;this.change=change;}
        void set(int v){v=Math.max(min,Math.min(max,v));if(v!=value){value=v;invalidate();change.onChange(v);}}
        @Override public boolean onKeyDown(int code,KeyEvent e){
            if(code==KeyEvent.KEYCODE_DPAD_LEFT){set(value-step);return true;}
            if(code==KeyEvent.KEYCODE_DPAD_RIGHT){set(value+step);return true;}
            return super.onKeyDown(code,e);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            float pad=dp(24),w=getWidth()-pad*2-dp(70);
            float f=Math.max(0,Math.min(1,(e.getX()-pad)/w));
            set(min+Math.round(f*(max-min)/step)*step);requestFocus();return true;
        }
        @Override public boolean onGenericMotionEvent(MotionEvent e){
            if(e.getAction()==MotionEvent.ACTION_SCROLL){set(value+(e.getAxisValue(MotionEvent.AXIS_VSCROLL)>0?step:-step));return true;}
            return super.onGenericMotionEvent(e);
        }
        @Override protected void onDraw(Canvas c){
            float pad=dp(24),label=dp(70),w=getWidth()-pad*2-label,cy=getHeight()/2f,f=(value-min)/(float)(max-min);
            paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(dp(6));
            paint.setColor(0xff3a414c);c.drawLine(pad,cy,pad+w,cy,paint);
            paint.setColor(ACCENT);c.drawLine(pad,cy,pad+w*f,cy,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(isFocused()?FOCUS:0xffd2e3fc);c.drawCircle(pad+w*f,cy,dp(isFocused()?12:10),paint);
            paint.setColor(INK);paint.setTextSize(dp(20));paint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.valueOf(value),getWidth()-dp(14),cy+dp(7),paint);
        }
    }
}
