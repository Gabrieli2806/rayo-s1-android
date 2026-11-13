package local.gabos.share.sender;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds GabOS projectors on the local network (DNS-SD "_gabosshare._tcp").
 * Resolves one service at a time, because older Android versions reject
 * overlapping resolves. All callbacks are delivered on the main thread.
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

    Discovery(Context context,Listener listener){
        this.context=context.getApplicationContext();this.listener=listener;
        this.nsd=(NsdManager)this.context.getSystemService(Context.NSD_SERVICE);
    }

    void start(){
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
}
