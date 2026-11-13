package local.rayo.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import local.gabos.ui.GabosUi;

/**
 * Read-only GabOS theme shared with every GabOS app:
 * content://local.gabos.theme/theme returns one row with "accent" (ARGB int)
 * and "mode" ("dark" or "light"). Only GabOS Settings in this package writes it,
 * through {@link #save}, which notifies observers of that URI.
 */
public class ThemeProvider extends ContentProvider {
    private static final String PREFS="theme";

    static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    static int accent(Context c){return prefs(c).getInt("accent",GabosUi.DEFAULT_ACCENT)|0xff000000;}
    static boolean light(Context c){return "light".equals(prefs(c).getString("mode","dark"));}

    static void save(Context c,int accent,boolean light){
        prefs(c).edit().putInt("accent",accent|0xff000000).putString("mode",light?"light":"dark").commit();
        c.getContentResolver().notifyChange(GabosUi.THEME_URI,null);
    }

    @Override public boolean onCreate(){return true;}

    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        if(!"theme".equals(uri.getLastPathSegment()))return null;
        Context c=getContext();
        MatrixCursor cursor=new MatrixCursor(new String[]{"accent","mode"},1);
        cursor.addRow(new Object[]{accent(c),light(c)?"light":"dark"});
        cursor.setNotificationUri(c.getContentResolver(),GabosUi.THEME_URI);
        return cursor;
    }

    @Override public String getType(Uri uri){return "vnd.android.cursor.item/vnd.local.gabos.theme";}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read-only");}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("Read-only");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("Read-only");}
}
