package local.gabos.share.sender;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Finds GabOS projectors on the local network. Two methods run together:
 * DNS-SD ("_gabosshare._tcp"), resolving one service at a time because older
 * Android versions reject overlapping resolves, and a direct probe of every
 * address on the phone's own local subnets (GET /info on port 8765). The probe
 * is what finds the projector when it is connected to this phone's hotspot,
 * where Android does not run mDNS, and on routers that drop multicast.
 * All callbacks are delivered on the main thread.
 */
final class Discovery {
    static final String TYPE="_gabosshare._tcp";
    private static final String TAG="GabOSShareSender";

    static final class Projector {
        final String name, host; final int port;
        Projector(String name,String host,int port){this.name=name;this.host=host;this.port=port;}
        String key(){return host+":"+port;}
    }

    interface Listener{void onChanged(List<Projector> projectors);}

    private final Context context;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final NsdManager nsd;
    private final List<Projector> found=new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> queue=new ArrayDeque<>();
    private boolean resolving;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock multicast;
    private static final int PORT=8765, RESCAN_MS=7000;
    /** Incremented on every stop; results of an older scan are ignored. */
    private int generation;
    private boolean scanning, active;
    private final Runnable rescan=this::scan;

    Discovery(Context context,Listener listener){
        this.context=context.getApplicationContext();this.listener=listener;
        this.nsd=(NsdManager)this.context.getSystemService(Context.NSD_SERVICE);
    }

    void start(){
        if(!active){active=true;scan();}
        if(discovery!=null||nsd==null)return;
        try{
            WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            if(wifi!=null){multicast=wifi.createMulticastLock("GabOSShare");multicast.setReferenceCounted(false);multicast.acquire();}
        }catch(RuntimeException e){Log.w(TAG,"No multicast lock",e);}
        discovery=new NsdManager.DiscoveryListener(){
            @Override public void onStartDiscoveryFailed(String type,int error){Log.w(TAG,"discovery failed "+error);main.post(()->discovery=null);}
            @Override public void onStopDiscoveryFailed(String type,int error){}
            @Override public void onDiscoveryStarted(String type){}
            @Override public void onDiscoveryStopped(String type){}
            @Override public void onServiceFound(NsdServiceInfo info){main.post(()->{queue.add(info);next();});}
            @Override public void onServiceLost(NsdServiceInfo info){main.post(()->{
                for(int i=found.size()-1;i>=0;i--)if(found.get(i).name.equals(info.getServiceName()))found.remove(i);
                publish();
            });}
        };
        try{nsd.discoverServices(TYPE,NsdManager.PROTOCOL_DNS_SD,discovery);}
        catch(RuntimeException e){Log.w(TAG,"discovery unavailable",e);discovery=null;}
    }

    void stop(){
        active=false;generation++;main.removeCallbacks(rescan);
        if(discovery!=null)try{nsd.stopServiceDiscovery(discovery);}catch(RuntimeException ignored){}
        discovery=null;queue.clear();
        if(multicast!=null&&multicast.isHeld())multicast.release();
    }

    /** Clears the list and searches again. */
    void restart(){stop();found.clear();publish();start();}

    private void next(){
        if(resolving||queue.isEmpty())return;
        resolving=true;
        NsdServiceInfo info=queue.poll();
        try{
            nsd.resolveService(info,new NsdManager.ResolveListener(){
                @Override public void onResolveFailed(NsdServiceInfo s,int error){main.post(()->{resolving=false;next();});}
                @Override public void onServiceResolved(NsdServiceInfo s){main.post(()->{
                    resolving=false;
                    String host=hostOf(s);
                    if(host!=null){
                        Projector p=new Projector(s.getServiceName(),host,s.getPort());
                        for(int i=found.size()-1;i>=0;i--)if(found.get(i).key().equals(p.key())||found.get(i).name.equals(p.name))found.remove(i);
                        found.add(p);publish();
                    }
                    next();
                });}
            });
        }catch(RuntimeException e){resolving=false;Log.w(TAG,"resolve failed",e);}
    }

    /**
     * Prefers an IPv4 address: Android 14+ lists every address through
     * getHostAddresses() (looked up by reflection, this app compiles against
     * API 26), while older versions report a single host.
     */
    static String hostOf(NsdServiceInfo s){
        try{
            Object list=NsdServiceInfo.class.getMethod("getHostAddresses").invoke(s);
            if(list instanceof List)for(Object a:(List<?>)list)if(a instanceof Inet4Address)return ((Inet4Address)a).getHostAddress();
        }catch(Exception ignored){}
        InetAddress host=s.getHost();
        if(host==null)return null;
        return host.getHostAddress();
    }

    /** "host:port" for a URL, with brackets and an escaped zone for IPv6 literals. */
    static String authority(String host,int port){
        if(host.indexOf(':')>=0)return "["+host.replace("%","%25")+"]:"+port;
        return host+":"+port;
    }

