package local.gabos.share.sender;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Sends the chosen items to a projector on a background thread:
 * POST /offer, wait for the projector user's answer, then POST /upload for
 * each file with progress. See work/gabos-share/PROTOCOL.md.
 */
final class Sender implements Runnable {
    static final class Item {
        final Uri uri; final String name, mime; long size;
        File temp;
        Item(Uri uri,String name,String mime,long size){this.uri=uri;this.name=name;this.mime=mime;this.size=size;}
    }

    interface Callback {
        void status(String text,boolean error);
        void progress(float fraction);
        void finished(boolean success);
    }

    private final Context context;
    private final String base, senderName;
    private final boolean show;
    private final List<Item> items;
    private final Callback callback;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private volatile HttpURLConnection active;
    private String transferId;

    Sender(Context context,String authority,String senderName,boolean show,List<Item> items,Callback callback){
        this.context=context.getApplicationContext();this.base="http://"+authority;this.senderName=senderName;
        this.show=show;this.items=items;this.callback=callback;
    }

    void cancel(){
        cancelled=true;
        HttpURLConnection c=active;if(c!=null)try{c.disconnect();}catch(RuntimeException ignored){}
    }

    @Override public void run(){
        boolean ok=false;
        try{
            measure();
            if(cancelled)return;
            status(context.getString(R.string.status_waiting),false);
            JSONObject answer=offer();
            if(!answer.optBoolean("accepted",false)){
                String reason=answer.optString("reason","");
                int message="declined".equals(reason)?R.string.error_declined:"timeout".equals(reason)?R.string.error_timeout
                    :"busy".equals(reason)?R.string.error_busy:"no space".equals(reason)?R.string.error_space:R.string.error_generic;
                status(context.getString(message),true);
                return;
            }
            transferId=answer.getString("id");
            long total=0;for(Item i:items)total+=i.size;
            long done=0;
            for(int i=0;i<items.size();i++){
                if(cancelled)break;
                Item item=items.get(i);
                status(context.getString(R.string.status_sending,i+1,items.size(),item.name),false);
                upload(i,item,done,total);
                done+=item.size;
            }
            if(cancelled){cancelRemote();status(context.getString(R.string.status_cancelled),true);return;}
            progress(1f);
            status(context.getString(show?R.string.status_done_show:R.string.status_done_save),false);
            ok=true;
        }catch(Exception e){
            if(cancelled){cancelRemote();status(context.getString(R.string.status_cancelled),true);}
            else{
                cancelRemote();
                String detail=e instanceof ServerError?e.getMessage():null;
                status(detail!=null?context.getString(R.string.error_projector,detail):context.getString(R.string.error_network),true);
            }
        }finally{
            for(Item i:items)if(i.temp!=null){//noinspection ResultOfMethodCallIgnored
                i.temp.delete();i.temp=null;}
            final boolean success=ok;
            main.post(()->callback.finished(success));
        }
    }

    /** Some providers do not report a size; those items are copied to the cache first. */
    private void measure() throws IOException {
        ContentResolver resolver=context.getContentResolver();
        for(Item item:items){
            if(item.size>=0)continue;
            try(AssetFileDescriptor fd=resolver.openAssetFileDescriptor(item.uri,"r")){
                if(fd!=null&&fd.getLength()>=0){item.size=fd.getLength();continue;}
            }catch(Exception ignored){}
            status(context.getString(R.string.status_preparing,item.name),false);
            File temp=File.createTempFile("share",".tmp",context.getCacheDir());
            item.temp=temp; // Registered first so run()'s finally deletes it even when the copy fails or is cancelled.
            try(InputStream in=resolver.openInputStream(item.uri);OutputStream out=new FileOutputStream(temp)){
                if(in==null)throw new IOException("cannot open "+item.uri);
                byte[] b=new byte[64*1024];int n;
                while((n=in.read(b))>0){if(cancelled)throw new IOException("cancelled");out.write(b,0,n);}
            }
            item.size=temp.length();
        }
    }

    private JSONObject offer() throws IOException, JSONException {
        JSONArray list=new JSONArray();
        for(Item i:items)list.put(new JSONObject().put("name",i.name).put("mime",i.mime).put("size",i.size));
        byte[] body=new JSONObject().put("sender",senderName).put("mode",show?"show":"save").put("items",list).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c=open("/offer",body.length,"application/json; charset=utf-8");
        c.setReadTimeout(80_000); // The projector user has 60 s to answer.
        try(OutputStream out=c.getOutputStream()){out.write(body);}
        return response(c);
    }

    private void upload(int index,Item item,long before,long total) throws IOException, JSONException {
        HttpURLConnection c=open("/upload?id="+URLEncoder.encode(transferId,"UTF-8")+"&index="+index,item.size,item.mime);
        c.setReadTimeout(120_000); // Saving a large video on the projector can take a while after the last byte.
        try(InputStream in=item.temp!=null?new FileInputStream(item.temp):context.getContentResolver().openInputStream(item.uri);
            OutputStream out=c.getOutputStream()){
            if(in==null)throw new IOException("cannot open "+item.uri);
            byte[] buffer=new byte[64*1024];
            long sent=0,lastPost=0;int n;
            while(sent<item.size&&(n=in.read(buffer,0,(int)Math.min(buffer.length,item.size-sent)))>0){
                if(cancelled)throw new IOException("cancelled");
                out.write(buffer,0,n);sent+=n;
                long now=System.currentTimeMillis();
                if(now-lastPost>100){lastPost=now;progress((before+sent)/(float)Math.max(1,total));}
            }
            if(sent!=item.size)throw new IOException("file changed while sending");
        }
        response(c);
    }

    private HttpURLConnection open(String path,long length,String type) throws IOException {
        HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();
        c.setConnectTimeout(6000);c.setDoOutput(true);c.setRequestMethod("POST");c.setUseCaches(false);
        c.setRequestProperty("Content-Type",type);c.setRequestProperty("Connection","close");
        c.setFixedLengthStreamingMode(length);
        active=c;
        return c;
    }

    private JSONObject response(HttpURLConnection c) throws IOException, JSONException {
        try{
            int code=c.getResponseCode();
            InputStream in=code>=400?c.getErrorStream():c.getInputStream();
            String text="";
            if(in!=null)try(InputStream s=in){
                ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
                while((n=s.read(buf))>0&&b.size()<65536)b.write(buf,0,n);
                text=new String(b.toByteArray(),StandardCharsets.UTF_8);
            }
            JSONObject json=text.isEmpty()?new JSONObject():new JSONObject(text);
            // 409 busy and 507 no space come back as a normal refusal of the offer.
            if(code>=400&&!json.has("accepted"))throw new ServerError(json.optString("error","HTTP "+code));
            return json;
        }finally{c.disconnect();active=null;}
    }

    /** Best effort: tells the projector to drop a half-finished transfer. */
    private void cancelRemote(){
        if(transferId==null)return;
        final String id=transferId;transferId=null;
        try{
            HttpURLConnection c=(HttpURLConnection)new URL(base+"/cancel?id="+URLEncoder.encode(id,"UTF-8")).openConnection();
            c.setConnectTimeout(3000);c.setReadTimeout(3000);c.setRequestMethod("POST");c.setDoOutput(true);c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();c.getResponseCode();c.disconnect();
        }catch(Exception ignored){}
    }

    private void status(String text,boolean error){main.post(()->callback.status(text,error));}
    private void progress(float f){main.post(()->callback.progress(f));}

    static final class ServerError extends IOException {
        ServerError(String message){super(message);}
    }
}
