package local.gabos.ui;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
 *
 * Theme contract: GabOS Home (local.rayo.launcher) publishes a read-only
 * provider at content://local.gabos.theme/theme with one row: "accent" (ARGB
 * int) and "mode" ("dark" or "light"). Observers on that URI are notified on
 * change. Without the provider the dark mode and the default blue accent apply.
 * The palette below is static per process and rebuilt by {@link #loadTheme}.
 */
public final class GabosUi {
    public static final String THEME_AUTHORITY="local.gabos.theme";
    public static final Uri THEME_URI=Uri.parse("content://"+THEME_AUTHORITY+"/theme");
    public static final int DEFAULT_ACCENT=0xff8ab4f8;
    /** Accent swatches offered in GabOS Settings (blue is the default). */
    public static final int[] ACCENTS={0xff8ab4f8,0xff78d9ec,0xff81c995,0xffc5e17a,0xfffdd663,0xfffcad70,0xfff28b82,0xffff8bcb,0xffc58af9,0xffe8eaed};

    /** Current palette; read at use time, so views built after a reload follow the theme. */
    public static boolean LIGHT=false;
    public static int INK, DIM, CARD, FOCUS, ACCENT, ON_ACCENT, BLUE_TOP, BLUE_BOTTOM, WARN;
    public static int SURFACE, PILL, TRACK, TILE_TOP, TILE_BOTTOM, CARD_FROM, CARD_TO, SCRIM;
    public static int[] BACKGROUND;
    public static final int[] TINTS={0xff3b4a8f,0xff2f6f73,0xff7a4a2e,0xff5b3f86,0xff2e5f8a,0xff7b3550,0xff46683a,0xff6a5a2c};
    public static final int TILE_W=188, TILE_H=106, GAP=22;
    private static int loadedAccent=DEFAULT_ACCENT, version;
    static{apply(false,DEFAULT_ACCENT);}

    private final Activity activity;
    private int seen;

    public interface IntChange{void onChange(int value);}

    public GabosUi(Activity activity){this.activity=activity;loadTheme(activity);seen=version;}

    // ---------------------------------------------------------------- theme

    /** Reads the shared theme; returns true when the palette changed. */
    public static synchronized boolean loadTheme(Context context){
        int accent=DEFAULT_ACCENT;boolean light=false;
        try(Cursor cursor=context.getContentResolver().query(THEME_URI,null,null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()){
                int a=cursor.getColumnIndex("accent"),m=cursor.getColumnIndex("mode");
                if(a>=0&&!cursor.isNull(a))accent=cursor.getInt(a)|0xff000000;
                if(m>=0)light="light".equals(cursor.getString(m));
            }
        }catch(Exception ignored){}
        if(accent==loadedAccent&&light==LIGHT)return false;
        apply(light,accent);version++;return true;
    }

    /** Reloads the theme; true when this screen was built with an older palette. */
    public boolean refreshTheme(){loadTheme(activity);if(seen!=version){seen=version;return true;}return false;}

    /**
     * Runs the action (normally a re-render) when the shared theme changes while
     * this activity exists. The observer is removed when the activity is destroyed.
     */
    public void onThemeChange(final Runnable action){
        final ContentObserver observer=new ContentObserver(new Handler(Looper.getMainLooper())){
            @Override public void onChange(boolean self){if(!activity.isFinishing()&&!activity.isDestroyed()&&refreshTheme())action.run();}
        };
        try{activity.getContentResolver().registerContentObserver(THEME_URI,false,observer);}catch(Exception e){return;}
        activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            @Override public void onActivityDestroyed(Activity a){
                if(a!=activity)return;
                try{activity.getContentResolver().unregisterContentObserver(observer);}catch(Exception ignored){}
                activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            }
            @Override public void onActivityCreated(Activity a,Bundle b){}
            @Override public void onActivityStarted(Activity a){}
            @Override public void onActivityResumed(Activity a){}
            @Override public void onActivityPaused(Activity a){}
            @Override public void onActivityStopped(Activity a){}
            @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
        });
    }

    private static void apply(boolean light,int accent){
        LIGHT=light;loadedAccent=accent;
        float[] hsv=new float[3];Color.colorToHSV(accent,hsv);
        float hue=hsv[0],chroma=Math.min(1f,hsv[1]/.4f);
        if(!light){
            INK=0xfff1f3f4;DIM=0xffaab0b8;CARD=0xff1b222d;FOCUS=0xffffffff;ACCENT=accent;WARN=0xffffb36b;
            SURFACE=0xff161c26;PILL=0xff2a2f37;TRACK=0xff3a414c;TILE_TOP=0xff25303f;TILE_BOTTOM=0xff1a222d;SCRIM=0xcc05070a;
            BLUE_TOP=hsv(hue,.84f*chroma,.63f);BLUE_BOTTOM=hsv(hue,.87f*chroma,.45f);
            CARD_FROM=0xff1a2231;CARD_TO=hsv(hue,.68f*chroma,.29f);
            BACKGROUND=new int[]{0xff0d0f13,0xff0e131c,hsv(hue,.70f*chroma,.21f)};
        }else{
            INK=0xff1f2329;DIM=0xff5c636d;CARD=0xffffffff;FOCUS=0xff1f2329;WARN=0xffb35c00;
            ACCENT=hsv(hue,Math.min(1f,hsv[1]*1.35f),Math.min(hsv[2],.66f));
            SURFACE=0xfff7f9fc;PILL=0xffdfe4eb;TRACK=0xffc9d0da;TILE_TOP=0xffffffff;TILE_BOTTOM=0xffedf1f6;SCRIM=0x99202328;
            BLUE_TOP=hsv(hue,.30f*chroma,.98f);BLUE_BOTTOM=hsv(hue,.42f*chroma,.91f);
            CARD_FROM=0xffffffff;CARD_TO=hsv(hue,.12f*chroma,.98f);
            BACKGROUND=new int[]{0xfff6f8fb,0xffeef2f7,hsv(hue,.12f*chroma,.95f)};
        }
        ON_ACCENT=luminance(ACCENT)>.45f?0xff0d0f13:0xffffffff;
    }

    private static int hsv(float h,float s,float v){return Color.HSVToColor(new float[]{h,Math.max(0,Math.min(1,s)),Math.max(0,Math.min(1,v))});}

    public static float luminance(int c){return (.299f*Color.red(c)+.587f*Color.green(c)+.114f*Color.blue(c))/255f;}

    /** Mixes two colours; f=0 gives a, f=1 gives b. */
    public static int mix(int a,int b,float f){
        return Color.argb(Math.round(Color.alpha(a)+(Color.alpha(b)-Color.alpha(a))*f),Math.round(Color.red(a)+(Color.red(b)-Color.red(a))*f),
                Math.round(Color.green(a)+(Color.green(b)-Color.green(a))*f),Math.round(Color.blue(a)+(Color.blue(b)-Color.blue(a))*f));
    }

    public static int alpha(int color,int alpha){return (color&0x00ffffff)|(alpha<<24);}

    /** A tile gradient from one base colour that keeps INK readable in either mode. */
    public static int[] tones(int base){
        if(!LIGHT)return new int[]{base,mix(base,0xff000000,.32f)};
        float[] hsv=new float[3];Color.colorToHSV(base,hsv);
        return new int[]{hsv(hsv[0],hsv[1]*.38f,.97f),hsv(hsv[0],hsv[1]*.5f,.90f)};
    }

    // ---------------------------------------------------------------- basics

    public int dp(float value){return (int)(value*activity.getResources().getDisplayMetrics().density+.5f);}

    /** True when the interface language is Spanish; used for the few strings owned by this kit. */
    public boolean spanish(){
        try{return "es".equals(activity.getResources().getConfiguration().getLocales().get(0).getLanguage());}catch(Exception e){return false;}
    }
    public String tr(String english,String spanish){return spanish()?spanish:english;}

    public static GradientDrawable background(){
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,BACKGROUND.clone());
    }

    /** A plain rounded surface; light mode adds a hairline so white cards stay visible. */
    public GradientDrawable surface(int color,float radius){
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));
        if(LIGHT)g.setStroke(Math.max(1,dp(1)),0x1a000000);return g;
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
        activity.getWindow().setBackgroundDrawable(background());
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
                float w=getWidth(),h=getHeight();boolean lit=isHovered()||isFocused();
                paint.setStyle(Paint.Style.FILL);paint.setColor(lit?alpha(INK,0xee):alpha(PILL,0xc0));c.drawCircle(w/2,h/2,w/2,paint);
                paint.setColor(lit?BACKGROUND[0]:INK);
                Path chevron=new Path();float s=w*.18f,cy=h/2+(up?s/2:-s/2);
                chevron.moveTo(w/2-s*1.3f,cy);chevron.lineTo(w/2,up?cy-s*1.3f:cy+s*1.3f);chevron.lineTo(w/2+s*1.3f,cy);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(4));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);c.drawPath(chevron,paint);
            }
        };
        button.setContentDescription(up?tr("Scroll up","Desplazar hacia arriba"):tr("Scroll down","Desplazar hacia abajo"));
        button.setVisibility(View.GONE);button.setClickable(true);
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

    /**
     * Battery level ring with the percentage inside and a small bolt while
     * charging. White in dark mode, the theme ink in light mode; no level colours.
     */
    public final class BatteryRing extends View{
        int level=-1,tint;boolean charging;
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final BroadcastReceiver receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){
                int l=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
                level=l>=0&&scale>0?Math.round(l*100f/scale):-1;
                int status=intent.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
                charging=status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL&&intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
                setContentDescription(level<0?tr("Battery","Batería"):tr("Battery ","Batería ")+level+"%"+(charging?tr(", charging",", cargando"):""));
                invalidate();
            }
        };
        public BatteryRing(){super(activity);}
        /** Forces a colour (for example white on the always-dark screensaver); 0 follows the theme. */
        public BatteryRing tint(int color){tint=color;invalidate();return this;}
        public int level(){return level;}
        public boolean charging(){return charging;}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();activity.registerReceiver(receiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));}
        @Override protected void onDetachedFromWindow(){try{activity.unregisterReceiver(receiver);}catch(IllegalArgumentException ignored){}super.onDetachedFromWindow();}
        @Override protected void onDraw(Canvas c){
            if(level<0)return;
            int color=tint!=0?tint:LIGHT?INK:0xffffffff;
            float w=getWidth(),h=getHeight(),stroke=Math.max(dp(3),Math.min(w,h)*.085f),r=Math.min(w,h)/2-stroke;
            RectF box=new RectF(w/2-r,h/2-r,w/2+r,h/2+r);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(alpha(color,0x38));c.drawArc(box,0,360,false,paint);
            paint.setColor(color);c.drawArc(box,-90,360f*level/100f,false,paint);
            paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(r*(level==100?.62f:.74f));
            float y=h/2-(paint.descent()+paint.ascent())/2-(charging?r*.16f:0);
            c.drawText(String.valueOf(level),w/2,y,paint);
            if(charging){
                Path bolt=new Path();float bx=w/2,by=h/2+r*.5f,s=r*.17f;
                bolt.moveTo(bx+s*.2f,by-s);bolt.lineTo(bx-s*.6f,by+s*.1f);bolt.lineTo(bx,by+s*.1f);bolt.lineTo(bx-s*.2f,by+s);
                bolt.lineTo(bx+s*.6f,by-s*.1f);bolt.lineTo(bx,by-s*.1f);bolt.close();c.drawPath(bolt,paint);
            }
        }
    }

    /**
     * Placement pictures: a projector on a table or on the ceiling, in front of
     * or behind the screen. Returns the views; the caller maps index to value.
     */
    public View[] placements(LinearLayout row,String[] labels,boolean[] ceiling,boolean[] rear,int selected,final IntChange change){
        final LinearLayout[] items=new LinearLayout[labels.length];final int[] current={selected};
        final Runnable paint=()->{for(int i=0;i<items.length;i++){GradientDrawable g=surface(i==current[0]?mix(CARD,ACCENT,.22f):CARD,10);
            if(i==current[0])g.setStroke(dp(2),ACCENT);items[i].setBackground(g);}};
        for(int i=0;i<labels.length;i++){
            final int index=i;final boolean onCeiling=ceiling[i],behind=rear[i];
            LinearLayout item=new LinearLayout(activity);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER_HORIZONTAL);item.setPadding(dp(8),dp(10),dp(8),dp(10));
            View picture=new View(activity){
                final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas c){
                    float w=getWidth(),h=getHeight();
                    p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(dp(3));p.setColor(alpha(INK,0x80));
                    float surface=onCeiling?h*.12f:h*.88f;c.drawLine(w*.05f,surface,w*.95f,surface,p);
                    // Screen on the right, viewer on the left; behind = projector beyond the screen.
                    float sx=w*.66f;p.setColor(ACCENT);p.setStrokeWidth(dp(4));c.drawLine(sx,h*.25f,sx,h*.75f,p);
                    float px=behind?w*.86f:w*.3f,pw=w*.14f,ph=h*.14f,py=onCeiling?surface+ph*.2f:surface-ph*1.2f;
                    p.setStyle(Paint.Style.FILL);p.setColor(INK);c.drawRoundRect(px-pw/2,py,px+pw/2,py+ph,dp(3),dp(3),p);
                    p.setColor(alpha(ACCENT,0x55));Path beam=new Path();float bx=behind?px-pw/2:px+pw/2;
                    beam.moveTo(bx,py+ph/2);beam.lineTo(sx,h*.27f);beam.lineTo(sx,h*.73f);beam.close();c.drawPath(beam,p);
                    p.setColor(DIM);float ex=w*.1f,ey=h*.5f;c.drawCircle(ex,ey-dp(8),dp(5),p);c.drawRect(ex-dp(4),ey-dp(2),ex+dp(4),ey+dp(14),p);
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

    /** The Home focus treatment: outline (white in dark mode), slight growth, raised above neighbours. */
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
        GradientDrawable fill=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});fill.setCornerRadius(dp(8));
        if(LIGHT)fill.setStroke(Math.max(1,dp(1)),0x1a000000);tile.setBackground(fill);
        TextView t=text(label,20,INK);t.setGravity(Gravity.CENTER);t.setPadding(dp(8),0,dp(8),0);tile.addView(t,new FrameLayout.LayoutParams(-1,-1));
        tile.setContentDescription(label);focusable(tile,8);tile.setOnClickListener(v->{pulse(v);action.onClick(v);});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(TILE_W),dp(TILE_H));p.setMargins(0,0,dp(GAP),0);row.addView(tile,p);return tile;
    }

    public View tile(LinearLayout row,String label,View.OnClickListener action){return tile(row,label,TILE_TOP,TILE_BOTTOM,action);}

    /** The accent-coloured tile used for the main action of a screen. */
    public View primaryTile(LinearLayout row,String label,View.OnClickListener action){return tile(row,label,BLUE_TOP,BLUE_BOTTOM,action);}

    /** A tile tinted from one base colour, readable in both modes. */
    public View tintedTile(LinearLayout row,String label,int base,View.OnClickListener action){int[] t=tones(base);return tile(row,label,t[0],t[1],action);}

    /** A value card: small caption above a large value, in the Home card surface. */
    public TextView stat(LinearLayout row,String caption,String value){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18),dp(12),dp(18),dp(12));card.setBackground(surface(CARD,8));
        TextView label=text(caption,14,DIM);label.setLetterSpacing(.08f);card.addView(label);
        TextView number=text(value,26,INK);number.setPadding(0,dp(10),0,0);number.setSingleLine(true);card.addView(number);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(TILE_H),1);p.setMargins(0,0,dp(GAP),0);row.addView(card,p);return number;
    }

    /** The wide Home-style information card; returns it for the caller to fill. */
    public LinearLayout card(LinearLayout column,int topDp){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(24),dp(22),dp(24),dp(22));
        card.setBackground(cardBackground(8));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(topDp),0,0);column.addView(card,p);return card;
    }

    public GradientDrawable cardBackground(float radius){
        GradientDrawable surface=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{CARD_FROM,CARD_TO});surface.setCornerRadius(dp(radius));
        if(LIGHT)surface.setStroke(Math.max(1,dp(1)),0x1a000000);return surface;
    }

    /** A full-width settings row: title and summary on the left, a control on the right. */
    public LinearLayout settingRow(LinearLayout column,String title,String summary){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(24),dp(14),dp(24),dp(14));
        row.setClipChildren(false);row.setClipToPadding(false);row.setBackground(surface(CARD,8));
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
        final String yes=tr("On","Sí"),no=tr("Off","No");
        final TextView pill=text(on?yes:no,18,INK);pill.setGravity(Gravity.CENTER);
        final Runnable paint=()->{GradientDrawable g=new GradientDrawable();g.setCornerRadius(dp(22));g.setColor(state[0]?ACCENT:PILL);pill.setBackground(g);
            pill.setText(state[0]?yes:no);pill.setTextColor(state[0]?ON_ACCENT:INK);};
        paint.run();focusable(pill,22);
        pill.setOnClickListener(v->{pulse(v);state[0]=!state[0];paint.run();change.onChange(state[0]?1:0);});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(120),dp(44));p.setMargins(dp(24),0,0,0);row.addView(pill,p);return pill;
    }

    /** A row of mutually exclusive pills. Returns the pill views. */
    public View[] choices(LinearLayout row,String[] labels,int selected,final IntChange change){
        final TextView[] pills=new TextView[labels.length];final int[] current={selected};
        final Runnable paint=()->{for(int i=0;i<pills.length;i++){GradientDrawable g=new GradientDrawable();g.setCornerRadius(dp(22));
            g.setColor(i==current[0]?ACCENT:PILL);pills[i].setBackground(g);pills[i].setTextColor(i==current[0]?ON_ACCENT:INK);}};
        for(int i=0;i<labels.length;i++){
            final int index=i;TextView pill=text(labels[i],17,INK);pill.setGravity(Gravity.CENTER);pill.setPadding(dp(18),0,dp(18),0);pills[i]=pill;
            focusable(pill,22);pill.setOnClickListener(v->{pulse(v);current[0]=index;paint.run();change.onChange(index);});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(44));p.setMargins(dp(12),0,0,0);row.addView(pill,p);
        }
        paint.run();return pills;
    }

    /**
     * Round colour tiles; the selected one carries a ring and a check mark.
     * Returns the views in the same order as the colours.
     */
    public View[] swatches(LinearLayout row,final int[] colors,String[] names,int selected,final IntChange change){
        final View[] views=new View[colors.length];final int[] current={selected};
        for(int i=0;i<colors.length;i++){
            final int index=i,color=colors[i];
            View swatch=new View(activity){
                final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas c){
                    float w=getWidth(),h=getHeight(),r=Math.min(w,h)/2;
                    p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawCircle(w/2,h/2,r*.72f,p);
                    if(index==current[0]){
                        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(3));p.setColor(INK);c.drawCircle(w/2,h/2,r*.9f,p);
                        p.setColor(luminance(color)>.45f?0xff0d0f13:0xffffffff);p.setStrokeWidth(dp(3.5f));p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
                        Path check=new Path();float s=r*.3f;check.moveTo(w/2-s,h/2);check.lineTo(w/2-s*.3f,h/2+s*.7f);check.lineTo(w/2+s,h/2-s*.6f);c.drawPath(check,p);
                    }
                }
            };
            if(names!=null&&i<names.length)swatch.setContentDescription(names[i]);
            focusable(swatch,32);views[i]=swatch;
            swatch.setOnClickListener(v->{pulse(v);current[0]=index;for(View o:views)o.invalidate();change.onChange(index);});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(64),dp(64));p.setMargins(0,0,dp(14),0);row.addView(swatch,p);
        }
        return views;
    }

    /**
     * Track slider. Changes are paced for a projector remote and its touchpad:
     * one step per press, held keys repeat every 180 ms, and the wheel or a
     * two-finger scroll over the hovered slider moves one step per notch at
     * most every 140 ms. Hovering highlights the thumb.
     */
    private final class SliderView extends View{
        final int min,max,step;int value;final IntChange change;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        long lastKey,lastScroll;float scrollAccumulated;boolean hovered;
        SliderView(int min,int max,int step,int value,IntChange change){super(activity);this.min=min;this.max=max;this.step=step;this.value=value;this.change=change;}
        void set(int v){v=Math.max(min,Math.min(max,v));if(v!=value){value=v;invalidate();change.onChange(v);}}
        @Override public boolean onKeyDown(int code,KeyEvent e){
            if(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT){
                long now=e.getEventTime();
                if(e.getRepeatCount()==0||now-lastKey>=180){lastKey=now;set(value+(code==KeyEvent.KEYCODE_DPAD_RIGHT?step:-step));}
                return true;
            }
            return super.onKeyDown(code,e);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            float pad=dp(24),w=getWidth()-pad*2-dp(70);
            float f=Math.max(0,Math.min(1,(e.getX()-pad)/w));
            set(min+Math.round(f*(max-min)/step)*step);requestFocus();return true;
        }
        @Override public boolean onHoverEvent(MotionEvent e){
            boolean now=e.getAction()!=MotionEvent.ACTION_HOVER_EXIT;
            if(now!=hovered){hovered=now;scrollAccumulated=0;invalidate();}
            return super.onHoverEvent(e);
        }
        @Override public boolean onGenericMotionEvent(MotionEvent e){
            if(e.getAction()==MotionEvent.ACTION_SCROLL){
                // Touchpads send many small scroll events; count whole notches and pace them.
                scrollAccumulated+=e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                long now=e.getEventTime();
                if(Math.abs(scrollAccumulated)>=1f&&now-lastScroll>=140){
                    lastScroll=now;set(value+(scrollAccumulated>0?step:-step));scrollAccumulated=0;
                }
                return true;
            }
            return super.onGenericMotionEvent(e);
        }
        @Override protected void onDraw(Canvas c){
            float pad=dp(24),label=dp(70),w=getWidth()-pad*2-label,cy=getHeight()/2f,f=(value-min)/(float)(max-min);
            paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(dp(6));
            paint.setColor(TRACK);c.drawLine(pad,cy,pad+w,cy,paint);
            paint.setColor(ACCENT);c.drawLine(pad,cy,pad+w*f,cy,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(isFocused()||hovered?FOCUS:ACCENT);c.drawCircle(pad+w*f,cy,dp(isFocused()?12:hovered?11:10),paint);
            paint.setColor(INK);paint.setTextSize(dp(20));paint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.valueOf(value),getWidth()-dp(14),cy+dp(7),paint);
        }
    }
}
