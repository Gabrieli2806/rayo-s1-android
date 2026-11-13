package local.gabos.share;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;

/**
 * Colours for this app, read from the launcher's shared theme provider
 * (content://local.gabos.theme/theme: columns "accent" ARGB int and "mode"
 * "dark" or "light"). Falls back to dark mode and the default accent when the
 * provider is missing.
 */
final class ShareTheme {
    static final Uri URI=Uri.parse("content://local.gabos.theme/theme");
    static final int DEFAULT_ACCENT=0xff8ab4f8;

    final int accent;
    final boolean light;
    final int ink, dim, card, cardHigh, focus, onAccent, neutral, scrim;

    private ShareTheme(int accent,boolean light){
        this.accent=accent|0xff000000;this.light=light;
        ink=light?0xff1b1f24:0xfff1f3f4;
        dim=light?0xff5b636e:0xffaab0b8;
        card=light?0xffffffff:0xff1b222d;
        cardHigh=light?0xffe9eef6:0xff222935;
        focus=light?0xff1b1f24:0xffffffff;
        neutral=light?0xffdfe4ec:0xff2a2f37;
        scrim=light?0xb0eef2f8:0xc0000000;
        onAccent=luminance(this.accent)>0.45?0xff0d0f13:0xffffffff;
    }

    static ShareTheme load(Context context){
        int accent=DEFAULT_ACCENT;boolean light=false;
        Cursor c=null;
        try{
            c=context.getContentResolver().query(URI,null,null,null,null);
            if(c!=null&&c.moveToFirst()){
                int a=c.getColumnIndex("accent"),m=c.getColumnIndex("mode");
                if(a>=0&&!c.isNull(a))accent=c.getInt(a);
                if(m>=0&&!c.isNull(m))light="light".equalsIgnoreCase(c.getString(m));
            }
        }catch(RuntimeException ignored){
            // Provider missing or failing: keep the defaults.
        }finally{if(c!=null)c.close();}
        return new ShareTheme(accent,light);
    }

    GradientDrawable background(){
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,light
            ?new int[]{0xfff6f8fb,0xffeef2f8,0xffe1e9f5}
            :new int[]{0xff0d0f13,0xff0e131c,0xff101d36});
    }

    GradientDrawable surface(float radiusPx){
        GradientDrawable g=new GradientDrawable();g.setColor(card);g.setCornerRadius(radiusPx);return g;
    }

    private static double luminance(int color){
        return (0.2126*Color.red(color)+0.7152*Color.green(color)+0.0722*Color.blue(color))/255.0;
    }
}