    private void publish(){listener.onChanged(new ArrayList<>(found));}

    // ---- Direct subnet probe ---------------------------------------------------------------

    /**
     * Local IPv4 hosts worth probing, as address -> own address to bind to.
     * Covers Wi-Fi, the hotspot interface (wlan1, swlan0, ap0, softap0...), USB
     * and Ethernet. Mobile data and VPN interfaces are skipped, and a subnet
     * larger than /24 is reduced to the /24 around the phone's own address.
     */
    private static Map<String,InetAddress> candidates(){
        Map<String,InetAddress> out=new LinkedHashMap<>();
        try{
            for(NetworkInterface nif:Collections.list(NetworkInterface.getNetworkInterfaces())){
                String n=nif.getName()==null?"":nif.getName();
                if(!nif.isUp()||nif.isLoopback()||nif.isPointToPoint())continue;
                if(n.startsWith("rmnet")||n.startsWith("ccmni")||n.startsWith("tun")||n.startsWith("ppp")||n.startsWith("pdp")
                        ||n.startsWith("dummy")||n.startsWith("v4-")||n.startsWith("clat")||n.startsWith("ipsec")||n.startsWith("epdg"))continue;
                for(InterfaceAddress ia:nif.getInterfaceAddresses()){
                    InetAddress own=ia.getAddress();
                    if(!(own instanceof Inet4Address)||!own.isSiteLocalAddress())continue;
                    byte[] b=own.getAddress();
                    int self=b[3]&0xff, prefix=Math.max(24,Math.min(30,ia.getNetworkPrefixLength()));
                    int size=1<<(32-prefix), first=self&~(size-1);
                    for(int h=first+1;h<first+size-1;h++){
                        if(h==self)continue;
                        String ip=(b[0]&0xff)+"."+(b[1]&0xff)+"."+(b[2]&0xff)+"."+h;
                        if(!out.containsKey(ip))out.put(ip,own);
                    }
                }
            }
        }catch(Exception e){Log.w(TAG,"interfaces unavailable",e);}
        return out;
    }

    /** Asks one address for /info; returns the projector's name, or null when it is not a GabOS receiver. */
    private static String probe(String ip,InetAddress own){
        try(Socket socket=new Socket()){
            try{socket.bind(new InetSocketAddress(own,0));}catch(Exception ignored){}
            socket.connect(new InetSocketAddress(ip,PORT),450);
            socket.setSoTimeout(2500);
            socket.getOutputStream().write(("GET /info HTTP/1.1\r\nHost: "+ip+":"+PORT+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream all=new ByteArrayOutputStream();
            InputStream in=socket.getInputStream();byte[] buffer=new byte[2048];int n;
            while(all.size()<16384&&(n=in.read(buffer))>0)all.write(buffer,0,n);
            String text=new String(all.toByteArray(),StandardCharsets.UTF_8);
            int body=text.indexOf("\r\n\r\n");
            if(!text.startsWith("HTTP/1.1 200")||body<0)return null;
            JSONObject info=new JSONObject(text.substring(body+4));
            if(!info.has("api"))return null;
            String name=info.optString("name","").trim();
            return name.isEmpty()?"GabOS":name;
        }catch(Exception e){return null;}
    }

    private void scan(){
        main.removeCallbacks(rescan);
        if(!active||scanning)return;
        scanning=true;
        final int run=generation;
        new Thread(()->{
            final Map<String,InetAddress> targets=candidates();
            final Map<String,String> hits=Collections.synchronizedMap(new LinkedHashMap<String,String>());
            if(!targets.isEmpty()){
                ExecutorService pool=Executors.newFixedThreadPool(48);
                for(final Map.Entry<String,InetAddress> t:targets.entrySet())pool.execute(()->{
                    String name=probe(t.getKey(),t.getValue());
                    if(name!=null)hits.put(t.getKey(),name);
                });
                pool.shutdown();
                try{pool.awaitTermination(40,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
                pool.shutdownNow();
            }
            main.post(()->{
                scanning=false;
                if(!active)return;
                if(run!=generation){scan();return;}
                boolean changed=false;
                for(Map.Entry<String,String> hit:hits.entrySet()){
                    Projector p=new Projector(hit.getValue(),hit.getKey(),PORT);
                    boolean known=false;
                    for(Projector f:found)if(f.key().equals(p.key()))known=true;
                    if(known)continue;
                    // The same projector may already be listed by DNS-SD under an IPv6 address.
                    for(int i=found.size()-1;i>=0;i--)if(found.get(i).name.equals(p.name))found.remove(i);
                    found.add(p);changed=true;
                }
                if(changed)publish();
                // Keep looking while nothing is listed: the projector may still be joining the network.
                if(found.isEmpty())main.postDelayed(rescan,RESCAN_MS);
            });
        },"GabOSShare-scan").start();
    }
}
