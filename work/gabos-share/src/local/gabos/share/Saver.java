package local.gabos.share;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Stores a received file in the shared media collections: Pictures/GabOS for
 * photos and Movies/GabOS for videos. On Android 10 and newer the MediaStore
 * insert with a relative path is tried first; if the media provider refuses
 * it, the file is written to the public directory directly (this needs the
 * storage permission) and handed to the media scanner.
 */
final class Saver {
    private Saver(){}

    static final String FOLDER="GabOS";

    static void save(Context context,File source,String name,String mime) throws IOException {
        boolean video=mime.startsWith("video/");
        if(Build.VERSION.SDK_INT>=29){
            try{saveWithMediaStore(context,source,name,mime,video);return;}
            catch(IOException|RuntimeException e){
                android.util.Log.w("GabOSShare","MediaStore insert refused, using the public folder: "+e);
            }
        }
        saveToPublicFolder(context,source,name,mime,video);
    }

    private static void saveWithMediaStore(Context context,File source,String name,String mime,boolean video) throws IOException {
        ContentResolver resolver=context.getContentResolver();
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        // Column names of API 29 (RELATIVE_PATH, IS_PENDING), spelled out because this app compiles against API 26.
        values.put("relative_path",(video?Environment.DIRECTORY_MOVIES:Environment.DIRECTORY_PICTURES)+"/"+FOLDER);
        values.put("is_pending",1);
        Uri collection=video?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri item=resolver.insert(collection,values);
        if(item==null)throw new IOException("MediaStore insert returned null");
        boolean ok=false;
        try{
            try(InputStream in=new FileInputStream(source);OutputStream out=resolver.openOutputStream(item)){
                if(out==null)throw new IOException("No output stream for "+item);
                copy(in,out);
            }
            ContentValues done=new ContentValues();done.put("is_pending",0);
            resolver.update(item,done,null,null);
            ok=true;
        }finally{
            if(!ok)try{resolver.delete(item,null,null);}catch(RuntimeException ignored){}
        }
    }

    private static void saveToPublicFolder(Context context,File source,String name,String mime,boolean video) throws IOException {
        File dir=new File(Environment.getExternalStoragePublicDirectory(video?Environment.DIRECTORY_MOVIES:Environment.DIRECTORY_PICTURES),FOLDER);
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create "+dir);
        File target=unique(dir,name);
        try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(target)){copy(in,out);}
        catch(IOException e){//noinspection ResultOfMethodCallIgnored
            target.delete();throw e;}
        MediaScannerConnection.scanFile(context.getApplicationContext(),new String[]{target.getAbsolutePath()},new String[]{mime},null);
    }

    private static File unique(File dir,String name){
        File f=new File(dir,name);
        if(!f.exists())return f;
        int dot=name.lastIndexOf('.');
        String base=dot>0?name.substring(0,dot):name,ext=dot>0?name.substring(dot):"";
        for(int i=2;;i++){f=new File(dir,base+" ("+i+")"+ext);if(!f.exists())return f;}
    }

    static void copy(InputStream in,OutputStream out) throws IOException {
        byte[] buffer=new byte[64*1024];int n;
        while((n=in.read(buffer))>0)out.write(buffer,0,n);
    }
}
