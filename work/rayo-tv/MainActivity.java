package local.rayo.launcher;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final int BG=0xff0c0f14, CARD=0xff20252e, WHITE=0xfff5f6f8, DIM=0xffa4acb9, CYAN=0xff43cce8;
    private final Handler handler=new Handler();
    private LinearLayout root, body;
    private TextView clock, batteryLabel;
    private BatteryView batteryIcon;
    private int page=0, battery=-1;
    private boolean charging=false, plugged=false, receiverRegistered=false, created=false;
    private int lastPackageCount=-1;
    private String focusedPackage="";
    private View preferredFocus;
    private final BroadcastReceiver batteryReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context, Intent intent){
            int level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1), scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
            battery=level>=0 && scale>0 ? Math.round(level*100f/scale) : -1;
            int status=intent.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
            charging=status==BatteryManager.BATTERY_STATUS_CHARGING;
            plugged=intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
            updateStatus();
        }
    };
    private final Runnable tick=new Runnable(){public void run(){updateStatus();handler.postDelayed(this,30000);}};
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    @Override public void onCreate(Bundle state){super.onCreate(state);render(0);created=true;handler.post(tick);}
    @Override protected void onResume(){super.onResume();if(!receiverRegistered){registerReceiver(batteryReceiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));receiverRegistered=true;}if(created&&getPackageManager().getInstalledApplications(0).size()!=lastPackageCount)render(page);updateStatus();}
    @Override protected void onPause(){if(receiverRegistered){unregisterReceiver(batteryReceiver);receiverRegistered=false;}super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(tick);super.onDestroy();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(page!=0||getPackageManager().getInstalledApplications(0).size()!=lastPackageCount){focusedPackage="";render(0);}else updateStatus();}
    @Override public void onBackPressed(){if(page!=0)render(0);}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");return t;}
    private GradientDrawable rounded(int fill,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(radius));if(stroke!=0)g.setStroke(dp(2),stroke);return g;}
    private int brand(){return getResources().getIdentifier("brand","drawable",getPackageName());}
    private void updateStatus(){
        if(clock!=null)clock.setText(new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()));
        if(batteryLabel!=null){batteryLabel.setText(battery<0?"—":battery+"%"+(plugged?(charging&&battery<100?" · Cargando":" · Conectado"):""));batteryLabel.setTextColor(battery>=0&&battery<=15&&!plugged?0xffffb36b:DIM);}
        if(batteryIcon!=null){batteryIcon.level=battery;batteryIcon.charging=plugged;batteryIcon.invalidate();}
    }
    private void render(int requestedPage){
        page=requestedPage;preferredFocus=null;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(42),dp(24),dp(42),dp(12));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff111b24,BG,0xff12131d}));
        header();
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(false);
        body=new LinearLayout(this);body.setOrientation(1);body.setPadding(0,dp(18),0,dp(8));body.setClipChildren(false);scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(page==0)home();else if(page==1)appGrid();else settings();
        TextView footer=text("Tu espacio. Tus apps.",12,0xff687383);footer.setPadding(dp(5),dp(4),0,0);root.addView(footer);
        setContentView(root);lastPackageCount=getPackageManager().getInstalledApplications(0).size();updateStatus();
        if(preferredFocus!=null){final View focus=preferredFocus;focus.post(()->focus.requestFocus());}
    }
    private void header(){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setClipChildren(false);
        ImageView logo=new ImageView(this);logo.setImageResource(brand());row.addView(logo,new LinearLayout.LayoutParams(dp(38),dp(38)));
        TextView name=text("RAYO",15,WHITE);name.setLetterSpacing(.18f);name.setTypeface(null,Typeface.BOLD);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-2,-2);np.setMargins(dp(10),0,dp(34),0);row.addView(name,np);
        String[] names={"Inicio","Apps","Ajustes"};
        for(int i=0;i<names.length;i++){
            final int destination=i;final boolean selected=page==i;
            TextView tab=text(names[i],17,selected?0xff11151d:DIM);tab.setGravity(Gravity.CENTER);tab.setPadding(dp(20),dp(10),dp(20),dp(10));tab.setTypeface(null,Typeface.BOLD);
            tab.setBackground(rounded(selected?WHITE:Color.TRANSPARENT,24,0));tab.setFocusable(true);tab.setClickable(true);
            tab.setOnClickListener(v->{focusedPackage="";render(destination);});
            tab.setOnFocusChangeListener((v,focus)->{tab.setBackground(rounded(focus?0xff36434f:selected?WHITE:Color.TRANSPARENT,24,focus?CYAN:0));tab.setTextColor(focus?WHITE:selected?0xff11151d:DIM);});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.setMargins(0,0,dp(6),0);row.addView(tab,p);
        }
        row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        batteryIcon=new BatteryView();row.addView(batteryIcon,new LinearLayout.LayoutParams(dp(27),dp(16)));
        batteryLabel=text("",13,DIM);batteryLabel.setPadding(dp(7),0,dp(20),0);row.addView(batteryLabel);
        clock=text("",20,WHITE);clock.setTypeface(null,Typeface.BOLD);row.addView(clock);root.addView(row,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    private void home(){
        FrameLayout hero=new FrameLayout(this);hero.setBackground(rounded(0xff18242e,24,0));
        ImageView mark=new ImageView(this);mark.setImageResource(brand());mark.setAlpha(.65f);mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(dp(284),dp(284),Gravity.RIGHT|Gravity.CENTER_VERTICAL);mp.setMargins(0,0,dp(45),0);hero.addView(mark,mp);
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(1);copy.setGravity(Gravity.CENTER_VERTICAL);copy.setPadding(dp(32),dp(22),0,dp(20));
        TextView eyebrow=text("HECHO PARA TU PANTALLA",11,CYAN);eyebrow.setLetterSpacing(.15f);eyebrow.setTypeface(null,Typeface.BOLD);copy.addView(eyebrow);
        TextView title=text("Dale al play.",40,WHITE);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.setPadding(0,dp(7),0,dp(5));copy.addView(title);
        TextView description=text("Vídeos, música y tus apps favoritas.\nTodo en un mismo lugar.",16,DIM);description.setLineSpacing(dp(3),1);copy.addView(description);
        final String feature=installed("org.smarttube.stable")?"org.smarttube.stable":"android.rk.RockVideoPlayer";
        LinearLayout play=new LinearLayout(this);play.setGravity(Gravity.CENTER_VERTICAL);play.setPadding(dp(16),dp(9),dp(22),dp(9));play.setBackground(rounded(WHITE,28,0));play.setFocusable(true);play.setClickable(true);play.setContentDescription("Abrir "+label(feature));
        ImageView icon=new ImageView(this);icon.setImageBitmap(icon(feature,28));play.addView(icon,new LinearLayout.LayoutParams(dp(28),dp(28)));
        TextView caption=text("Abrir "+label(feature),16,0xff11151d);caption.setTypeface(null,Typeface.BOLD);caption.setPadding(dp(10),0,0,0);play.addView(caption);
        play.setOnClickListener(v->launch(feature));play.setOnFocusChangeListener((v,focus)->play.setBackground(rounded(focus?0xffc8f6ff:WHITE,28,focus?CYAN:0)));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-2,-2);pp.setMargins(0,dp(18),0,0);copy.addView(play,pp);
        hero.addView(copy,new FrameLayout.LayoutParams(-1,-1));body.addView(hero,new LinearLayout.LayoutParams(-1,dp(272)));
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);heading.setPadding(dp(4),dp(24),dp(4),dp(8));
        TextView titleApps=text("Tus aplicaciones",21,WHITE);titleApps.setTypeface(null,Typeface.BOLD);heading.addView(titleApps,new LinearLayout.LayoutParams(0,-2,1));
        TextView all=text("Ver todas  ›",14,DIM);all.setPadding(dp(14),dp(8),dp(14),dp(8));all.setFocusable(true);all.setBackground(rounded(Color.TRANSPARENT,18,0));all.setOnClickListener(v->render(1));all.setOnFocusChangeListener((v,f)->all.setBackground(rounded(f?CARD:Color.TRANSPARENT,18,f?CYAN:0)));heading.addView(all);body.addView(heading);
        HorizontalScrollView rail=new HorizontalScrollView(this);rail.setHorizontalScrollBarEnabled(false);rail.setClipToPadding(false);rail.setClipChildren(false);
        LinearLayout apps=new LinearLayout(this);apps.setOrientation(0);apps.setClipChildren(false);apps.setPadding(dp(5),dp(8),dp(10),dp(12));
        List<App> available=apps();for(App app:available){View tile=appTile(app,166,104);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(166),-2);p.setMargins(0,0,dp(15),0);apps.addView(tile,p);if(preferredFocus==null||app.pkg.equals(focusedPackage))preferredFocus=tile;}
        rail.addView(apps);body.addView(rail,new LinearLayout.LayoutParams(-1,-2));
        if(preferredFocus==null)preferredFocus=play;
    }
    private View appTile(final App app,int width,int height){
        LinearLayout group=new LinearLayout(this);group.setOrientation(1);group.setGravity(Gravity.CENTER_HORIZONTAL);group.setClipChildren(false);group.setFocusable(true);group.setClickable(true);group.setContentDescription(app.name);
        FrameLayout face=new FrameLayout(this);face.setBackground(rounded(CARD,18,0));
        ImageView image=new ImageView(this);image.setImageBitmap(icon(app.pkg,68));image.setScaleType(ImageView.ScaleType.FIT_CENTER);FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(68),dp(68),Gravity.CENTER);face.addView(image,ip);group.addView(face,new LinearLayout.LayoutParams(-1,dp(height)));
        TextView caption=text(app.name,14,DIM);caption.setGravity(Gravity.CENTER);caption.setSingleLine(true);caption.setEllipsize(TextUtils.TruncateAt.END);caption.setPadding(dp(3),dp(10),dp(3),dp(4));group.addView(caption,new LinearLayout.LayoutParams(-1,-2));
        group.setOnClickListener(v->launch(app.pkg));group.setOnFocusChangeListener((v,focus)->{face.setBackground(rounded(focus?0xff354652:CARD,18,focus?WHITE:0));caption.setTextColor(focus?WHITE:DIM);group.animate().scaleX(focus?1.035f:1f).scaleY(focus?1.035f:1f).setDuration(120).start();if(focus)focusedPackage=app.pkg;});
        return group;
    }
    private void appGrid(){
        TextView title=text("Todas tus apps",30,WHITE);title.setTypeface(null,Typeface.BOLD);title.setPadding(dp(4),dp(8),0,dp(22));body.addView(title);
        List<App> list=apps();LinearLayout row=null;int n=0;
        for(App app:list){if(n%5==0){row=new LinearLayout(this);row.setClipChildren(false);body.addView(row,new LinearLayout.LayoutParams(-1,-2));}View tile=appTile(app,180,112);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(7),dp(7),dp(7),dp(12));row.addView(tile,p);if(preferredFocus==null||app.pkg.equals(focusedPackage))preferredFocus=tile;n++;}
        if(row!=null)while(n++%5!=0)row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
    }
    private void settings(){
        TextView title=text("A tu manera",30,WHITE);title.setTypeface(null,Typeface.BOLD);title.setPadding(dp(4),dp(12),0,dp(24));body.addView(title);
        setting("Ajustes del proyector","Imagen, conexión y opciones del equipo","com.hotack.hotackkoreasettings",false);
        setting("Ajustes de Android","Conexiones, aplicaciones y preferencias","com.android.settings",false);
        setting("Volver al menú Canon","Abrir la interfaz original del proyector","com.htc.htcrayolauncher",true);
    }
    private void setting(String name,String detail,final String pkg,final boolean canon){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(22),dp(18),dp(22),dp(18));row.setBackground(rounded(CARD,18,0));row.setFocusable(true);row.setClickable(true);
        ImageView icon=new ImageView(this);icon.setImageBitmap(icon(pkg,42));row.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(1);copy.setPadding(dp(18),0,0,0);TextView t=text(name,20,WHITE);t.setTypeface(null,Typeface.BOLD);copy.addView(t);copy.addView(text(detail,14,DIM));row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));row.addView(text("›",28,DIM));
        row.setOnClickListener(v->{if(canon)original();else launch(pkg);});row.setOnFocusChangeListener((v,f)->row.setBackground(rounded(f?0xff354652:CARD,18,f?WHITE:0)));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(4),0,dp(4),dp(12));body.addView(row,p);if(preferredFocus==null)preferredFocus=row;
    }
    private static class App{final String pkg,name;App(String p,String n){pkg=p;name=n;}}
    private List<App> apps(){
        LinkedHashMap<String,App> result=new LinkedHashMap<>();
        String[] pinned={"org.smarttube.stable","android.rk.RockVideoPlayer","com.android.music","com.ecloud.eshare.server","acr.browser.barebones","com.android.gallery3d","com.hotack.hotackkoreasettings"};
        for(String pkg:pinned)if(installed(pkg)&&launchIntent(pkg)!=null)result.put(pkg,new App(pkg,label(pkg)));
        List<ResolveInfo> found=new ArrayList<>();for(String category:new String[]{Intent.CATEGORY_LAUNCHER,"android.intent.category.LEANBACK_LAUNCHER"}){Intent i=new Intent(Intent.ACTION_MAIN);i.addCategory(category);found.addAll(getPackageManager().queryIntentActivities(i,0));}
        Collections.sort(found,new ResolveInfo.DisplayNameComparator(getPackageManager()));
        for(ResolveInfo info:found){String pkg=info.activityInfo.packageName;if(pkg.equals(getPackageName())||result.containsKey(pkg))continue;result.put(pkg,new App(pkg,info.loadLabel(getPackageManager()).toString()));}
        return new ArrayList<>(result.values());
    }
    private boolean installed(String pkg){try{getPackageManager().getApplicationInfo(pkg,0);return true;}catch(PackageManager.NameNotFoundException e){return false;}}
    private String label(String pkg){if(pkg.equals("org.smarttube.stable"))return "SmartTube";if(pkg.equals("android.rk.RockVideoPlayer"))return "Vídeos";if(pkg.equals("com.hotack.hotackkoreasettings"))return "Proyector";if(pkg.equals("acr.browser.barebones"))return "Navegador";if(pkg.equals("com.android.music"))return "Música";if(pkg.equals("com.android.gallery3d"))return "Galería";if(pkg.equals("com.ecloud.eshare.server"))return "EShare";try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception e){return "Aplicación";}}
    private Intent launchIntent(String pkg){Intent i=getPackageManager().getLaunchIntentForPackage(pkg);if(i==null)i=getPackageManager().getLeanbackLaunchIntentForPackage(pkg);return i;}
    private void launch(String pkg){Intent i=launchIntent(pkg);try{if(i==null)throw new ActivityNotFoundException();startActivity(i);}catch(Exception e){Toast.makeText(this,"No se pudo abrir esta aplicación",Toast.LENGTH_SHORT).show();}}
    private Bitmap icon(String pkg,int size){Drawable drawable;try{drawable=getPackageManager().getApplicationIcon(pkg);}catch(Exception e){drawable=getResources().getDrawable(android.R.drawable.sym_def_app_icon,null);}Bitmap b=Bitmap.createBitmap(dp(size),dp(size),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);drawable.setBounds(0,0,b.getWidth(),b.getHeight());drawable.draw(canvas);return b;}
    private void original(){Intent i=new Intent();i.setClassName("com.htc.htcrayolauncher","com.htc.activity.HomeActivity");try{startActivity(i);}catch(Exception e){Toast.makeText(this,"No se pudo abrir Canon",Toast.LENGTH_SHORT).show();}}
    private class BatteryView extends View{
        int level=-1;boolean charging=false;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        BatteryView(){super(MainActivity.this);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();int color=level>=0&&level<=15&&!charging?0xffffb36b:charging?CYAN:DIM;paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));c.drawRoundRect(dp(1),dp(1),w-dp(4),h-dp(1),dp(3),dp(3),paint);paint.setStyle(Paint.Style.FILL);c.drawRoundRect(w-dp(3),h*.32f,w,h*.68f,dp(1),dp(1),paint);if(level>=0)c.drawRoundRect(dp(4),dp(4),dp(4)+(w-dp(11))*Math.max(0,Math.min(level,100))/100f,h-dp(4),dp(1),dp(1),paint);}
    }
}
