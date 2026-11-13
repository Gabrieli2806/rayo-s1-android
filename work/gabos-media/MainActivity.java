package local.gabos.media;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.InputStream;
import local.gabos.ui.GabosUi;

/** Local media through the native document picker and the platform codec stack. */
public class MainActivity extends Activity {
    private GabosUi ui;
    private LinearLayout body;
    private VideoView video;
    private MediaPlayer audio;
    private Bitmap picture;
    private boolean browsing=true;
    private String selectedType="video/*";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); ui=new GabosUi(this); ui.onThemeChange(()->{if(browsing)menu(null);}); menu(null);
        if(Intent.ACTION_VIEW.equals(getIntent().getAction()) && getIntent().getData()!=null)
            play(getIntent().getData(),getIntent().getType());
    }
    private void menu(String notice) {
        release(); browsing=true;
        body=ui.page(getString(R.string.title),getString(R.string.subtitle));
        LinearLayout row=ui.row(body,26);
        View first=pick(row,getString(R.string.video),"video/*",0xff2e5f8a);
        pick(row,getString(R.string.music),"audio/*",0xff5b3f86);
        pick(row,getString(R.string.photos),"image/*",0xff2f6f73);
        LinearLayout card=ui.card(body,34);
        card.addView(ui.text("GabOS",12,GabosUi.DIM));
        TextView line=ui.text(notice!=null?notice:getString(R.string.hint),18,notice!=null?GabosUi.WARN:GabosUi.INK);
        line.setPadding(0,ui.dp(12),0,0);card.addView(line);
        first.post(first::requestFocus);
    }
    private View pick(LinearLayout row,String label,final String type,int base) {
        return ui.tintedTile(row,label,base,v->{
            selectedType=type;
            try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),1);ui.fade(); }
            catch(ActivityNotFoundException e) { menu(getString(R.string.no_files)); }
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==1 && result==RESULT_OK && data!=null && data.getData()!=null) play(data.getData(),selectedType);
    }
    private String name(Uri uri) {
        try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(cursor!=null && cursor.moveToFirst() && cursor.getString(0)!=null) return cursor.getString(0);
        } catch(Exception ignored) { }
        String last=uri.getLastPathSegment(); return last==null?getString(R.string.file):last;
    }
    private FrameLayout stage() {
        ui.immersive(); FrameLayout stage=new FrameLayout(this); stage.setBackgroundColor(Color.BLACK); setContentView(stage); return stage;
    }
    private void play(final Uri uri,String type) {
        release(); browsing=false;
        if(type==null) type=getContentResolver().getType(uri);
        try {
            if(type!=null && type.startsWith("image/")) {
                BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true;
                try(InputStream input=getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(input,null,options); }
                options.inSampleSize=1;
                while(options.outWidth/options.inSampleSize>1280 || options.outHeight/options.inSampleSize>720) options.inSampleSize*=2;
                options.inJustDecodeBounds=false;
                try(InputStream input=getContentResolver().openInputStream(uri)) { picture=BitmapFactory.decodeStream(input,null,options); }
                if(picture==null) throw new IllegalArgumentException(getString(R.string.unsupported));
                ImageView image=new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setImageBitmap(picture);
                stage().addView(image,new FrameLayout.LayoutParams(-1,-1));
            } else if(type!=null && type.startsWith("audio/")) {
                body=ui.page(getString(R.string.music),null);
                LinearLayout card=ui.card(body,26);
                card.addView(ui.text(getString(R.string.now_playing),12,GabosUi.DIM));
                TextView title=ui.text(name(uri),26,GabosUi.INK);title.setPadding(0,ui.dp(14),0,ui.dp(6));title.setSingleLine(true);card.addView(title);
                final TextView state=ui.text(getString(R.string.loading),14,GabosUi.DIM);card.addView(state);
                LinearLayout row=ui.row(body,26);
                View toggle=ui.primaryTile(row,getString(R.string.play_pause),v->{
                    try { if(audio!=null){ if(audio.isPlaying()){audio.pause();state.setText(R.string.paused);} else {audio.start();state.setText(R.string.playing);} } }
                    catch(IllegalStateException e){ }
                });
                ui.tile(row,getString(R.string.choose_another),v->menu(null));
                audio=new MediaPlayer(); audio.setDataSource(this,uri);
                audio.setOnPreparedListener(p->{p.start(); state.setText(R.string.playing); Log.i("GabOSMedia","Audio prepared and started");});
                audio.setOnCompletionListener(p->state.setText(R.string.finished));
                audio.setOnErrorListener((p,what,extra)->{state.setText(getString(R.string.playback_failed,what,extra));state.setTextColor(GabosUi.WARN); return true;});
                audio.prepareAsync(); toggle.post(toggle::requestFocus);
            } else if(type!=null && type.startsWith("video/")) {
                FrameLayout stage=stage();
                video=new VideoView(this); MediaController controls=new MediaController(this);
                controls.setAnchorView(video); video.setMediaController(controls);
                stage.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
                video.setOnPreparedListener(p->{video.start(); Log.i("GabOSMedia","Video prepared and started");});
                video.setOnCompletionListener(p->menu(null));
                video.setOnErrorListener((p,what,extra)->{menu(getString(R.string.playback_failed,what,extra)); return true;});
                video.setVideoURI(uri);
            } else menu(getString(R.string.unsupported));
        } catch(Exception e) { menu(getString(R.string.cannot_open,e.getClass().getSimpleName())); }
    }
    private void release() {
        if(video!=null){video.stopPlayback();video=null;}
        if(audio!=null){audio.release();audio=null;}
        if(picture!=null){picture.recycle();picture=null;}
    }
    @Override protected void onResume(){super.onResume();if(ui.refreshTheme()&&browsing)menu(null);}
    @Override protected void onPause(){if(video!=null)video.pause();if(audio!=null){try{if(audio.isPlaying())audio.pause();}catch(IllegalStateException e){}}super.onPause();}
    @Override public void onBackPressed(){if(browsing){super.onBackPressed();ui.fade();}else menu(null);}
    @Override protected void onDestroy(){release();super.onDestroy();}
}
