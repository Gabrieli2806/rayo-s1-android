package local.rayo.launcher;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import local.gabos.ui.GabosUi;

/**
 * GabOS Home: a single-activity TV launcher. No network access, account
 * service, recommendation feed or profile. After three minutes without input
 * while Home is in front it shows a dark clock screensaver.
 */
public class MainActivity extends Activity {
    private static final int COLUMNS=5, MARGIN=48, GAP=20;
    private static final long IDLE_MS=3*60*1000L;
    private int TILE_W=188, TILE_H=106;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final HashMap<String,Bitmap> art=new HashMap<>();
    private final Random random=new Random();
    private GabosUi ui;
    private FrameLayout root;
    private TextView clock, greeting, date, status;
    private String focusedPackage="";
    private View preferredFocus;
    private int battery=-1, lastPackageCount=-1;
    private boolean charging=false, receiverRegistered=false, created=false, resumed=false;

    // Screensaver state.
    private FrameLayout saver;
    private LinearLayout saverBlock;
    private TextView saverClock, saverDate, saverBattery;
    private int swallowKey=-1;
    private boolean swallowTouch=false;

    private final BroadcastReceiver batteryReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context, Intent intent){
            int level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1), scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
            battery=level>=0&&scale>0?Math.round(level*100f/scale):-1;
            int state=intent.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
            charging=state==BatteryManager.BATTERY_STATUS_CHARGING||state==BatteryManager.BATTERY_STATUS_FULL&&intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
            updateStatus();
        }
    };
    private final Runnable tick=new Runnable(){public void run(){updateStatus();handler.postDelayed(this,saver!=null?5000:30000);}};
    private final Runnable idle=new Runnable(){public void run(){
        if(!resumed||saver!=null)return;
        // A dialog (the settings chooser) has the input; wait for it to close.
        if(!hasWindowFocus()){handler.postDelayed(this,IDLE_MS);return;}
        showSaver();
    }};
    private final Runnable drift=new Runnable(){public void run(){
        if(saver==null||saverBlock==null)return;
        float rangeX=Math.max(0,(saver.getWidth()-saverBlock.getWidth())/2f-dp(40)),rangeY=Math.max(0,(saver.getHeight()-saverBlock.getHeight())/2f-dp(48));
        saverBlock.animate().translationX((random.nextFloat()*2-1)*rangeX).translationY((random.nextFloat()*2-1)*rangeY)
            .setDuration(6000).setInterpolator(new AccelerateDecelerateInterpolator()).start();
        handler.postDelayed(this,20000);
    }};

    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);ui=new GabosUi(this);
        ui.onThemeChange(()->{art.clear();render();});
        SystemService.start(this);render();created=true;handler.post(tick);
        // First boot: the setup wizard opens once over Home. A recreated Home (for example
        // after the wizard changed the language) has a saved state and does not reopen it.
        if(state==null&&!SetupActivity.done(this))handler.post(()->open(new Intent(this,SetupActivity.class)));
    }
    @Override protected void onResume(){
        super.onResume();resumed=true;
        if(!receiverRegistered){registerReceiver(batteryReceiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));receiverRegistered=true;}
        if(ui.refreshTheme()||created&&packageCount()!=lastPackageCount){art.clear();render();}
        updateStatus();poke();
    }
    @Override protected void onPause(){
        resumed=false;handler.removeCallbacks(idle);hideSaver();
        if(receiverRegistered){unregisterReceiver(batteryReceiver);receiverRegistered=false;}
        super.onPause();
    }
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);hideSaver();if(packageCount()!=lastPackageCount){art.clear();render();}else updateStatus();}
    @Override public void onBackPressed(){}
    private int packageCount(){return getPackageManager().getInstalledApplications(0).size();}

    // ------------------------------------------------------------ input and screensaver

    /** Restarts the inactivity countdown. */
    private void poke(){handler.removeCallbacks(idle);if(resumed)handler.postDelayed(idle,IDLE_MS);}

    @Override public boolean dispatchKeyEvent(KeyEvent event){
        poke();
        if(saver!=null){
            // The key that wakes Home must not also press the element under the screensaver.
            hideSaver();swallowKey=event.getAction()==KeyEvent.ACTION_UP?-1:event.getKeyCode();return true;
        }
        if(swallowKey!=-1&&event.getKeyCode()==swallowKey){if(event.getAction()==KeyEvent.ACTION_UP)swallowKey=-1;return true;}
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event){
        poke();
        int action=event.getActionMasked();
        if(saver!=null){hideSaver();swallowTouch=action!=MotionEvent.ACTION_UP&&action!=MotionEvent.ACTION_CANCEL;return true;}
        if(swallowTouch){if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)swallowTouch=false;return true;}
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){
        poke();
        if(saver!=null){hideSaver();return true;}
        return super.dispatchGenericMotionEvent(event);
    }

    private void showSaver(){
        if(root==null||saver!=null)return;
        saver=new FrameLayout(this);saver.setBackgroundColor(0xff030406);saver.setFocusable(false);
        saverBlock=new LinearLayout(this);saverBlock.setOrientation(LinearLayout.VERTICAL);saverBlock.setGravity(Gravity.CENTER_HORIZONTAL);
        saverClock=text("",132,0xffeef0f2);saverClock.setTypeface(Typeface.create("sans-serif-thin",Typeface.NORMAL));saverClock.setGravity(Gravity.CENTER);
        saverBlock.addView(saverClock,new LinearLayout.LayoutParams(-2,-2));
        saverDate=text("",26,0xff9aa0a6);saverDate.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));saverDate.setLetterSpacing(.04f);
        LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(-2,-2);dl.setMargins(0,dp(10),0,0);saverBlock.addView(saverDate,dl);
        LinearLayout power=new LinearLayout(this);power.setGravity(Gravity.CENTER_VERTICAL);
        power.addView(ui.new BatteryRing().tint(0xffe8eaed),new LinearLayout.LayoutParams(dp(64),dp(64)));
        saverBattery=text("",20,0xffaab0b8);saverBattery.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.setMargins(dp(16),0,0,0);power.addView(saverBattery,bp);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-2,-2);pp.setMargins(0,dp(34),0,0);saverBlock.addView(power,pp);
        saver.addView(saverBlock,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        TextView site=text(getString(R.string.saver_site),14,0x66ffffff);site.setLetterSpacing(.18f);
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_HORIZONTAL|Gravity.BOTTOM);sp.setMargins(0,0,0,dp(22));saver.addView(site,sp);
        saver.setAlpha(0f);root.addView(saver,new FrameLayout.LayoutParams(-1,-1));
        saver.animate().alpha(1f).setDuration(900).start();
        updateStatus();
        handler.removeCallbacks(tick);handler.post(tick);
        handler.postDelayed(drift,4000);
    }

    private void hideSaver(){
        handler.removeCallbacks(drift);
        if(saver==null)return;
        View gone=saver;saver=null;saverBlock=null;saverClock=saverDate=saverBattery=null;
        gone.animate().cancel();
        if(gone.getParent()!=null)((ViewGroup)gone.getParent()).removeView(gone);
        if(preferredFocus!=null&&getCurrentFocus()==null)preferredFocus.requestFocus();
    }

    // ------------------------------------------------------------ Home page

    private TextView text(String value,float size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);return t;}

    private void updateStatus(){
        Date now=new Date();Locale locale=getResources().getConfiguration().getLocales().get(0);
        CharSequence time=time(now,locale,.6f);
        String day=new SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale,"EEEEMMMMd"),locale).format(now);
        if(!day.isEmpty())day=day.substring(0,1).toUpperCase(locale)+day.substring(1);
        if(clock!=null)clock.setText(time);
        if(date!=null)date.setText(day);
        if(greeting!=null){
            int hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            greeting.setText(hour<5?R.string.greeting_night:hour<12?R.string.greeting_morning:hour<19?R.string.greeting_afternoon:R.string.greeting_evening);
        }
        if(status!=null){
            NetworkInfo network=((ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
            status.setText(network!=null&&network.isConnected()?R.string.status_online:R.string.status_offline);
        }
        if(saverClock!=null){
            saverClock.setText(time(now,locale,.35f));saverDate.setText(day);
            saverBattery.setText(battery<0?"":battery+"%"+(charging?"  ·  "+getString(R.string.saver_charging):""));
        }
    }

    /**
     * The time in the system's 12/24-hour choice and the language's pattern. In
     * 12-hour mode the AM/PM marker is drawn smaller so the large clocks stay compact.
     */
    private CharSequence time(Date now,Locale locale,float markerSize){
        boolean h24=android.text.format.DateFormat.is24HourFormat(this);
        String text=new SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale,h24?"Hm":"hm"),locale).format(now);
        if(h24)return text;
        String marker=new SimpleDateFormat("a",locale).format(now);int at=marker.isEmpty()?-1:text.indexOf(marker);
        if(at<0)return text;
        android.text.SpannableString styled=new android.text.SpannableString(text);
        styled.setSpan(new android.text.style.RelativeSizeSpan(markerSize),at,at+marker.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }

    private void render(){
        hideSaver();
        TILE_W=ui.tileWidthDp(COLUMNS,MARGIN,GAP);TILE_H=TILE_W*9/16;
        preferredFocus=null;
        ui.immersive();
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);scroll.setFillViewport(true);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setClipChildren(false);page.setClipToPadding(false);
        page.setPadding(dp(MARGIN),dp(32),dp(MARGIN),dp(40));
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2));

        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setClipChildren(false);
        TextView title=text(getString(R.string.home_title),40,GabosUi.INK);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(ui.new BatteryRing(),new LinearLayout.LayoutParams(dp(58),dp(58)));
        clock=text("",26,GabosUi.INK);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.setMargins(dp(26),0,0,0);header.addView(clock,cp);
        page.addView(header,new LinearLayout.LayoutParams(-1,dp(60)));

        LinearLayout system=row(page,dp(20));
        View settings=systemTile(getString(R.string.tile_settings),GabosUi.BLUE_TOP,GabosUi.BLUE_BOTTOM,true,v->chooseSettings());
        system.addView(settings,tileParams());
        if(installed("com.android.documentsui"))system.addView(systemTile(getString(R.string.tile_files),GabosUi.TILE_TOP,GabosUi.TILE_BOTTOM,false,v->launch("com.android.documentsui")),tileParams());

        List<App> apps=apps();LinearLayout line=null;int index=0;
        for(App app:apps){
            if(index%COLUMNS==0)line=row(page,dp(index==0?26:18));
            View tile=appTile(app);line.addView(tile,tileParams());
            if(preferredFocus==null||app.pkg.equals(focusedPackage))preferredFocus=tile;
            index++;
        }
        if(preferredFocus==null)preferredFocus=settings;

        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(22),dp(20),dp(22),dp(20));card.setBackground(ui.cardBackground(6));
        TextView brand=text("GabOS",15,GabosUi.DIM);brand.setLetterSpacing(.12f);card.addView(brand);
        greeting=text("",34,GabosUi.INK);greeting.setPadding(0,dp(14),0,dp(8));card.addView(greeting);
        date=text("",19,GabosUi.DIM);card.addView(date);
        status=text("",17,GabosUi.DIM);status.setPadding(0,dp(10),0,0);card.addView(status);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.setMargins(0,dp(34),0,0);page.addView(card,sp);

        root=ui.assist(scroll);root.setBackground(GabosUi.background());
        getWindow().setBackgroundDrawable(GabosUi.background());
        setContentView(root);lastPackageCount=packageCount();updateStatus();
        final View focus=preferredFocus;focus.post(()->focus.requestFocus());
    }

    private void pressed(View v){
        v.animate().cancel();v.setScaleX(.94f);v.setScaleY(.94f);
        v.animate().scaleX(v.isFocused()?1.08f:1f).scaleY(v.isFocused()?1.08f:1f).setDuration(160).start();
    }

    /** Settings opens a choice between the projector's own settings and Android's. */
    private void chooseSettings(){
        final android.app.Dialog dialog=new android.app.Dialog(this,android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        FrameLayout scrim=new FrameLayout(this);scrim.setBackgroundColor(GabosUi.SCRIM);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(40),dp(32),dp(40),dp(36));
        panel.setBackground(ui.surface(GabosUi.SURFACE,12));panel.setClipChildren(false);panel.setClipToPadding(false);
        panel.addView(text(getString(R.string.chooser_title),34,GabosUi.INK));
        TextView hint=text(getString(R.string.chooser_hint),18,GabosUi.DIM);hint.setPadding(0,dp(8),0,dp(22));panel.addView(hint);
        LinearLayout choices=new LinearLayout(this);choices.setClipChildren(false);choices.setClipToPadding(false);
        View gabos=choice(choices,getString(R.string.chooser_gabos),getString(R.string.chooser_gabos_detail),GabosUi.BLUE_TOP,GabosUi.BLUE_BOTTOM,
            ()->{dialog.dismiss();open(new Intent(this,SettingsActivity.class));});
        choice(choices,getString(R.string.chooser_android),getString(R.string.chooser_android_detail),GabosUi.TILE_TOP,GabosUi.TILE_BOTTOM,
            ()->{dialog.dismiss();open(new Intent(android.provider.Settings.ACTION_SETTINGS));});
        panel.addView(choices);
        scrim.addView(panel,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        scrim.setOnClickListener(v->dialog.dismiss());
        dialog.setOnDismissListener(d->poke());
        dialog.setContentView(scrim);dialog.show();gabos.post(gabos::requestFocus);
    }

    private View choice(LinearLayout row,String title,String detail,int top,int bottom,final Runnable action){
        LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setGravity(Gravity.CENTER_VERTICAL);tile.setPadding(dp(26),0,dp(26),0);
        GradientDrawable fill=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});fill.setCornerRadius(dp(8));
        if(GabosUi.LIGHT)fill.setStroke(Math.max(1,dp(1)),0x1a000000);tile.setBackground(fill);
        tile.addView(text(title,28,GabosUi.INK));TextView d=text(detail,17,GabosUi.mix(GabosUi.INK,GabosUi.DIM,.5f));d.setPadding(0,dp(10),0,0);tile.addView(d);
        tile.setContentDescription(title);focusable(tile,null);tile.setOnClickListener(v->{pressed(v);action.run();});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(300),dp(150));p.setMargins(0,0,dp(24),0);row.addView(tile,p);return tile;
    }

    private LinearLayout row(LinearLayout page,int top){
        LinearLayout r=new LinearLayout(this);r.setClipChildren(false);r.setClipToPadding(false);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,top,0,0);page.addView(r,p);return r;
    }
    private LinearLayout.LayoutParams tileParams(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(TILE_W),dp(TILE_H));p.setMargins(0,0,dp(GAP),0);return p;}

    /** Theme focus outline and slight growth, drawn as a foreground so the art is not rebuilt. */
    private void focusable(final View tile,final String pkg){
        tile.setFocusable(true);tile.setClickable(true);
        final GradientDrawable ring=new GradientDrawable();ring.setColor(Color.TRANSPARENT);ring.setCornerRadius(dp(8));ring.setStroke(dp(3),GabosUi.FOCUS);
        tile.setOnFocusChangeListener((v,focus)->{
            v.setForeground(focus?ring:null);v.setElevation(focus?dp(8):0);
            v.animate().scaleX(focus?1.08f:1f).scaleY(focus?1.08f:1f).setDuration(110).start();
            if(focus&&pkg!=null)focusedPackage=pkg;
        });
    }

    private View systemTile(String label,int from,int to,boolean gear,View.OnClickListener action){
        FrameLayout tile=new FrameLayout(this);
        GradientDrawable fill=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{from,to});fill.setCornerRadius(dp(6));
        if(GabosUi.LIGHT)fill.setStroke(Math.max(1,dp(1)),0x1a000000);tile.setBackground(fill);
        tile.setContentDescription(label);
        if(gear)tile.addView(new GearView(),new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));
        else{TextView t=text(label,20,GabosUi.INK);t.setGravity(Gravity.CENTER);tile.addView(t,new FrameLayout.LayoutParams(-1,-1));}
        focusable(tile,null);tile.setOnClickListener(v->{pressed(v);action.onClick(v);});return tile;
    }

    private View appTile(final App app){
        ImageView tile=new ImageView(this);tile.setScaleType(ImageView.ScaleType.FIT_XY);tile.setImageBitmap(art(app));
        tile.setContentDescription(app.name);focusable(tile,app.pkg);tile.setOnClickListener(v->{pressed(v);launch(app.pkg);});return tile;
    }

    /** One cached bitmap per application: its TV banner, or its icon and name on a tint. */
    private Bitmap art(App app){
        Bitmap cached=art.get(app.pkg);if(cached!=null)return cached;
        int w=dp(TILE_W),h=dp(TILE_H);Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
        Path clip=new Path();clip.addRoundRect(new RectF(0,0,w,h),dp(6),dp(6),Path.Direction.CW);canvas.clipPath(clip);
        Drawable banner=null;
        try{banner=getPackageManager().getApplicationBanner(app.pkg);}catch(Exception ignored){}
        if(banner!=null){banner.setBounds(0,0,w,h);banner.draw(canvas);}
        else{
            int[] tones=GabosUi.tones(GabosUi.TINTS[(app.pkg.hashCode()&0x7fffffff)%GabosUi.TINTS.length]);
            Paint fill=new Paint();fill.setShader(new LinearGradient(0,0,0,h,tones[0],tones[1],Shader.TileMode.CLAMP));canvas.drawRect(0,0,w,h,fill);
            Drawable icon;try{icon=getPackageManager().getApplicationIcon(app.pkg);}catch(Exception e){icon=getResources().getDrawable(android.R.drawable.sym_def_app_icon,null);}
            int size=dp(48),left=dp(14),top=(h-size)/2;icon.setBounds(left,top,left+size,top+size);icon.draw(canvas);
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(GabosUi.INK);paint.setTextSize(dp(18));
            float x=left+size+dp(10),room=w-x-dp(8);String[] words=app.name.split(" ");ArrayList<String> lines=new ArrayList<>();String current="";
            for(String word:words){String next=current.isEmpty()?word:current+" "+word;if(paint.measureText(next)<=room||current.isEmpty())current=next;else{lines.add(current);current=word;}}
            if(!current.isEmpty())lines.add(current);
            while(lines.size()>3)lines.remove(lines.size()-1);
            float step=dp(22),y=h/2f-(lines.size()-1)*step/2f+dp(5);
            for(String lineText:lines){canvas.drawText(TextUtils.ellipsize(lineText,new android.text.TextPaint(paint),room,TextUtils.TruncateAt.END).toString(),x,y,paint);y+=step;}
        }
        art.put(app.pkg,bitmap);return bitmap;
    }

    private static class App{final String pkg,name;App(String p,String n){pkg=p;name=n;}}
    private List<App> apps(){
        LinkedHashMap<String,App> result=new LinkedHashMap<>();
        HashSet<String> hidden=new HashSet<>(Arrays.asList(getPackageName(),"com.android.tv.settings","com.android.settings","com.android.documentsui"));
        String[] pinned={"org.smarttube.stable","com.aurora.store","local.gabos.media"};
        for(String pkg:pinned)if(installed(pkg)&&launchIntent(pkg)!=null)result.put(pkg,new App(pkg,label(pkg)));
        List<ResolveInfo> found=new ArrayList<>();
        for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER}){Intent i=new Intent(Intent.ACTION_MAIN);i.addCategory(category);found.addAll(getPackageManager().queryIntentActivities(i,0));}
        Collections.sort(found,new ResolveInfo.DisplayNameComparator(getPackageManager()));
        for(ResolveInfo info:found){String pkg=info.activityInfo.packageName;if(hidden.contains(pkg)||result.containsKey(pkg))continue;result.put(pkg,new App(pkg,info.loadLabel(getPackageManager()).toString()));}
        return new ArrayList<>(result.values());
    }
    private boolean installed(String pkg){try{getPackageManager().getApplicationInfo(pkg,0);return true;}catch(PackageManager.NameNotFoundException e){return false;}}
    private String label(String pkg){try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception e){return getString(R.string.app_fallback);}}
    private Intent launchIntent(String pkg){Intent i=getPackageManager().getLeanbackLaunchIntentForPackage(pkg);if(i==null)i=getPackageManager().getLaunchIntentForPackage(pkg);return i;}
    private void launch(String pkg){open(launchIntent(pkg));}
    private void open(Intent intent){try{if(intent==null)throw new ActivityNotFoundException();startActivity(intent);overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);}catch(Exception e){Toast.makeText(this,R.string.open_failed,Toast.LENGTH_SHORT).show();}}

    private class GearView extends View{
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        GearView(){super(MainActivity.this);}
        @Override protected void onDraw(Canvas c){
            float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(cx,cy);
            paint.setColor(GabosUi.INK);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(r*.16f);paint.setStrokeCap(Paint.Cap.ROUND);
            for(int i=0;i<8;i++){double a=Math.PI*i/4;c.drawLine(cx+(float)Math.cos(a)*r*.62f,cy+(float)Math.sin(a)*r*.62f,cx+(float)Math.cos(a)*r*.9f,cy+(float)Math.sin(a)*r*.9f,paint);}
            c.drawCircle(cx,cy,r*.58f,paint);c.drawCircle(cx,cy,r*.2f,paint);
        }
    }
}
