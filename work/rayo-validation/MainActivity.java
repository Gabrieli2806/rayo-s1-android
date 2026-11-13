package local.rayo.validation;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
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
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
import local.gabos.ui.GabosUi;

/**
 * Hardware Check: a tone, a short AVC/AAC clip and the Bluetooth settings, in
 * the GabOS look. The "test" extra ("tone" or "video") and the RayoValidation
 * log lines are used by the host validation tools; keep their wording.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "RayoValidation";
    private final Handler handler = new Handler();
    private final StringBuilder log = new StringBuilder();
    private GabosUi ui;
    private TextView status;
    private VideoView video;
    private AudioTrack tone;

    /**
     * Writes the fixed English line for the host tools to the log and shows the
     * localized line on screen.
     */
    private void report(String message, String shown) {
        Log.i(TAG, message);
        log.append(shown).append('\n');
        if (status != null) status.setText(log.toString().trim());
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new GabosUi(this);
        ui.onThemeChange(this::render);
        render();
        report("Hardware diagnostics: enumeration is not playback verification.", getString(R.string.log_intro));
        enumerateCodecs();
        String test = getIntent().getStringExtra("test");
        if ("tone".equals(test)) handler.postDelayed(this::playTone, 800);
        if ("video".equals(test)) handler.postDelayed(this::playVideo, 800);
    }

    @Override protected void onResume() { super.onResume(); if (ui.refreshTheme()) render(); }

    /** Builds the page in the current GabOS theme. */
    private void render() {
        if (video != null) video.stopPlayback();
        LinearLayout page = ui.page(getString(R.string.app_name), getString(R.string.subtitle));
        LinearLayout controls = ui.row(page, 26);
        View first = ui.primaryTile(controls, getString(R.string.test_tone), v -> playTone());
        ui.tile(controls, getString(R.string.test_video), v -> playVideo());
        ui.tile(controls, getString(R.string.bluetooth), v -> {
            try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); ui.fade(); }
            catch (android.content.ActivityNotFoundException missing) {
                report("Bluetooth settings unavailable; opening main settings.", getString(R.string.log_bluetooth_missing));
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        ui.tile(controls, getString(R.string.close), v -> { finish(); ui.fade(); });

        LinearLayout media = ui.row(page, 26);
        media.setGravity(Gravity.TOP);
        LinearLayout videoColumn = new LinearLayout(this); videoColumn.setOrientation(LinearLayout.VERTICAL);
        TextView videoCaption = ui.text(getString(R.string.section_video), 14, GabosUi.DIM); videoCaption.setLetterSpacing(.08f);
        videoColumn.addView(videoCaption);
        FrameLayout screen = new FrameLayout(this); screen.setBackground(ui.surface(0xff000000, 8));
        video = new VideoView(this);
        screen.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ui.dp(400), ui.dp(225)); sp.setMargins(0, ui.dp(10), 0, 0);
        videoColumn.addView(screen, sp);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2); vp.setMargins(0, 0, ui.dp(22), 0);
        media.addView(videoColumn, vp);

        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(18)); card.setBackground(ui.cardBackground(8));
        TextView logCaption = ui.text(getString(R.string.section_log), 14, GabosUi.DIM); logCaption.setLetterSpacing(.08f);
        card.addView(logCaption);
        status = ui.text(log.toString().trim(), 15, GabosUi.INK);
        status.setTypeface(Typeface.MONOSPACE); status.setLineSpacing(ui.dp(4), 1); status.setPadding(0, ui.dp(12), 0, 0);
        card.addView(status);
        media.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
        first.post(first::requestFocus);
    }

    private void enumerateCodecs() {
        try {
            MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            int count = 0;
            for (MediaCodecInfo info : codecs) {
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (type.equals("video/avc") || type.equals("audio/mp4a-latm")) {
                        report("DECODER " + info.getName() + " " + type, getString(R.string.log_decoder, info.getName(), type));
                        count++;
                    }
                }
            }
            report("Relevant decoder entries: " + count, getString(R.string.log_decoder_count, count));
        } catch (Throwable error) { report("CODEC_ENUM_ERROR " + error, getString(R.string.log_decoder_error, error.getClass().getSimpleName())); }
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
            report("TONE_STARTED samples=" + written + " state=" + tone.getState(), getString(R.string.log_tone_started));
            handler.postDelayed(() -> { report("TONE_FINISHED", getString(R.string.log_tone_finished)); releaseTone(); }, 2400);
        } catch (Throwable error) { report("TONE_ERROR " + error, getString(R.string.log_tone_error, error.getClass().getSimpleName())); releaseTone(); }
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
                        + " duration=" + player.getDuration(),
                        getString(R.string.log_video_prepared, player.getVideoWidth(), player.getVideoHeight(), player.getDuration() / 1000.0));
                video.start();
            });
            video.setOnCompletionListener(player -> report("VIDEO_COMPLETE", getString(R.string.log_video_complete)));
            video.setOnErrorListener((player, what, extra) -> {
                report("VIDEO_ERROR what=" + what + " extra=" + extra, getString(R.string.log_video_error, what, extra)); return true;
            });
            video.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/raw/av_probe"));
            report("VIDEO_REQUESTED", getString(R.string.log_video_requested));
        } catch (Throwable error) { report("VIDEO_EXCEPTION " + error, getString(R.string.log_video_exception, error.getClass().getSimpleName())); }
    }

    @Override public void onBackPressed() { super.onBackPressed(); ui.fade(); }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseTone();
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }
}
