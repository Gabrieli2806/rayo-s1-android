package local.gabos.media;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.*;
import java.io.InputStream;

/** Local media through the native document picker and the platform codec stack. */
public class MainActivity extends Activity {
    private LinearLayout body;
    private VideoView video;
    private MediaPlayer audio;
    private Bitmap picture;
    private boolean browsing=true;
    private String selectedType="video/*";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); menu();
        if(Intent.ACTION_VIEW.equals(getIntent().getAction()) && getIntent().getData()!=null)
            play(getIntent().getData(),getIntent().getType());
    }
    private void menu() {
        release(); browsing=true; body=new LinearLayout(this); body.setOrientation(1);
        body.setPadding(40,24,40,24); body.setBackgroundColor(0xff10151d); setContentView(body);
        text("GabOS Media",30);
        text("Choose a local file using Android Files",18);
        pick("Video","video/*"); pick("Music","audio/*"); pick("Photos","image/*");
    }
    private void text(String value,int size) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(-1); body.addView(t); }
    private void pick(String label,final String type) {
        Button b=new Button(this); b.setText(label); body.addView(b);
        b.setOnClickListener(v->{
            selectedType=type;
            try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),1); }
            catch(ActivityNotFoundException e) { text("Android Files is not installed",18); }
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==1 && result==RESULT_OK && data!=null && data.getData()!=null) play(data.getData(),selectedType);
    }
    private void play(Uri uri,String type) {
        release(); browsing=false;
        if(type==null) type=getContentResolver().getType(uri);
        body.removeAllViews();
        try {
            if(type!=null && type.startsWith("image/")) {
                BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true;
                try(InputStream input=getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(input,null,options); }
                options.inSampleSize=1;
                while(options.outWidth/options.inSampleSize>1280 || options.outHeight/options.inSampleSize>720) options.inSampleSize*=2;
                options.inJustDecodeBounds=false;
                try(InputStream input=getContentResolver().openInputStream(uri)) { picture=BitmapFactory.decodeStream(input,null,options); }
                if(picture==null) throw new IllegalArgumentException("Unsupported image");
                ImageView image=new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setImageBitmap(picture); body.addView(image,new LinearLayout.LayoutParams(-1,0,1));
            } else if(type!=null && type.startsWith("audio/")) {
                text("Music",26); audio=new MediaPlayer(); audio.setDataSource(this,uri);
                audio.setOnPreparedListener(p->{p.start(); Log.i("GabOSMedia","Audio prepared and started");});
                audio.setOnErrorListener((p,what,extra)->{text("Playback failed ("+what+", "+extra+")",18); return true;});
                Button toggle=new Button(this); toggle.setText("Play / Pause"); body.addView(toggle);
                toggle.setOnClickListener(v->{try {if(audio!=null){if(audio.isPlaying())audio.pause();else audio.start();}}catch(IllegalStateException e){ }});
                audio.prepareAsync();
            } else if(type!=null && type.startsWith("video/")) {
                video=new VideoView(this); MediaController controls=new MediaController(this);
                controls.setAnchorView(video); video.setMediaController(controls);
                body.addView(video,new LinearLayout.LayoutParams(-1,0,1));
                video.setOnPreparedListener(p->{video.start(); Log.i("GabOSMedia","Video prepared and started");});
                video.setOnErrorListener((p,what,extra)->{text("Playback failed ("+what+", "+extra+")",18); return true;});
                video.setVideoURI(uri);
            } else text("Unsupported media type",18);
        } catch(Exception e) { text("Cannot open this file: "+e.getClass().getSimpleName(),18); }
        Button back=new Button(this); back.setText("Choose another file"); body.addView(back); back.setOnClickListener(v->menu());
    }
    private void release() {
        if(video!=null){video.stopPlayback();video=null;}
        if(audio!=null){audio.release();audio=null;}
        if(picture!=null){picture.recycle();picture=null;}
    }
    @Override protected void onPause(){if(video!=null)video.pause();if(audio!=null){try{if(audio.isPlaying())audio.pause();}catch(IllegalStateException e){}}super.onPause();}
    @Override public void onBackPressed(){if(browsing)super.onBackPressed();else menu();}
    @Override protected void onDestroy(){release();super.onDestroy();}
}
