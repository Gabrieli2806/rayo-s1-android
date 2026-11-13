package local.gabos.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
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

    /** A scrolling page with the Home margins, a title and the battery; returns the content column. */
    public LinearLayout page(String title,String subtitle){
        immersive();
        ScrollView scroll=new ScrollView(activity);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);scroll.setFillViewport(true);
        LinearLayout column=new LinearLayout(activity);column.setOrientation(LinearLayout.VERTICAL);column.setClipChildren(false);column.setClipToPadding(false);
        column.setPadding(dp(56),dp(36),dp(56),dp(48));scroll.addView(column,new ScrollView.LayoutParams(-1,-2));
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=text(title,40,INK);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(new BatteryRing(),new LinearLayout.LayoutParams(dp(58),dp(58)));
        column.addView(header,new LinearLayout.LayoutParams(-1,dp(60)));
        if(subtitle!=null){TextView note=text(subtitle,18,DIM);note.setPadding(0,dp(4),0,0);column.addView(note);}
        FrameLayout root=assist(scroll);root.setBackground(background());
        activity.setContentView(root);return column;
    }

    /** Equal tile width for a number of columns across the current screen. */
    public int tileWidthDp(int columns,int marginDp,int gapDp){
        float width=activity.getResources().getDisplayMetrics().widthPixels/activity.getResources().getDisplayMetrics().density;
        return (int)((width-2*marginDp-(columns-1)*gapDp)/columns);
    }

    /**
     * Scrolling help for the projector's touch panel and buttons: large up/down
     * buttons appear at the right edge when there is more to see; resting the
     * pointer on one scrolls continuously, clicking scrolls one page, and wheel
     * steps move further than the platform default.
     */
    public FrameLayout assist(final ScrollView scroll){
        FrameLayout root=new FrameLayout(activity);root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        final View up=arrow(true,scroll),down=arrow(false,scroll);
        FrameLayout.LayoutParams pu=new FrameLayout.LayoutParams(dp(64),dp(64),Gravity.RIGHT|Gravity.TOP);pu.setMargins(0,dp(84),dp(10),0);
        FrameLayout.LayoutParams pd=new FrameLayout.LayoutParams(dp(64),dp(64),Gravity.RIGHT|Gravity.BOTTOM);pd.setMargins(0,0,dp(10),dp(16));
        root.addView(up,pu);root.addView(down,pd);
        final Runnable update=()->{
            View child=scroll.getChildCount()>0?scroll.getChildAt(0):null;
            int range=child==null?0:Math.max(0,child.getHeight()-scroll.getHeight());
            up.setVisibility(scroll.getScrollY()>4?View.VISIBLE:View.GONE);
            down.setVisibility(scroll.getScrollY()<range-4?View.VISIBLE:View.GONE);
        };
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->update.run());
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(update::run);
        scroll.setOnGenericMotionListener((v,e)->{
            if(e.getAction()!=MotionEvent.ACTION_SCROLL)return false;
            float steps=e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if(steps==0)return false;
            scroll.smoothScrollBy(0,(int)(-steps*dp(140)));return true;
        });
        return root;
    }

    private View arrow(final boolean up,final ScrollView scroll){
        final Handler handler=new Handler(Looper.getMainLooper());
        final View button=new View(activity){
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c){
                float w=getWidth(),h=getHeight();
                paint.setStyle(Paint.Style.FILL);paint.setColor(isHovered()||isFocused()?0xeee8eaed:0xb0262d38);c.drawCircle(w/2,h/2,w/2,paint);
                paint.setColor(isHovered()||isFocused()?0xff0d0f13:INK);
                Path chevron=new Path();float s=w*.18f,cy=h/2+(up?s/2:-s/2);
                chevron.moveTo(w/2-s*1.3f,cy);chevron.lineTo(w/2,up?cy-s*1.3f:cy+s*1.3f);chevron.lineTo(w/2+s*1.3f,cy);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(4));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);c.drawPath(chevron,paint);
            }
        };
        button.setContentDescription(up?"Scroll up":"Scroll down");button.setVisibility(View.GONE);button.setClickable(true);
        final Runnable glide=new Runnable(){public void run(){
            if(!button.isHovered())return;scroll.smoothScrollBy(0,up?-dp(26):dp(26));handler.postDelayed(this,40);}};
        button.setOnClickListener(v->scroll.smoothScrollBy(0,(int)((up?-1:1)*scroll.getHeight()*.7f)));
        button.setOnHoverListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_HOVER_ENTER){v.setHovered(true);v.invalidate();handler.postDelayed(glide,350);}
            else if(e.getAction()==MotionEvent.ACTION_HOVER_EXIT){v.setHovered(false);v.invalidate();handler.removeCallbacks(glide);}
            return true;
        });
        return button;
    }

    /** Battery level ring with the percentage inside; green, amber or red, with a bolt while charging. */
    public final class BatteryRing extends View{
        int level=-1;boolean charging;
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final BroadcastReceiver receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){
                int l=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
                level=l>=0&&scale>0?Math.round(l*100f/scale):-1;
                int status=intent.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
                charging=status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL&&intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
                setContentDescription(level<0?"Battery":"Battery "+level+" percent"+(charging?", charging":""));invalidate();
            }
        };
        public BatteryRing(){super(activity);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();activity.registerReceiver(receiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));}
        @Override protected void onDetachedFromWindow(){try{activity.unregisterReceiver(receiver);}catch(IllegalArgumentException ignored){}super.onDetachedFromWindow();}
        @Override protected void onDraw(Canvas c){
            if(level<0)return;
            float w=getWidth(),h=getHeight(),stroke=dp(5),r=Math.min(w,h)/2-stroke;
            RectF box=new RectF(w/2-r,h/2-r,w/2+r,h/2+r);
            int color=level<=15?0xffff6b6b:level<=40?WARN:0xff6fdc8c;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0x40ffffff);c.drawArc(box,0,360,false,paint);
            paint.setColor(color);c.drawArc(box,-90,360f*level/100f,false,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(INK);paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(r*(level==100?.62f:.74f));
            float y=h/2-(paint.descent()+paint.ascent())/2-(charging?r*.16f:0);
            c.drawText(String.valueOf(level),w/2,y,paint);
            if(charging){
                Path bolt=new Path();float bx=w/2,by=h/2+r*.48f,s=r*.2f;
                bolt.moveTo(bx+s*.2f,by-s);bolt.lineTo(bx-s*.6f,by+s*.1f);bolt.lineTo(bx,by+s*.1f);bolt.lineTo(bx-s*.2f,by+s);
                bolt.lineTo(bx+s*.6f,by-s*.1f);bolt.lineTo(bx,by-s*.1f);bolt.close();paint.setColor(color);c.drawPath(bolt,paint);
            }
        }
    }

    /**
     * Placement pictures: a projector on a table or on the ceiling, in front of
     * or behind the screen. Returns the views; the caller maps index to value.
     */
    public View[] placements(LinearLayout row,String[] labels,boolean[] ceiling,boolean[] rear,int selected,final IntChange change){
        final LinearLayout[] items=new LinearLayout[labels.length];final int[] current={selected};
        final Runnable paint=()->{for(int i=0;i<items.length;i++){GradientDrawable g=new GradientDrawable();g.setCornerRadius(dp(10));
            g.setColor(i==current[0]?0xff23406e:0xff222935);if(i==current[0])g.setStroke(dp(2),ACCENT);items[i].setBackground(g);}};
        for(int i=0;i<labels.length;i++){
            final int index=i;final boolean onCeiling=ceiling[i],behind=rear[i];
            LinearLayout item=new LinearLayout(activity);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER_HORIZONTAL);item.setPadding(dp(8),dp(10),dp(8),dp(10));
            View picture=new View(activity){
                final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas c){
                    float w=getWidth(),h=getHeight();
                    p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(dp(3));p.setColor(0x80ffffff);
                    float surface=onCeiling?h*.12f:h*.88f;c.drawLine(w*.05f,surface,w*.95f,surface,p);
                    // Screen on the right, viewer on the left; behind = projector beyond the screen.
                    float sx=w*.66f;p.setColor(0xffd2e3fc);p.setStrokeWidth(dp(4));c.drawLine(sx,h*.25f,sx,h*.75f,p);
                    float px=behind?w*.86f:w*.3f,pw=w*.14f,ph=h*.14f,py=onCeiling?surface+ph*.2f:surface-ph*1.2f;
                    p.setStyle(Paint.Style.FILL);p.setColor(INK);c.drawRoundRect(px-pw/2,py,px+pw/2,py+ph,dp(3),dp(3),p);
                    p.setColor(0x558ab4f8);Path beam=new Path();float bx=behind?px-pw/2:px+pw/2;
                    beam.moveTo(bx,py+ph/2);beam.lineTo(sx,h*.27f);beam.lineTo(sx,h*.73f);beam.close();c.drawPath(beam,p);
                    p.setColor(0xffaab0b8);float ex=w*.1f,ey=h*.5f;c.drawCircle(ex,ey-dp(8),dp(5),p);c.drawRect(ex-dp(4),ey-dp(2),ex+dp(4),ey+dp(14),p);
                }
            };
            item.addView(picture,new LinearLayout.LayoutParams(dp(150),dp(84)));
            TextView label=text(labels[i],16,INK);label.setGravity(Gravity.CENTER);label.setPadding(0,dp(8),0,0);item.addView(label);
            items[i]=item;focusable(item,10);item.setOnClickListener(v->{pulse(v);current[0]=index;paint.run();change.onChange(index);});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMargins(0,0,dp(16),0);row.addView(item,lp);
        }
        paint.run();return items;
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
