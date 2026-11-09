package local.rayo.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private final int bg=Color.rgb(12,16,23), card=Color.rgb(24,31,42), ink=Color.rgb(240,245,250), muted=Color.rgb(151,168,186), accent=Color.rgb(101,224,207);
    private LinearLayout root, content;
    private boolean apps=false;
    private int d(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    public void onCreate(Bundle state){super.onCreate(state);home();}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private GradientDrawable shape(int color,int stroke){GradientDrawable s=new GradientDrawable();s.setColor(color);s.setCornerRadius(d(14));if(stroke!=0)s.setStroke(d(2),stroke);return s;}
    private void base(String title){
        root=new LinearLayout(this);root.setOrientation(1);root.setPadding(d(36),d(24),d(36),d(20));root.setBackgroundColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand=new LinearLayout(this);brand.setOrientation(1);
        TextView mark=text("RAYO / DARK",14,accent);mark.setTypeface(null,Typeface.BOLD);brand.addView(mark);
        TextView heading=text(title,30,ink);heading.setTypeface(null,Typeface.BOLD);brand.addView(heading);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView time=text(new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()),28,muted);header.addView(time);root.addView(header);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setClipToPadding(false);
        content=new LinearLayout(this);content.setOrientation(1);content.setPadding(0,d(20),0,d(8));scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView note=text("Tu proyector, a tu manera.",13,muted);footer.addView(note,new LinearLayout.LayoutParams(0,-2,1));
        TextView canon=text("Volver a Canon  →",15,accent);canon.setPadding(d(16),d(12),d(16),d(12));canon.setFocusable(true);canon.setBackground(shape(card,0));canon.setOnClickListener(v->original());footer.addView(canon);
        root.addView(footer);setContentView(root);
    }
    private void home(){apps=false;base("Elige qué quieres hacer");
        LinearLayout row=row(); tile(row,"01  /  REPRODUCIR","Vídeos","Tu reproductor de siempre",()->launch("android.rk.RockVideoPlayer"));tile(row,"02  /  ESCUCHAR","Música","Audio y biblioteca local",()->launch("com.android.music"));tile(row,"03  /  CONECTAR","Compartir pantalla","Abre EShare",()->launch("com.ecloud.eshare.server"));
        row=row();tile(row,"04  /  EXPLORAR","Navegador","Abre tu navegador",()->launch("acr.browser.barebones"));tile(row,"05  /  PERSONALIZAR","Aplicaciones","Todas tus apps",()->applications());tile(row,"06  /  CONTROLAR","Ajustes","Opciones del proyector",()->launch("com.hotack.hotackkoreasettings"));
    }
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(0);content.addView(r,new LinearLayout.LayoutParams(-1,-2));return r;}
    private void tile(LinearLayout row,String kicker,String title,String detail,final Runnable action){
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setGravity(Gravity.CENTER_VERTICAL);box.setPadding(d(22),d(16),d(22),d(16));box.setBackground(shape(card,0));box.setFocusable(true);box.setClickable(true);box.setContentDescription(title);box.setMinimumHeight(d(135));
        TextView k=text(kicker,11,accent);k.setTypeface(null,Typeface.BOLD);box.addView(k);
        TextView h=text(title,23,ink);h.setTypeface(null,Typeface.BOLD);h.setPadding(0,d(8),0,d(5));box.addView(h);box.addView(text(detail,13,muted));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(d(5),d(5),d(5),d(5));row.addView(box,p);
        box.setOnClickListener(v->action.run());box.setOnFocusChangeListener((v,focused)->box.setBackground(shape(focused?Color.rgb(31,52,62):card,focused?accent:0)));
    }
    private void launch(String name){Intent intent=getPackageManager().getLaunchIntentForPackage(name);if(intent==null){Toast.makeText(this,"No se encontró esta aplicación",Toast.LENGTH_SHORT).show();return;}try{startActivity(intent);}catch(Exception e){Toast.makeText(this,"No se pudo abrir la aplicación",Toast.LENGTH_SHORT).show();}}
    private void original(){Intent i=new Intent();i.setClassName("com.htc.htcrayolauncher","com.htc.activity.HomeActivity");try{startActivity(i);}catch(Exception e){Toast.makeText(this,"No se pudo abrir el menú Canon",Toast.LENGTH_SHORT).show();}}
    private void applications(){apps=true;base("Tus aplicaciones");Intent query=new Intent(Intent.ACTION_MAIN);query.addCategory(Intent.CATEGORY_LAUNCHER);List<ResolveInfo> list=getPackageManager().queryIntentActivities(query,0);Collections.sort(list,new ResolveInfo.DisplayNameComparator(getPackageManager()));LinearLayout row=null;int count=0;
        for(final ResolveInfo item:list){if(item.activityInfo.packageName.equals(getPackageName()))continue;if(count%3==0)row=row();final String pkg=item.activityInfo.packageName;tile(row,"APLICACIÓN",item.loadLabel(getPackageManager()).toString(),"Abrir",()->launch(pkg));count++;}
        if(row!=null)while(count%3!=0){row.addView(new View(this),new LinearLayout.LayoutParams(0,d(135),1));count++;}
    }
    @Override public void onBackPressed(){if(apps)home();else original();}
}
