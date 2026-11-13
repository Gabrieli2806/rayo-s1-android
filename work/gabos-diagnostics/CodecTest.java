package local.gabos.diagnostics;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Self-contained video codec check: encodes a short moving test pattern with the
 * platform software encoder, then decodes it with every decoder the system
 * offers for that format (hardware and software), rendering to a real surface.
 * Reports frames decoded, speed and errors. Used by host validation; no network.
 */
final class CodecTest {
    private static final String TAG = "GabOSCodecTest";
    private static final long TIMEOUT_US = 10000;

    static final class Case {
        final String mime; final int width, height, frames; final int muxer; final String ext;
        Case(String mime, int width, int height, int frames, int muxer, String ext) {
            this.mime = mime; this.width = width; this.height = height; this.frames = frames; this.muxer = muxer; this.ext = ext;
        }
    }

    static final Case[] CASES = {
        new Case(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720, 90, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4"),
        new Case(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080, 45, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4"),
        new Case(MediaFormat.MIMETYPE_VIDEO_HEVC, 1280, 720, 30, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4"),
        new Case(MediaFormat.MIMETYPE_VIDEO_VP8, 1280, 720, 60, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "webm"),
        new Case(MediaFormat.MIMETYPE_VIDEO_VP9, 1280, 720, 30, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "webm"),
        new Case(MediaFormat.MIMETYPE_VIDEO_MPEG4, 352, 288, 60, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4"),
        new Case(MediaFormat.MIMETYPE_VIDEO_H263, 352, 288, 60, MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP, "3gp"),
    };

    private static List<String> codecs(String mime, boolean encoder) {
        List<String> hardware = new ArrayList<>(), software = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder() != encoder) continue;
            String name = info.getName();
            boolean soft = name.startsWith("c2.android.") || name.startsWith("OMX.google.");
            for (String type : info.getSupportedTypes()) if (type.equalsIgnoreCase(mime) && !hardware.contains(name) && !software.contains(name)) {
                (soft ? software : hardware).add(name);
            }
        }
        hardware.addAll(software);
        return hardware;
    }

    /** Encodes the moving pattern; returns null when no software encoder exists. */
    static File encode(Case c, File dir) throws Exception {
        String encoderName = null;
        for (String name : codecs(c.mime, true)) if (name.startsWith("c2.android.") || name.startsWith("OMX.google.")) { encoderName = name; break; }
        if (encoderName == null) return null;
        File out = new File(dir, "test-" + c.mime.replace('/', '-') + "-" + c.height + "." + c.ext);
        MediaFormat format = MediaFormat.createVideoFormat(c.mime, c.width, c.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, c.width * c.height * 3);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec encoder = MediaCodec.createByCodecName(encoderName);
        MediaMuxer muxer = new MediaMuxer(out.getPath(), c.muxer);
        int track = -1; boolean started = false;
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int queued = 0; boolean inputDone = false, outputDone = false;
            long deadline = SystemClock.elapsedRealtime() + 240000;
            while (!outputDone && SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    int index = encoder.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        if (queued == c.frames) {
                            encoder.queueInputBuffer(index, 0, 0, queued * 33333L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            Image image = encoder.getInputImage(index);
                            fill(image, queued, c.width, c.height);
                            encoder.queueInputBuffer(index, 0, c.width * c.height * 3 / 2, queued * 33333L, 0);
                            queued++;
                        }
                    }
                }
                int out_index = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (out_index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.getOutputFormat()); muxer.start(); started = true;
                } else if (out_index >= 0) {
                    ByteBuffer data = encoder.getOutputBuffer(out_index);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && started)
                        muxer.writeSampleData(track, data, info);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                    encoder.releaseOutputBuffer(out_index, false);
                }
            }
        } finally {
            encoder.release();
            if (started) muxer.stop();
            muxer.release();
        }
        return out;
    }

    private static void fill(Image image, int frame, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer y = planes[0].getBuffer();
        int rowStride = planes[0].getRowStride(), pixelStride = planes[0].getPixelStride();
        int bar = (frame * 8) % width;
        for (int row = 0; row < height; row++) for (int col = 0; col < width; col++) {
            int value = (col / 64 + row / 64) % 2 == 0 ? 60 : 180;
            if (Math.abs(col - bar) < 24) value = 235;
            y.put(row * rowStride + col * pixelStride, (byte) value);
        }
        for (int p = 1; p < 3; p++) {
            ByteBuffer plane = planes[p].getBuffer();
            int rs = planes[p].getRowStride(), ps = planes[p].getPixelStride();
            for (int row = 0; row < height / 2; row++) for (int col = 0; col < width / 2; col++)
                plane.put(row * rs + col * ps, (byte) (p == 1 ? (col * 255 / (width / 2)) : (row * 255 / (height / 2))));
        }
    }

    /** Decodes the file with one decoder to the surface; frames out, elapsed time and error. */
    static JSONObject decode(File file, String mime, String decoderName, Surface surface) throws Exception {
        JSONObject result = new JSONObject();
        result.put("decoder", decoderName);
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        int in = 0, out = 0; long start = SystemClock.elapsedRealtime();
        try {
            extractor.setDataSource(file.getPath());
            int trackIndex = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++)
                if (mime.equalsIgnoreCase(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME))) trackIndex = i;
            if (trackIndex < 0) throw new IllegalStateException("track not found");
            extractor.selectTrack(trackIndex);
            codec = MediaCodec.createByCodecName(decoderName);
            codec.configure(extractor.getTrackFormat(trackIndex), surface, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            long deadline = SystemClock.elapsedRealtime() + 60000;
            while (!outputDone && SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        int size = extractor.readSampleData(codec.getInputBuffer(index), 0);
                        if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
                        else { codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0); extractor.advance(); in++; }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (index >= 0) {
                    if (info.size > 0 || (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) out++;
                    codec.releaseOutputBuffer(index, true);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            if (!outputDone) result.put("error", "timeout");
        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) { } codec.release(); }
            extractor.release();
        }
        long elapsed = Math.max(1, SystemClock.elapsedRealtime() - start);
        result.put("frames_in", in); result.put("frames_out", out); result.put("ms", elapsed);
        result.put("fps", Math.round(out * 10000.0 / elapsed) / 10.0);
        return result;
    }

    /** Runs every case and returns one JSON array. */
    static JSONArray run(File dir, Surface surface) {
        JSONArray all = new JSONArray();
        for (Case c : CASES) {
            JSONObject entry = new JSONObject();
            try {
                entry.put("mime", c.mime); entry.put("size", c.width + "x" + c.height);
                long t = SystemClock.elapsedRealtime();
                File file = encode(c, dir);
                if (file == null) { entry.put("error", "no software encoder for the test clip"); all.put(entry); continue; }
                entry.put("encode_ms", SystemClock.elapsedRealtime() - t);
                JSONArray results = new JSONArray();
                for (String decoder : codecs(c.mime, false)) results.put(decode(file, c.mime, decoder, surface));
                entry.put("decoders", results);
                file.delete();
            } catch (Exception e) {
                try { entry.put("error", e.getClass().getSimpleName() + ": " + e.getMessage()); } catch (Exception ignored) { }
            }
            Log.i(TAG, entry.toString());
            all.put(entry);
        }
        return all;
    }
}
