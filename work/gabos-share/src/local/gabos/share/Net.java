package local.gabos.share;

import android.content.Context;
import android.provider.Settings;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Address helpers: the projector's own LAN address and the private-network check for callers. */
final class Net {
    private Net(){}

    /** Only loopback, link-local and private (RFC 1918 / unique local IPv6) peers may talk to the server. */
    static boolean isPrivate(InetAddress a){
        if(a==null)return false;
        if(a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress())return true;
        // fc00::/7 unique local IPv6. Everything else, including 100.64.0.0/10, is refused.
        return a instanceof Inet6Address&&(a.getAddress()[0]&0xfe)==0xfc;
    }

    /**
     * True when an HTTP Host header names this server by address, "localhost" or an mDNS
     * ".local" name. A public host name means DNS rebinding from a web page and is refused.
     * A missing Host (HTTP/1.0 tools) is allowed; browsers always send one.
     */
    static boolean isLocalHost(String host){
        if(host==null)return true;
        String h=host.trim().toLowerCase(java.util.Locale.ROOT);
        if(h.startsWith("["))return h.indexOf(']')>1; // IPv6 literal
        int colon=h.lastIndexOf(':');
        if(colon>=0)h=h.substring(0,colon);
        if(h.endsWith("."))h=h.substring(0,h.length()-1);
        return h.equals("localhost")||h.endsWith(".local")||h.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** The first private IPv4 address, preferring Wi-Fi then Ethernet; null when offline. */
    static String localAddress(){
        String fallback=null;
        try{
            for(NetworkInterface nif:Collections.list(NetworkInterface.getNetworkInterfaces())){
                if(!nif.isUp()||nif.isLoopback())continue;
                for(InetAddress a:Collections.list(nif.getInetAddresses())){
                    if(!(a instanceof Inet4Address)||!a.isSiteLocalAddress())continue;
                    String name=nif.getName();
                    if(name.startsWith("wlan"))return a.getHostAddress();
                    if(fallback==null||name.startsWith("eth"))fallback=a.getHostAddress();
                }
            }
        }catch(Exception ignored){}
        return fallback;
    }

    static String deviceName(Context context){
        String name=null;
        try{name=Settings.Global.getString(context.getContentResolver(),"device_name");}catch(RuntimeException ignored){}
        if(name==null||name.trim().isEmpty())name=context.getString(R.string.default_device_name);
        return name.trim();
    }
}
