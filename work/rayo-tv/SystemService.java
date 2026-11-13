package local.rayo.launcher;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * Runs in the Home process, which the system keeps alive.
 * - Reapplies projector settings after boot.
 * - Turns the light source off while the screen is off (inactivity timeout).
 * - Speaker anti-pop: the factory audio path restarts the codec after every
 *   silence, which the amplifier turns into an audible pop. A silent stream keeps
 *   the output running while the screen is on, so new sounds mix in without a
 *   restart. It costs one idle mixer track and is stopped with the screen.
 */
public class SystemService extends Service {
    private static final String TAG="GabOSSystem";
    private volatile boolean silenceWanted;
    private Thread silenceThread;
    private boolean registered;

    private final BroadcastReceiver screen=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            boolean on=Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            if(Projector.lightWithScreen(context))Projector.light(on);
            if(on)Projector.restore(context);
            updateSilence(on);
        }
    };

    static void start(Context c){c.startService(new Intent(c,SystemService.class));}
    static void refresh(Context c){c.startService(new Intent(c,SystemService.class).setAction("refresh"));}

    @Override public void onCreate(){
        super.onCreate();
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen,filter);registered=true;
        Projector.restore(this);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        boolean interactive=((PowerManager)getSystemService(POWER_SERVICE)).isInteractive();
        updateSilence(interactive);
        return START_STICKY;
    }

    private synchronized void updateSilence(boolean screenOn){
        boolean want=screenOn&&Projector.antiPop(this);
        if(want==silenceWanted&&(!want||silenceThread!=null&&silenceThread.isAlive()))return;
        silenceWanted=want;
        if(!want){if(silenceThread!=null)silenceThread.interrupt();silenceThread=null;return;}
        silenceThread=new Thread(this::playSilence,"gabos-anti-pop");silenceThread.setPriority(Thread.MIN_PRIORITY);silenceThread.start();
    }

    private void playSilence(){
        // Recreate the stream if the audio server restarts (its track becomes invalid).
        while(silenceWanted&&!Thread.currentThread().isInterrupted()){
            streamSilence();
            try{Thread.sleep(2000);}catch(InterruptedException e){return;}
        }
    }

    private void streamSilence(){
        int rate=AudioTrack.getNativeOutputSampleRate(android.media.AudioManager.STREAM_MUSIC);
        if(rate<=0)rate=44100;
        int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track=null;
        try{
            track=new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(Math.max(min,rate))
                .setTransferMode(AudioTrack.MODE_STREAM).build();
            byte[] zeros=new byte[Math.max(min/2,4096)];
            track.play();
            while(silenceWanted&&!Thread.currentThread().isInterrupted()){
                if(track.write(zeros,0,zeros.length)<0)break;
            }
        }catch(Exception e){Log.w(TAG,"Anti-pop stream stopped",e);}
        finally{if(track!=null){try{track.stop();}catch(Exception ignored){}track.release();}}
    }

    @Override public void onDestroy(){silenceWanted=false;if(silenceThread!=null)silenceThread.interrupt();if(registered)unregisterReceiver(screen);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}

    /** Starts the service after boot even before Home is drawn. */
    public static class Boot extends BroadcastReceiver{
        @Override public void onReceive(Context context,Intent intent){start(context);}
    }
}
