package local.rayo.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Canon Rayo S1 optical engine controls. The factory DPP2607 driver exposes
 * world-writable sysfs nodes; the value ranges and paths are those used by the
 * factory android.os.PrjScreen class (7.1.2 framework). The driver does not keep
 * settings across a power cycle, so GabOS stores them and reapplies them.
 */
final class Projector {
    static final String DLP="/sys/devices/20072000.i2c/i2c-0/0-001b/";
    static final String WARP="/sys/devices/20072000.i2c/i2c-0/0-0020/";
    private Projector(){}

    static SharedPreferences prefs(Context c){return c.getSharedPreferences("projector",Context.MODE_PRIVATE);}

    static boolean write(String path,String value){
        try(FileOutputStream out=new FileOutputStream(path)){out.write(value.getBytes(StandardCharsets.US_ASCII));return true;}
        catch(Exception e){return false;}
    }

    static String read(String path){
        try(FileInputStream in=new FileInputStream(path)){byte[] b=new byte[128];int n=in.read(b);return n>0?new String(b,0,n,StandardCharsets.US_ASCII).trim():"";}
        catch(Exception e){return "";}
    }

    static boolean available(){return new java.io.File(DLP+"panel_brightness").canWrite();}

    static int brightness(Context c){return prefs(c).getInt("brightness",100);}
    static void setBrightness(Context c,int v){v=Math.max(0,Math.min(100,v));prefs(c).edit().putInt("brightness",v).apply();write(DLP+"panel_brightness",Integer.toString(v));}

    static int colorTemperature(Context c){return prefs(c).getInt("color",0);}
    static void setColorTemperature(Context c,int v){v=Math.max(0,Math.min(7,v));prefs(c).edit().putInt("color",v).apply();write(DLP+"panel_color_temperature",Integer.toString(v));}

    /** Factory range 1-4, factory default 3. */
    static int orientation(Context c){return prefs(c).getInt("rotate",3);}
    static void setOrientation(Context c,int v){v=Math.max(1,Math.min(4,v));prefs(c).edit().putInt("rotate",v).apply();
        write(DLP+"panel_autorotate_enable","0");write(DLP+"panel_rotate",Integer.toString(v));}


    /**
     * panel_rotate value for each placement picture: table, ceiling, table
     * behind the screen, ceiling behind the screen. 3 is the factory default
     * (table). The other three follow the driver's rotate order and must be
     * confirmed on the lens.
     */
    static final int[] PLACEMENT_VALUES={3,1,4,2};

    /** Hardware keystone of the T113 warp processor; -1 means not set. */
    static int manualKeystone(Context c){return prefs(c).getInt("manual_keystone",0);}
    static void setManualKeystone(Context c,int v){v=Math.max(-40,Math.min(40,v));prefs(c).edit().putInt("manual_keystone",v).apply();
        write(WARP+"panel_Keystone_enbale","0");write(WARP+"panel_manual_keystone",Integer.toString(v));}


    static boolean lightWithScreen(Context c){return prefs(c).getBoolean("light_with_screen",true);}
    static void setLightWithScreen(Context c,boolean on){prefs(c).edit().putBoolean("light_with_screen",on).apply();}


    /** Turns the optical engine (light source) off or on; image content is untouched. */
    static void light(boolean on){write(DLP+"panel_pmu_off",on?"0":"1");}

    static String model(){String t=read(DLP+"panel_type");return t.isEmpty()?"unknown":t;}
    static String ledCurrents(){return read(DLP+"panel_brightness").replaceAll(",$","");}

    /** Reapply only what the owner changed; untouched values keep the driver defaults. */
    static void restore(Context c){
        if(!available())return;
        SharedPreferences p=prefs(c);
        light(true);
        if(p.contains("brightness"))write(DLP+"panel_brightness",Integer.toString(brightness(c)));
        if(p.contains("color"))write(DLP+"panel_color_temperature",Integer.toString(colorTemperature(c)));
        // The tilt sensor does not work on this unit (owner check): placement and
        // keystone are always manual.
        write(DLP+"panel_autorotate_enable","0");
        write(DLP+"panel_rotate",Integer.toString(orientation(c)));
        write(WARP+"panel_Keystone_enbale","0");
        if(p.contains("manual_keystone"))write(WARP+"panel_manual_keystone",Integer.toString(manualKeystone(c)));
    }
}
