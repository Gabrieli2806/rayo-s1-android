package local.rayo.validation;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

public final class MainActivity extends Activity {
    private static final String TAG = "RayoValidation";
    private final Handler handler = new Handler();
    private TextView status;
    private VideoView video;
    private AudioTrack tone;

    private void report(String message) {
        Log.i(TAG, message);
        if (status != null) status.append(message + "\n");
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 12, 20, 12);
        root.setBackgroundColor(Color.rgb(24, 24, 24));
        LinearLayout controls = new LinearLayout(this);
        root.addView(controls);
        addButton(controls, "Test tone", v -> playTone());
        addButton(controls, "Test video", v -> playVideo());
        addButton(controls, "Bluetooth", v -> {
            try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
            catch (android.content.ActivityNotFoundException missing) {
                report("Bluetooth settings unavailable; opening main settings.");
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        addButton(controls, "Close", v -> finish());
        video = new VideoView(this);
        root.addView(video, new LinearLayout.LayoutParams(-1, 0, 2));
        ScrollView scroll = new ScrollView(this);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        scroll.addView(status);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        report("Hardware diagnostics: enumeration is not playback verification.");
        enumerateCodecs();
        String test = getIntent().getStringExtra("test");
        if ("tone".equals(test)) handler.postDelayed(this::playTone, 800);
        if ("video".equals(test)) handler.postDelayed(this::playVideo, 800);
    }

    private void addButton(LinearLayout row, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void enumerateCodecs() {
        try {
            MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            int count = 0;
            for (MediaCodecInfo info : codecs) {
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (type.equals("video/avc") || type.equals("audio/mp4a-latm")) {
                        report("DECODER " + info.getName() + " " + type);
                        count++;
                    }
                }
            }
            report("Relevant decoder entries: " + count);
        } catch (Throwable error) { report("CODEC_ENUM_ERROR " + error); }
    }

    @SuppressWarnings("deprecation") private void playTone() {
        releaseTone();
        try {
            int sampleRate = 44100;
            short[] samples = new short[sampleRate * 2];
            for (int i = 0; i < samples.length; i++) {
                double envelope = Math.min(1.0, Math.min(i / 800.0, (samples.length - i) / 800.0));
                samples[i] = (short)(8000.0 * envelope * Math.sin(i * 2.0 * Math.PI * 440.0 / sampleRate));
            }
            tone = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, samples.length * 2, AudioTrack.MODE_STATIC);
            int written = tone.write(samples, 0, samples.length);
            tone.setVolume(0.20f);
            tone.play();
            report("TONE_STARTED samples=" + written + " state=" + tone.getState());
            handler.postDelayed(() -> { report("TONE_FINISHED"); releaseTone(); }, 2400);
        } catch (Throwable error) { report("TONE_ERROR " + error); releaseTone(); }
    }

    private void releaseTone() {
        if (tone != null) {
            try { tone.stop(); } catch (IllegalStateException ignored) { }
            tone.release();
            tone = null;
        }
    }

    private void playVideo() {
        try {
            video.setOnPreparedListener(player -> {
                player.setVolume(0.20f, 0.20f);
                report("VIDEO_PREPARED " + player.getVideoWidth() + "x" + player.getVideoHeight()
                        + " duration=" + player.getDuration());
                video.start();
            });
            video.setOnCompletionListener(player -> report("VIDEO_COMPLETE"));
            video.setOnErrorListener((player, what, extra) -> {
                report("VIDEO_ERROR what=" + what + " extra=" + extra); return true;
            });
            video.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/raw/av_probe"));
            report("VIDEO_REQUESTED");
        } catch (Throwable error) { report("VIDEO_EXCEPTION " + error); }
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseTone();
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }
}
