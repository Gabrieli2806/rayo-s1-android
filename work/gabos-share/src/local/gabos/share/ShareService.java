package local.gabos.share;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * GabOS Share receiver: a small HTTP server on {@link #PORT}, advertised over
 * mDNS/DNS-SD as {@link #SERVICE_TYPE}. See PROTOCOL.md for the wire format.
 * Only peers on private networks are served, one transfer runs at a time and
 * every offer needs approval on the projector.
 */
public class ShareService extends Service {
    static final String TAG="GabOSShare";
    static final int PORT=8765;
    static final String SERVICE_TYPE="_gabosshare._tcp";
    static final long MAX_FILE=2L*1024*1024*1024;
    static final int MAX_ITEMS=200;
    static final long RESERVE=400L*1024*1024;
    static final long DECISION_MS=60_000;
    static final long IDLE_MS=10*60_000;
    private static final int MAX_CONNECTIONS=6;
    private static final int MAX_JSON=256*1024;

    private static final Object LOCK=new Object();
    private static Transfer current;
    private static volatile boolean running;
    private static volatile String lastResult;

    private final SecureRandom random=new SecureRandom();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile ServerSocket server;
    private volatile boolean destroyed;
    private Thread acceptThread;
    private ThreadPoolExecutor pool;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private String advertisedName;

    static void start(Context context){
        context.startService(new Intent(context,ShareService.class));
    }

    @Override public IBinder onBind(Intent intent){return null;}

    @Override public void onCreate(){
        super.onCreate();
        deleteTree(shareRoot(this));
        pool=new ThreadPoolExecutor(1,MAX_CONNECTIONS,30,TimeUnit.SECONDS,new SynchronousQueue<>());
        startForegroundQuietly();
        acceptThread=new Thread(this::serve,"GabOSShare-accept");acceptThread.start();
        main.postDelayed(janitor,60_000);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        // Re-advertise when the device name changed since the last start.
        if(running&&advertisedName!=null&&!advertisedName.equals(Net.deviceName(this))){unregister();register();}
        return START_STICKY;
    }

    @Override public void onDestroy(){
        destroyed=true;running=false;
        if(acceptThread!=null)acceptThread.interrupt();
        main.removeCallbacks(janitor);
        unregister();
        try{if(server!=null)server.close();}catch(IOException ignored){}
        if(pool!=null)pool.shutdownNow();
        synchronized(LOCK){if(current!=null){current.state=Transfer.CANCELLED;current.decision.countDown();current.deleteFiles();current=null;}}
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void startForegroundQuietly(){
        // A low-priority ongoing notification keeps the receiver alive on a 1 GB device.
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),0);
        Notification n=new Notification.Builder(this)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_ready))
            .setContentIntent(open).setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build();
        try{startForeground(1,n);}catch(RuntimeException e){Log.w(TAG,"Foreground start refused: "+e);}
    }

    // ---- Status for the app screen ---------------------------------------------------------

    static boolean isRunning(){return running;}

    /** A one-line status in the user's language. */
    static String status(Context c){
        if(!running)return c.getString(R.string.status_stopped);
        synchronized(LOCK){
            if(current==null)return lastResult!=null?lastResult:c.getString(R.string.status_ready);
            switch(current.state){
                case Transfer.AWAITING:return c.getString(R.string.status_waiting,current.sender);
                case Transfer.ACCEPTED:return c.getString(R.string.status_receiving,current.receivedCount(),current.items.length,
                    percent(current.bytesReceived,current.totalBytes()));
                case Transfer.COMPLETE:return c.getString(R.string.status_showing,current.sender);
                default:return c.getString(R.string.status_ready);
            }
        }
    }

    private static int percent(long part,long total){return total<=0?100:(int)Math.min(100,part*100/total);}

    static Transfer transfer(String id){
        synchronized(LOCK){return current!=null&&current.id.equals(id)?current:null;}
    }

    /** A completed Show transfer whose viewer is not open (for example when the start was blocked). */
    static Transfer pendingShow(){
        synchronized(LOCK){return current!=null&&current.show&&current.state==Transfer.COMPLETE&&!current.viewerOpen?current:null;}
    }

    /** Called by the approval screen. */
    static void decide(String id,boolean accept){
        synchronized(LOCK){
            if(current==null||!current.id.equals(id)||current.state!=Transfer.AWAITING)return;
            current.state=accept?Transfer.ACCEPTED:Transfer.DECLINED;
            current.lastActivity=System.currentTimeMillis();
            current.decision.countDown();
        }
    }

    /** Called when the viewer closes: Show mode keeps nothing. */
    static void finishShow(String id){
        synchronized(LOCK){
            if(current==null||!current.id.equals(id))return;
            current.deleteFiles();current=null;
        }
    }

    // ---- Server ----------------------------------------------------------------------------

    private void serve(){
        ServerSocket server=null;
        for(int attempt=0;;attempt++){
            if(destroyed)return;
            try{
                server=new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new java.net.InetSocketAddress(PORT),16);
                break;
            }catch(IOException e){
                try{if(server!=null)server.close();}catch(IOException ignored){}
                if(attempt>=20){Log.e(TAG,"Cannot listen on port "+PORT,e);return;}
                try{Thread.sleep(3000);}catch(InterruptedException stop){return;}
            }
        }
        this.server=server;
        // onDestroy may have run while binding; never keep the port open for a dead service.
        if(destroyed){try{server.close();}catch(IOException ignored){}return;}
        running=true;
        main.post(this::register);
        while(running&&!destroyed){
            final Socket socket;
            try{socket=server.accept();}
            catch(IOException e){if(!running||server.isClosed())break;Log.w(TAG,"accept failed",e);continue;}
            try{pool.execute(()->handle(socket));}
            catch(RuntimeException busy){
                try{socket.setSoTimeout(2000);reply(socket.getOutputStream(),503,json("error","busy"));}catch(IOException ignored){}
                close(socket);
            }
        }
    }

    private void register(){
        if(!running||registration!=null)return;
        try{
            nsd=(NsdManager)getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo info=new NsdServiceInfo();
            advertisedName=Net.deviceName(this);
            info.setServiceName(advertisedName);
            info.setServiceType(SERVICE_TYPE);
            info.setPort(PORT);
            registration=new NsdManager.RegistrationListener(){
                @Override public void onRegistrationFailed(NsdServiceInfo s,int error){Log.w(TAG,"NSD registration failed: "+error);registration=null;}
                @Override public void onUnregistrationFailed(NsdServiceInfo s,int error){Log.w(TAG,"NSD unregistration failed: "+error);}
                @Override public void onServiceRegistered(NsdServiceInfo s){Log.i(TAG,"Advertised as "+s.getServiceName());}
                @Override public void onServiceUnregistered(NsdServiceInfo s){}
            };
            nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);
        }catch(RuntimeException e){Log.w(TAG,"NSD unavailable",e);registration=null;}
    }

    private void unregister(){
        if(nsd!=null&&registration!=null)try{nsd.unregisterService(registration);}catch(RuntimeException ignored){}
        registration=null;
    }

    private void handle(Socket socket){
        try{
            socket.setSoTimeout(30_000);
            OutputStream out=socket.getOutputStream();
            InetAddress peer=socket.getInetAddress();
            if(!Net.isPrivate(peer)){reply(out,403,json("error","forbidden"));return;}
            InputStream in=new BufferedInputStream(socket.getInputStream(),64*1024);
            String requestLine=readLine(in);
            if(requestLine==null)return;
            String[] parts=requestLine.split(" ");
            if(parts.length<3){reply(out,400,json("error","bad request"));return;}
            Map<String,String> headers=new HashMap<>();
            for(int i=0;i<100;i++){
                String line=readLine(in);
                if(line==null||line.isEmpty())break;
                int colon=line.indexOf(':');
                if(colon>0)headers.put(line.substring(0,colon).trim().toLowerCase(Locale.ROOT),line.substring(colon+1).trim());
            }
            String method=parts[0],target=parts[1],path=target;
            Map<String,String> query=new HashMap<>();
            int q=target.indexOf('?');
            if(q>=0){path=target.substring(0,q);parseQuery(target.substring(q+1),query);}
            if(!originAllowed(headers)){reply(out,403,json("error","forbidden"));return;}
            if(headers.containsKey("transfer-encoding")){reply(out,411,json("error","content-length required"));return;}
            // DNS rebinding and cross-site requests from a web page on the LAN: the Host must be an
            // address or .local name, and a browser Origin must be this server itself.
            String host=headers.get("host"),origin=headers.get("origin");
            if(!Net.isLocalHost(host)||origin!=null&&(host==null||!origin.equalsIgnoreCase("http://"+host))){
                reply(out,403,json("error","forbidden"));return;
            }
            if("GET".equals(method)&&("/".equals(path)||"/index.html".equals(path))){
                byte[] page=WebPage.html(headers.get("accept-language"),Net.deviceName(this)).getBytes(StandardCharsets.UTF_8);
                reply(out,200,"text/html; charset=utf-8",page);
            }else if("GET".equals(method)&&"/info".equals(path)){
                JSONObject info=new JSONObject();
                info.put("name",Net.deviceName(this));info.put("version",version());info.put("api",1);
                synchronized(LOCK){info.put("busy",current!=null);}
                reply(out,200,info);
            }else if("POST".equals(method)&&"/offer".equals(path)){
                offer(in,out,headers,socket);
            }else if("POST".equals(method)&&"/upload".equals(path)){
                upload(in,out,headers,query);
            }else if("POST".equals(method)&&"/cancel".equals(path)){
                cancel(query.get("id"));reply(out,200,json("ok",true));
            }else{
                reply(out,404,json("error","not found"));
            }
        }catch(SocketException ignored){
            // The peer went away.
        }catch(IOException|JSONException|RuntimeException e){
            Log.w(TAG,"request failed",e);
        }finally{close(socket);}
    }

    private void offer(InputStream in,OutputStream out,Map<String,String> headers,Socket socket) throws IOException, JSONException {
        long length=contentLength(headers);
        if(length<0||length>MAX_JSON){reply(out,413,json("error","offer too large"));return;}
        JSONObject body;
        try{body=new JSONObject(new String(readFully(in,(int)length),StandardCharsets.UTF_8));}
        catch(JSONException|StackOverflowError e){
            // org.json parses recursively: deeply nested input must not kill the process.
            reply(out,400,json("error","invalid JSON"));return;
        }
        String sender=clean(body.optString("sender","").trim(),40);
        if(sender.isEmpty())sender=getString(R.string.unknown_sender);
        String mode=body.optString("mode","");
        if(!"show".equals(mode)&&!"save".equals(mode)){reply(out,400,json("error","mode must be show or save"));return;}
        JSONArray list=body.optJSONArray("items");
        if(list==null||list.length()==0||list.length()>MAX_ITEMS){reply(out,400,json("error","1 to "+MAX_ITEMS+" items required"));return;}
        Transfer.Item[] items=new Transfer.Item[list.length()];
        for(int i=0;i<items.length;i++){
            JSONObject o=list.optJSONObject(i);
            if(o==null){reply(out,400,json("error","bad item "+i));return;}
            String mime=o.optString("mime","").toLowerCase(Locale.ROOT).trim();
            long size=o.optLong("size",-1);
            if(!mime.matches("(image|video)/[a-z0-9][a-z0-9.+_-]{0,90}")){reply(out,415,json("error","only photos and videos"));return;}
            if(size<0||size>MAX_FILE){reply(out,413,json("error","file too large"));return;}
            items[i]=new Transfer.Item(safeName(o.optString("name",""),i,mime),mime,size);
        }
        Transfer t;
        synchronized(LOCK){
            if(current!=null){reply(out,409,new JSONObject().put("accepted",false).put("reason","busy"));return;}
            File dir=new File(shareRoot(this),newId());
            t=new Transfer(dir.getName(),sender,"show".equals(mode),items,dir);
            // Show keeps everything in the cache until the viewer closes; Save keeps one file at a time.
            long needed=t.show?t.totalBytes():t.totalBytes()+t.largest();
            if(getCacheDir().getUsableSpace()-RESERVE<needed){reply(out,507,new JSONObject().put("accepted",false).put("reason","no space"));return;}
            current=t;
        }
        lastResult=null;
        main.post(()->{
            Intent ask=new Intent(this,ApproveActivity.class).putExtra(ApproveActivity.EXTRA_ID,t.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION);
            try{startActivity(ask);}catch(RuntimeException e){Log.w(TAG,"Cannot show the approval prompt",e);}
        });
        boolean decided=false;
        long deadline=t.created+DECISION_MS;
        try{
            while(!decided){
                long left=deadline-System.currentTimeMillis();
                if(left<=0)break;
                decided=t.decision.await(Math.min(1000,left),TimeUnit.MILLISECONDS);
                // The sender gave up (Cancel on the phone, tab closed): withdraw the prompt now.
                if(!decided&&peerGone(socket,in)){cancel(t.id);decided=true;}
            }
        }catch(InterruptedException e){decided=false;}
        String reason;
        synchronized(LOCK){
            if(!decided&&t.state==Transfer.AWAITING){t.state=Transfer.TIMED_OUT;t.decision.countDown();}
            reason=t.state==Transfer.ACCEPTED?null:t.state==Transfer.DECLINED?"declined":t.state==Transfer.TIMED_OUT?"timeout":"cancelled";
            if(reason!=null&&current==t)current=null;
            if(t.state==Transfer.ACCEPTED){
                if(!t.dir.isDirectory()&&!t.dir.mkdirs()){current=null;reason="storage";}
                t.lastActivity=System.currentTimeMillis();
            }
        }
        JSONObject result=new JSONObject().put("id",t.id).put("accepted",reason==null);
        if(reason!=null)result.put("reason",reason);
        try{reply(out,200,result);}
        catch(IOException gone){
            // The sender left while the prompt was up: do not keep the projector busy for it.
            if(reason==null)cancel(t.id);
            throw gone;
        }
    }

    private void upload(InputStream in,OutputStream out,Map<String,String> headers,Map<String,String> query) throws IOException, JSONException {
        String id=query.get("id");
        int index;
        try{index=Integer.parseInt(query.get("index"));}catch(RuntimeException e){reply(out,400,json("error","index required"));return;}
        long length=contentLength(headers);
        Transfer t;Transfer.Item item;
        synchronized(LOCK){
            t=current;
            if(t==null||!t.id.equals(id)||t.state!=Transfer.ACCEPTED){reply(out,404,json("error","unknown or closed transfer"));return;}
            if(index<0||index>=t.items.length){reply(out,400,json("error","bad index"));return;}
            item=t.items[index];
            if(item.done){reply(out,409,json("error","already received"));return;}
            if(length!=item.size){reply(out,400,json("error","Content-Length must equal the offered size"));return;}
            if(t.uploading){reply(out,409,json("error","one upload at a time"));return;}
            t.uploading=true;
        }
        File file=new File(t.dir,index+"-"+item.name);
        boolean ok=false;
        try{
            long remaining=length,before=t.bytesReceived;
            byte[] buffer=new byte[64*1024];
            try(OutputStream f=new FileOutputStream(file)){
                while(remaining>0){
                    int n=in.read(buffer,0,(int)Math.min(buffer.length,remaining));
                    if(n<0)throw new IOException("stream ended early");
                    f.write(buffer,0,n);remaining-=n;
                    t.bytesReceived+=n;t.lastActivity=System.currentTimeMillis();
                    if(t.state!=Transfer.ACCEPTED)throw new IOException("transfer cancelled");
                }
            }finally{if(remaining>0)t.bytesReceived=before;}
            if(!t.show){
                try{Saver.save(this,file,item.name,item.mime);}
                catch(IOException|RuntimeException e){
                    Log.w(TAG,"Saving "+item.name+" failed",e);
                    reply(out,500,json("error","could not save on the projector"));return;
                }finally{
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
            ok=true;
        }finally{
            if(!ok)//noinspection ResultOfMethodCallIgnored
                file.delete();
            synchronized(LOCK){t.uploading=false;if(t.state==Transfer.CANCELLED)t.deleteFiles();}
        }
        boolean complete;
        synchronized(LOCK){
            item.file=t.show?file:null;item.done=true;
            complete=t.allReceived()&&t.state==Transfer.ACCEPTED;
            if(complete)t.state=Transfer.COMPLETE;
        }
        if(complete)completed(t);
        reply(out,200,new JSONObject().put("ok",true).put("received",t.receivedCount()).put("total",t.items.length).put("complete",complete));
    }

    private void completed(final Transfer t){
        if(t.show){
            main.post(()->{
                Intent view=new Intent(this,ViewerActivity.class).putExtra(ViewerActivity.EXTRA_ID,t.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try{startActivity(view);}catch(RuntimeException e){Log.w(TAG,"Cannot open the viewer",e);}
            });
        }else{
            final String message=getResources().getQuantityString(R.plurals.saved_confirmation,t.items.length,t.items.length,t.sender);
            lastResult=message;
            synchronized(LOCK){t.deleteFiles();if(current==t)current=null;}
            main.post(()->Toast.makeText(this,message,Toast.LENGTH_LONG).show());
        }
    }

    private void cancel(String id){
        synchronized(LOCK){
            if(current==null||!current.id.equals(id))return;
            Transfer t=current;
            if(t.state==Transfer.COMPLETE)return; // Already on screen; the viewer owns it now.
            t.state=Transfer.CANCELLED;t.decision.countDown();
            if(!t.uploading)t.deleteFiles();
            current=null;
            lastResult=getString(R.string.status_cancelled,t.sender);
        }
    }

    /** Drops transfers that stalled, so a vanished phone cannot block the projector. */
    private final Runnable janitor=new Runnable(){public void run(){
        synchronized(LOCK){
            Transfer t=current;
            long idle=System.currentTimeMillis()-(t==null?0:t.lastActivity);
            if(t!=null&&!t.uploading&&(t.state==Transfer.ACCEPTED&&idle>IDLE_MS||t.state==Transfer.COMPLETE&&!t.viewerOpen&&idle>3*IDLE_MS)){
                Log.i(TAG,"Dropping stalled transfer "+t.id);
                t.state=Transfer.CANCELLED;t.deleteFiles();current=null;
            }
        }
        main.postDelayed(this,60_000);
    }};

    // ---- HTTP helpers ----------------------------------------------------------------------

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line=new ByteArrayOutputStream();
        int c;
        while((c=in.read())>=0){
            if(c=='\n')break;
            if(c!='\r')line.write(c);
            if(line.size()>8192)throw new IOException("header line too long");
        }
        if(c<0&&line.size()==0)return null;
        return new String(line.toByteArray(),StandardCharsets.ISO_8859_1);
    }

    private static byte[] readFully(InputStream in,int length) throws IOException {
        byte[] data=new byte[length];int off=0;
        while(off<length){int n=in.read(data,off,length-off);if(n<0)throw new IOException("body ended early");off+=n;}
        return data;
    }

    /**
     * Against DNS rebinding and cross-site requests (see PROTOCOL.md): the Host
     * header must be an IP literal, localhost or a .local name, and an Origin
     * header, when present, must be exactly http://Host.
     */
    static boolean originAllowed(Map<String,String> headers){
        String host=headers.get("host");
        if(host==null||host.isEmpty())return false;
        String name;
        if(host.startsWith("[")){
            int end=host.indexOf(']');
            if(end<0)return false;
            String rest=host.substring(end+1);
            if(!rest.isEmpty()&&!rest.matches(":[0-9]{1,5}"))return false;
            name=null; // An IPv6 literal.
        }else{
            int colon=host.lastIndexOf(':');
            if(colon>=0&&!host.substring(colon).matches(":[0-9]{1,5}"))return false;
            name=(colon>=0?host.substring(0,colon):host).toLowerCase(Locale.ROOT);
        }
        if(name!=null&&!name.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")&&!"localhost".equals(name)&&!name.endsWith(".local"))return false;
        String origin=headers.get("origin");
        return origin==null||origin.equalsIgnoreCase("http://"+host);
    }

    /**
     * True when the client closed the connection while its offer waits for an
     * answer, so a cancelled phone or a closed browser tab withdraws the prompt.
     */
    private static boolean peerGone(Socket socket,InputStream in){
        try{
            int previous=socket.getSoTimeout();
            socket.setSoTimeout(1);
            try{return in.read()<0;}
            catch(java.net.SocketTimeoutException alive){return false;}
            finally{socket.setSoTimeout(previous);}
        }catch(IOException e){return true;}
    }

    private static long contentLength(Map<String,String> headers){
        try{return Long.parseLong(headers.get("content-length"));}catch(RuntimeException e){return -1;}
    }

    private static void parseQuery(String q,Map<String,String> into){
        for(String pair:q.split("&")){
            int eq=pair.indexOf('=');if(eq<=0)continue;
            try{into.put(URLDecoder.decode(pair.substring(0,eq),"UTF-8"),URLDecoder.decode(pair.substring(eq+1),"UTF-8"));}
            catch(Exception ignored){}
        }
    }

    private static JSONObject json(String key,Object value){
        try{return new JSONObject().put(key,value);}catch(JSONException e){return new JSONObject();}
    }

    private static void reply(OutputStream out,int code,JSONObject body) throws IOException {
        reply(out,code,"application/json; charset=utf-8",body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(OutputStream out,int code,String type,byte[] body) throws IOException {
        String head="HTTP/1.1 "+code+" "+reason(code)+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length
            +"\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));out.write(body);out.flush();
    }

    private static String reason(int code){
        switch(code){
            case 200:return "OK";case 400:return "Bad Request";case 403:return "Forbidden";case 404:return "Not Found";
            case 409:return "Conflict";case 411:return "Length Required";case 413:return "Payload Too Large";
            case 415:return "Unsupported Media Type";case 500:return "Internal Server Error";case 503:return "Service Unavailable";case 507:return "Insufficient Storage";
            default:return "Error";
        }
    }

    private static void close(Socket s){try{s.close();}catch(IOException ignored){}}

    // ---- Misc ------------------------------------------------------------------------------

    private String newId(){
        byte[] b=new byte[12];random.nextBytes(b);
        StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x));return s.toString();
    }

    private String version(){
        try{PackageInfo p=getPackageManager().getPackageInfo(getPackageName(),0);return p.versionName;}catch(Exception e){return "1.0";}
    }

    static File shareRoot(Context c){return new File(c.getCacheDir(),"share");}

    private static void deleteTree(File f){
        File[] children=f.listFiles();
        if(children!=null)for(File c:children)deleteTree(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String clean(String s,int max){
        StringBuilder b=new StringBuilder();
        for(int i=0;i<s.length()&&b.length()<max;i++){char c=s.charAt(i);if(printable(c))b.append(c);}
        // Do not end on half of a surrogate pair.
        if(b.length()>0&&Character.isHighSurrogate(b.charAt(b.length()-1)))b.setLength(b.length()-1);
        return b.toString();
    }

    /** No control characters and no invisible format characters such as right-to-left overrides. */
    private static boolean printable(char c){
        return c>=0x20&&c!=0x7f&&!(c>=0x80&&c<0xa0)&&Character.getType(c)!=Character.FORMAT;
    }

    /** Ext4 limits a file name to 255 bytes; keep room for the index prefix and " (n)" suffixes. */
    private static final int MAX_NAME_BYTES=180;

    private static String fitBytes(String s,int maxBytes){
        if(s.getBytes(StandardCharsets.UTF_8).length<=maxBytes)return s;
        int end=s.length();
        while(end>0&&s.substring(0,end).getBytes(StandardCharsets.UTF_8).length>maxBytes)end--;
        if(end>0&&Character.isHighSurrogate(s.charAt(end-1)))end--;
        return s.substring(0,end);
    }

    /** A plain file name: no paths or control characters, a sane length and an extension that fits the type. */
    static String safeName(String raw,int index,String mime){
        String name=raw;
        int slash=Math.max(name.lastIndexOf('/'),name.lastIndexOf('\\'));
        if(slash>=0)name=name.substring(slash+1);
        StringBuilder b=new StringBuilder();
        for(int i=0;i<name.length();i++){
            char c=name.charAt(i);
            if(!printable(c)||":*?\"<>|".indexOf(c)>=0)continue;
            b.append(c);
        }
        name=b.toString().trim();
        while(name.startsWith("."))name=name.substring(1);
        if(name.length()>100||name.getBytes(StandardCharsets.UTF_8).length>MAX_NAME_BYTES){
            int dot=name.lastIndexOf('.');
            String ext=dot>0&&name.length()-dot<=10?name.substring(dot):"";
            String base=dot>0&&!ext.isEmpty()?name.substring(0,dot):name;
            base=fitBytes(base.length()>100-ext.length()?base.substring(0,100-ext.length()):base,
                MAX_NAME_BYTES-ext.getBytes(StandardCharsets.UTF_8).length);
            if(!base.isEmpty()&&Character.isHighSurrogate(base.charAt(base.length()-1)))base=base.substring(0,base.length()-1);
            name=base.trim()+ext;
            if(name.startsWith("."))name="";
        }
        if(name.isEmpty())name=(mime.startsWith("video/")?"video-":"photo-")+(index+1);
        if(name.indexOf('.')<0){
            String ext=android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if(ext!=null)name+="."+ext;
        }
        return name;
    }
}
