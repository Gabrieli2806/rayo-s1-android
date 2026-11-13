package local.gabos.share;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the receiver after boot and after the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        try{ShareService.start(context);}catch(RuntimeException e){android.util.Log.w(ShareService.TAG,"Cannot start the receiver",e);}
    }
}
