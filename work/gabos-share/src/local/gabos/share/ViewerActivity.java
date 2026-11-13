package local.gabos.share;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.ExifInterface;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;

/**
 * Full-screen viewer for a Show transfer. Photos are decoded to at most
 * 1280x720 and fitted to the screen; videos play with the platform decoders.
 * Left/right (D-pad, on-screen arrows or wheel) move between items; OK pauses
 * a video. Closing the viewer deletes the received files.
 */
public class ViewerActivity extends Activity {
    static final String EXTRA_ID="id";
    private static final int MAX_W=1280, MAX_H=720;

    private final Handler handler=new Handler();
    private String id;
    private Transfer transfer;
    private int index;
    private int generation;
    private FrameLayout stage;
    private ImageView image;
    private VideoView video;
    private Bitmap bitmap;
    private View chrome, previous, next;
    private TextView caption;
    private Ui ui;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        id=getIntent().getStringExtra(EXTRA_ID);
        transfer=ShareService.transfer(id);
        if(transfer==null||transfer.state!=Transfer.COMPLETE){finish();return;}
        transfer.viewerOpen=true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON|WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        ui=new Ui(this,ShareTheme.load(this));
        ui.immersive();
        build();
        show(0);
    }

    private void build(){
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xff000000);
        stage=new FrameLayout(this);root.addView(stage,new FrameLayout.LayoutParams(-1,-1));
        image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(image,new FrameLayout.LayoutParams(-1,-1));

        FrameLayout overlay=new FrameLayout(this);chrome=overlay;
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(ui.dp(32),ui.dp(20),ui.dp(20),ui.dp(20));
        top.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x99000000,0x00000000}));
        caption=ui.text("",18,0xfff1f3f4);caption.setSingleLine(true);caption.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(caption,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(round(Glyph.CLOSE,v->finish()),new LinearLayout.LayoutParams(ui.dp(56),ui.dp(56)));
        overlay.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        previous=round(Glyph.LEFT,v->move(-1));next=round(Glyph.RIGHT,v->move(1));
        FrameLayout.LayoutParams pl=new FrameLayout.LayoutParams(ui.dp(68),ui.dp(68),Gravity.LEFT|Gravity.CENTER_VERTICAL);pl.leftMargin=ui.dp(20);
        FrameLayout.LayoutParams pr=new FrameLayout.LayoutParams(ui.dp(68),ui.dp(68),Gravity.RIGHT|Gravity.CENTER_VERTICAL);pr.rightMargin=ui.dp(20);
        overlay.addView(previous,pl);overlay.addView(next,pr);
        root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        root.setOnClickListener(v->{if(video!=null)togglePause();else wake();});
        root.setOnGenericMotionListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_HOVER_MOVE){wake();return false;}
            if(e.getAction()==MotionEvent.ACTION_SCROLL){move(e.getAxisValue(MotionEvent.AXIS_VSCROLL)>0?-1:1);return true;}
            return false;
        });
        setContentView(root);
    }

    private enum Glyph{LEFT,RIGHT,CLOSE}

    /** A translucent round button drawn in code; not focusable so the D-pad keeps navigating items. */
    private View round(final Glyph glyph,View.OnClickListener action){
        View b=new View(this){
            final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c){
                float w=getWidth(),h=getHeight(),s=w*.16f;
                p.setStyle(Paint.Style.FILL);p.setColor(isHovered()?0xeee8eaed:0x80262d38);c.drawCircle(w/2,h/2,w/2,p);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(ui.dp(4));p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
                p.setColor(isHovered()?0xff0d0f13:0xfff1f3f4);
                Path path=new Path();
                if(glyph==Glyph.CLOSE){path.moveTo(w/2-s,h/2-s);path.lineTo(w/2+s,h/2+s);path.moveTo(w/2+s,h/2-s);path.lineTo(w/2-s,h/2+s);}
                else{float d=glyph==Glyph.LEFT?1:-1;path.moveTo(w/2+d*s*.6f,h/2-s*1.3f);path.lineTo(w/2-d*s*.6f,h/2);path.lineTo(w/2+d*s*.6f,h/2+s*1.3f);}
                c.drawPath(path,p);
            }
        };
        b.setFocusable(false);b.setClickable(true);
        b.setContentDescription(getString(glyph==Glyph.CLOSE?R.string.close:glyph==Glyph.LEFT?R.string.previous:R.string.next));
        b.setOnHoverListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_HOVER_ENTER){v.setHovered(true);v.invalidate();wake();}
            else if(e.getAction()==MotionEvent.ACTION_HOVER_EXIT){v.setHovered(false);v.invalidate();}
            return false;
        });
        b.setOnClickListener(v->{Ui.pulse(v);action.onClick(v);wake();});
        return b;
    }

    private void move(int delta){
        int target=index+delta;
        if(target<0||target>=transfer.items.length){wake();return;}
        show(target);
    }

    private void show(int i){
        index=i;generation++;
        Transfer.Item item=transfer.items[i];
        releaseVideo();
        image.setImageDrawable(null);
        if(bitmap!=null){bitmap.recycle();bitmap=null;}
        caption.setText((transfer.items.length>1?(i+1)+" / "+transfer.items.length+"  ·  ":"")+item.name);
        previous.setVisibility(i>0?View.VISIBLE:View.INVISIBLE);
        next.setVisibility(i<transfer.items.length-1?View.VISIBLE:View.INVISIBLE);
        if(item.file==null||!item.file.exists()){caption.setText(getString(R.string.viewer_missing,item.name));wake();return;}
        if(item.video())playVideo(item.file);else loadPhoto(item.file,generation);
        wake();
    }

    private void loadPhoto(final File file,final int ticket){
        image.setVisibility(View.VISIBLE);
        new Thread(()->{
            final Bitmap decoded=decode(file.getAbsolutePath());
            runOnUiThread(()->{
                if(isFinishing()||ticket!=generation){if(decoded!=null)decoded.recycle();return;}
                bitmap=decoded;
                if(decoded==null)caption.setText(getString(R.string.viewer_unreadable,file.getName()));
                image.setImageBitmap(decoded);
                image.setAlpha(0f);image.animate().alpha(1f).setDuration(180).start();
            });
        },"GabOSShare-decode").start();
    }

    /** Decodes with a power-of-two sample, then scales and rotates so the result fits 1280x720. */
    static Bitmap decode(String path){
        try{
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(path,bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
            int rotation=0;
            try{
                int o=new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
                rotation=o==ExifInterface.ORIENTATION_ROTATE_90?90:o==ExifInterface.ORIENTATION_ROTATE_180?180:o==ExifInterface.ORIENTATION_ROTATE_270?270:0;
            }catch(Exception ignored){}
            boolean turned=rotation==90||rotation==270;
            int w=turned?bounds.outHeight:bounds.outWidth,h=turned?bounds.outWidth:bounds.outHeight;
            float fit=Math.min(1f,Math.min(MAX_W/(float)w,MAX_H/(float)h));
            int sample=1;
            while(sample*2<=1/fit)sample*=2;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;
            options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            Bitmap raw=BitmapFactory.decodeFile(path,options);
            if(raw==null)return null;
            int rw=turned?raw.getHeight():raw.getWidth(),rh=turned?raw.getWidth():raw.getHeight();
            float scale=Math.min(1f,Math.min(MAX_W/(float)rw,MAX_H/(float)rh));
            if(scale>=1f&&rotation==0)return raw;
            Matrix m=new Matrix();m.postRotate(rotation);m.postScale(scale,scale);
            Bitmap out=Bitmap.createBitmap(raw,0,0,raw.getWidth(),raw.getHeight(),m,true);
            if(out!=raw)raw.recycle();
            return out;
        }catch(OutOfMemoryError|RuntimeException e){
            // A crafted or extreme image (for example 1x100000 scaled to a zero width) must not crash the app.
            return null;
        }
    }

    private void playVideo(File file){
        image.setVisibility(View.GONE);
        video=new VideoView(this);
        video.setFocusable(false);
        stage.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        video.setOnPreparedListener(mp->{video.start();wake();});
        video.setOnCompletionListener(mp->{if(index<transfer.items.length-1)move(1);else wake();});
        video.setOnErrorListener((mp,what,extra)->{caption.setText(getString(R.string.viewer_unplayable,file.getName()));wake();return true;});
        video.setVideoPath(file.getAbsolutePath());
    }

    private void releaseVideo(){
        if(video==null)return;
        try{video.stopPlayback();}catch(RuntimeException ignored){}
        stage.removeView(video);video=null;
    }

    private void togglePause(){
        if(video==null)return;
        if(video.isPlaying())video.pause();else video.start();
        wake();
    }

    private final Runnable hide=()->chrome.animate().alpha(0f).setDuration(300).start();

    /** Shows the caption and arrows, then fades them after a few seconds. */
    private void wake(){
        if(chrome==null)return;
        chrome.animate().cancel();chrome.setAlpha(1f);
        handler.removeCallbacks(hide);
        handler.postDelayed(hide,video!=null&&video.isPlaying()?2500:4000);
    }

    @Override public boolean onKeyDown(int code,KeyEvent e){
        switch(code){
            case KeyEvent.KEYCODE_DPAD_LEFT:case KeyEvent.KEYCODE_MEDIA_PREVIOUS:case KeyEvent.KEYCODE_PAGE_UP:move(-1);return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:case KeyEvent.KEYCODE_MEDIA_NEXT:case KeyEvent.KEYCODE_PAGE_DOWN:move(1);return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:case KeyEvent.KEYCODE_SPACE:
                if(video!=null)togglePause();else wake();return true;
            case KeyEvent.KEYCODE_DPAD_UP:case KeyEvent.KEYCODE_DPAD_DOWN:wake();return true;
            default:return super.onKeyDown(code,e);
        }
    }

    @Override protected void onPause(){
        super.onPause();
        if(video!=null&&video.isPlaying())video.pause();
    }

    @Override protected void onStop(){
        super.onStop();
        // Leaving the viewer (Home, another app) ends the show: Show mode keeps nothing.
        if(!isFinishing()&&!isChangingConfigurations())finish();
    }

    @Override protected void onDestroy(){
        handler.removeCallbacksAndMessages(null);
        releaseVideo();
        if(bitmap!=null){image.setImageDrawable(null);bitmap.recycle();bitmap=null;}
        if(transfer!=null&&isFinishing()){transfer.viewerOpen=false;ShareService.finishShow(id);}
        super.onDestroy();
    }

    @Override public void finish(){super.finish();overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);}
}
