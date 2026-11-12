package local.rayo.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.*;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * GabOS Home: a single-activity TV launcher with a local, offline profile name.
 * No network access, account service, recommendation feed or background service.
 */
public class MainActivity extends Activity {
    private static final int INK=0xfff1f3f4, DIM=0xff9aa0a6, CARD=0xff1b222d, FOCUS=0xffffffff;
    private static final int[] TINTS={0xff3b4a8f,0xff2f6f73,0xff7a4a2e,0xff5b3f86,0xff2e5f8a,0xff7b3550,0xff46683a,0xff6a5a2c};
    private static final int COLUMNS=7;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final HashMap<String,Bitmap> art=new HashMap<>();
    private TextView clock, greeting, date, status, profileName, profileInitial;
    private String focusedPackage="";
    private View preferredFocus;
    private int battery=-1, lastPackageCount=-1;
    private boolean plugged=false, receiverRegistered=false, created=false;
    private final BroadcastReceiver batteryReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context, Intent intent){
            int level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1), scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
            battery=level>=0&&scale>0?Math.round(level*100f/scale):-1;
            plugged=intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
            updateStatus();
        }
    };
    private final Runnable tick=new Runnable(){public void run(){updateStatus();handler.postDelayed(this,30000);}};

    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private SharedPreferences prefs(){return getSharedPreferences("profile",MODE_PRIVATE);}
    private String name(){return prefs().getString("name","");}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);render();created=true;handler.post(tick);
        if(name().isEmpty()&&!prefs().getBoolean("asked",false))askName();
    }
    @Override protected void onResume(){
        super.onResume();
        if(!receiverRegistered){registerReceiver(batteryReceiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));receiverRegistered=true;}
        if(created&&packageCount()!=lastPackageCount){art.clear();render();}
        updateStatus();
    }
    @Override protected void onPause(){if(receiverRegistered){unregisterReceiver(batteryReceiver);receiverRegistered=false;}super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(tick);super.onDestroy();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(packageCount()!=lastPackageCount){art.clear();render();}else updateStatus();}
    @Override public void onBackPressed(){}
    private int packageCount(){return getPackageManager().getInstalledApplications(0).size();}

    private TextView text(String value,float size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);return t;}
    private GradientDrawable rounded(int fill,float radius){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(radius));return g;}

    private void updateStatus(){
        Date now=new Date();
        if(clock!=null)clock.setText(new SimpleDateFormat("HH:mm",Locale.getDefault()).format(now));
        if(date!=null)date.setText(new SimpleDateFormat("EEEE, MMMM d",Locale.getDefault()).format(now));
        if(greeting!=null){
            int hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            String part=hour<5?"Good night":hour<12?"Good morning":hour<19?"Good afternoon":"Good evening";
            greeting.setText(name().isEmpty()?part:part+", "+name());
        }
        if(status!=null){
            NetworkInfo network=((ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
            String line=network!=null&&network.isConnected()?"Online":"Offline";
            if(battery>=0)line+="   ·   Battery "+battery+"%"+(plugged?" (plugged in)":"");
            status.setText(line);
        }
        if(profileName!=null){
            String n=name();profileName.setText(n.isEmpty()?"Set name":n);
            profileInitial.setText(n.isEmpty()?"+":n.substring(0,1).toUpperCase(Locale.getDefault()));
        }
    }

    private void render(){
        preferredFocus=null;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff0d0f13,0xff0e131c,0xff101d36}));
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setClipChildren(false);page.setClipToPadding(false);
        page.setPadding(dp(58),dp(40),dp(58),dp(40));
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2));

        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setClipChildren(false);
        TextView title=text("Home",32,INK);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(profileChip());
        clock=text("",20,INK);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.setMargins(dp(22),0,0,0);header.addView(clock,cp);
        page.addView(header,new LinearLayout.LayoutParams(-1,dp(48)));

        LinearLayout system=row(page,dp(20));
        View settings=systemTile("Settings",0xff1a47a0,0xff0f2f73,true,v->open(new Intent(android.provider.Settings.ACTION_SETTINGS)));
        system.addView(settings,tileParams());
        if(installed("com.android.documentsui"))system.addView(systemTile("Files",0xff25303f,0xff1a222d,false,v->launch("com.android.documentsui")),tileParams());

        List<App> apps=apps();LinearLayout line=null;int index=0;
        for(App app:apps){
            if(index%COLUMNS==0)line=row(page,dp(index==0?26:18));
            View tile=appTile(app);line.addView(tile,tileParams());
            if(preferredFocus==null||app.pkg.equals(focusedPackage))preferredFocus=tile;
            index++;
        }
        if(preferredFocus==null)preferredFocus=settings;

        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(22),dp(20),dp(22),dp(20));
        GradientDrawable surface=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xff1a2231,0xff18294a});surface.setCornerRadius(dp(6));card.setBackground(surface);
        TextView brand=text("GabOS",12,DIM);brand.setLetterSpacing(.12f);card.addView(brand);
        greeting=text("",26,INK);greeting.setPadding(0,dp(14),0,dp(6));card.addView(greeting);
        date=text("",14,DIM);card.addView(date);
        status=text("",13,DIM);status.setPadding(0,dp(10),0,0);card.addView(status);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(156));sp.setMargins(0,dp(34),0,0);page.addView(card,sp);

        setContentView(scroll);lastPackageCount=packageCount();updateStatus();
        final View focus=preferredFocus;focus.post(()->focus.requestFocus());
    }

    private LinearLayout row(LinearLayout page,int top){
        LinearLayout r=new LinearLayout(this);r.setClipChildren(false);r.setClipToPadding(false);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,top,0,0);page.addView(r,p);return r;
    }
    private LinearLayout.LayoutParams tileParams(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(148),dp(83));p.setMargins(0,0,dp(18),0);return p;}

    /** White outline and slight growth, drawn as a foreground so the art is not rebuilt. */
    private void focusable(final View tile,final String pkg){
        tile.setFocusable(true);tile.setClickable(true);
        final GradientDrawable ring=new GradientDrawable();ring.setColor(Color.TRANSPARENT);ring.setCornerRadius(dp(6));ring.setStroke(dp(2),FOCUS);
        tile.setOnFocusChangeListener((v,focus)->{
            v.setForeground(focus?ring:null);v.setElevation(focus?dp(8):0);
            v.animate().scaleX(focus?1.08f:1f).scaleY(focus?1.08f:1f).setDuration(110).start();
            if(focus&&pkg!=null)focusedPackage=pkg;
        });
    }

    private View profileChip(){
        LinearLayout chip=new LinearLayout(this);chip.setGravity(Gravity.CENTER_VERTICAL);chip.setPadding(dp(6),dp(5),dp(16),dp(5));
        chip.setBackground(rounded(0x26ffffff,24));chip.setContentDescription("Profile name");
        profileInitial=text("",15,0xff0d0f13);profileInitial.setGravity(Gravity.CENTER);profileInitial.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable dot=new GradientDrawable();dot.setShape(GradientDrawable.OVAL);dot.setColor(0xffd2e3fc);profileInitial.setBackground(dot);
        chip.addView(profileInitial,new LinearLayout.LayoutParams(dp(30),dp(30)));
        profileName=text("",15,INK);profileName.setPadding(dp(10),0,0,0);profileName.setMaxWidth(dp(220));profileName.setSingleLine(true);profileName.setEllipsize(TextUtils.TruncateAt.END);chip.addView(profileName);
        chip.setFocusable(true);chip.setClickable(true);chip.setOnClickListener(v->askName());
        chip.setOnFocusChangeListener((v,focus)->{chip.setBackground(rounded(focus?0xffe8eaed:0x26ffffff,24));profileName.setTextColor(focus?0xff0d0f13:INK);});
        return chip;
    }

    private void askName(){
        final EditText field=new EditText(this);field.setSingleLine(true);field.setText(name());field.setSelection(field.getText().length());
        field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PERSON_NAME|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);field.setHint("Your name");
        FrameLayout frame=new FrameLayout(this);frame.setPadding(dp(24),dp(8),dp(24),0);frame.addView(field);
        final AlertDialog dialog=new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Who is watching?").setMessage("This name stays on this device. No account is created.")
            .setView(frame).setPositiveButton("Save",null).setNegativeButton("Skip",null).create();
        final Runnable save=()->{
            String value=field.getText().toString().trim();if(value.length()>24)value=value.substring(0,24);
            prefs().edit().putString("name",value).putBoolean("asked",true).apply();updateStatus();dialog.dismiss();
        };
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->save.run());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{prefs().edit().putBoolean("asked",true).apply();dialog.dismiss();});
        });
        field.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_DONE){save.run();return true;}return false;});
        dialog.show();
    }

    private View systemTile(String label,int from,int to,boolean gear,View.OnClickListener action){
        FrameLayout tile=new FrameLayout(this);
        GradientDrawable fill=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{from,to});fill.setCornerRadius(dp(6));tile.setBackground(fill);
        tile.setContentDescription(label);
        if(gear)tile.addView(new GearView(),new FrameLayout.LayoutParams(dp(30),dp(30),Gravity.CENTER));
        else{TextView t=text(label,16,INK);t.setGravity(Gravity.CENTER);tile.addView(t,new FrameLayout.LayoutParams(-1,-1));}
        focusable(tile,null);tile.setOnClickListener(action);return tile;
    }

    private View appTile(final App app){
        ImageView tile=new ImageView(this);tile.setScaleType(ImageView.ScaleType.FIT_XY);tile.setImageBitmap(art(app));
        tile.setContentDescription(app.name);focusable(tile,app.pkg);tile.setOnClickListener(v->launch(app.pkg));return tile;
    }

    /** One cached bitmap per application: its TV banner, or its icon and name on a tint. */
    private Bitmap art(App app){
        Bitmap cached=art.get(app.pkg);if(cached!=null)return cached;
        int w=dp(148),h=dp(83);Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
        Path clip=new Path();clip.addRoundRect(new RectF(0,0,w,h),dp(6),dp(6),Path.Direction.CW);canvas.clipPath(clip);
        Drawable banner=null;
        try{banner=getPackageManager().getApplicationBanner(app.pkg);}catch(Exception ignored){}
        if(banner!=null){banner.setBounds(0,0,w,h);banner.draw(canvas);}
        else{
            canvas.drawColor(TINTS[(app.pkg.hashCode()&0x7fffffff)%TINTS.length]);
            Drawable icon;try{icon=getPackageManager().getApplicationIcon(app.pkg);}catch(Exception e){icon=getResources().getDrawable(android.R.drawable.sym_def_app_icon,null);}
            int size=dp(36),left=dp(12),top=(h-size)/2;icon.setBounds(left,top,left+size,top+size);icon.draw(canvas);
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(INK);paint.setTextSize(dp(14));
            float x=left+size+dp(10),room=w-x-dp(8);String[] words=app.name.split(" ");ArrayList<String> lines=new ArrayList<>();String current="";
            for(String word:words){String next=current.isEmpty()?word:current+" "+word;if(paint.measureText(next)<=room||current.isEmpty())current=next;else{lines.add(current);current=word;}}
            if(!current.isEmpty())lines.add(current);
            while(lines.size()>3)lines.remove(lines.size()-1);
            float step=dp(18),y=h/2f-(lines.size()-1)*step/2f+dp(5);
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
    private String label(String pkg){try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception e){return "App";}}
    private Intent launchIntent(String pkg){Intent i=getPackageManager().getLeanbackLaunchIntentForPackage(pkg);if(i==null)i=getPackageManager().getLaunchIntentForPackage(pkg);return i;}
    private void launch(String pkg){open(launchIntent(pkg));}
    private void open(Intent intent){try{if(intent==null)throw new ActivityNotFoundException();startActivity(intent);overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);}catch(Exception e){Toast.makeText(this,"Could not open this app",Toast.LENGTH_SHORT).show();}}

    private class GearView extends View{
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        GearView(){super(MainActivity.this);}
        @Override protected void onDraw(Canvas c){
            float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(cx,cy);
            paint.setColor(INK);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(r*.16f);paint.setStrokeCap(Paint.Cap.ROUND);
            for(int i=0;i<8;i++){double a=Math.PI*i/4;c.drawLine(cx+(float)Math.cos(a)*r*.62f,cy+(float)Math.sin(a)*r*.62f,cx+(float)Math.cos(a)*r*.9f,cy+(float)Math.sin(a)*r*.9f,paint);}
            c.drawCircle(cx,cy,r*.58f,paint);c.drawCircle(cx,cy,r*.2f,paint);
        }
    }
}
