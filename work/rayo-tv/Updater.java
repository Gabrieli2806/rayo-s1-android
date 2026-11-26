package local.rayo.launcher;

import android.content.Context;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * GabOS wireless updates, stage 1 (GabOS Settings, no special rights).
 *
 * The latest GitHub release of the GabOS repository carries gabos-ota.json, its
 * ECDSA P-256 signature (gabos-ota.json.sig, key in manifests/), a public
 * system image without factory files and a boot image without the factory
 * kernel (tools/make-gabos-ota.py). This class checks the signature, compares
 * versions, downloads both images into the app's private files while hashing
 * them, writes the plan that GabOS Rescue follows, and asks the root staging
 * service (gabos-update-stage) to continue. Nothing is installed by this app.
 */
final class Updater {
    static final String REPOSITORY = "Gabrieli2806/rayo-s1-android";
    static final String LATEST = "https://github.com/"+REPOSITORY+"/releases/latest/download/";
    /** manifests/gabos-ota-signing.pub.pem */
    private static final String PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETWy+IbG7rb7RSGL41+Lz30gkbsWHPA5Owsse4VhJouQm6qfzyvgj5j7Nb0EjfhuSWgLQdvl9QgaoatpedZrDJw==";

    interface Progress { void update(long done, long total); boolean cancelled(); }

    static final class Release {
        final JSONObject json; final String version; final long downloadBytes;
        Release(JSONObject json) throws Exception {
            this.json = json; version = json.getString("version");
            downloadBytes = json.getJSONObject("system").getLong("gz_bytes")+json.getJSONObject("boot").getLong("bytes");
        }
    }

    static String installedVersion() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return (String) props.getMethod("get", String.class, String.class).invoke(null, "ro.gabos.version", "");
        } catch (Exception e) { return ""; }
    }

    static String property(String name) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return (String) props.getMethod("get", String.class, String.class).invoke(null, name, "");
        } catch (Exception e) { return ""; }
    }

    /** True when version a is newer than b ("1.10" > "1.9"). */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }
    private static int parse(String s) { try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; } }

    /** Downloads and verifies the latest release manifest. */
    static Release check() throws Exception {
        byte[] body = fetch(LATEST+"gabos-ota.json", 4 << 20);
        byte[] signature = fetch(LATEST+"gabos-ota.json.sig", 4096);
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)));
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(body);
        if (!verifier.verify(signature)) throw new SecurityException("signature");
        JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
        if (json.getInt("format") != 1 || !REPOSITORY.equals(json.getString("repository"))) throw new IOException("format");
        return new Release(json);
    }

    static File folder(Context context) { return new File(context.getFilesDir(), "update"); }

    /** Downloads both images with SHA-256 checks, writes plan.txt, and starts the staging service. */
    static void download(Context context, Release release, Progress progress) throws Exception {
        JSONObject system = release.json.getJSONObject("system"), boot = release.json.getJSONObject("boot");
        File dir = folder(context);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("folder");
        long need = release.downloadBytes+(64L << 20);
        if (dir.getUsableSpace() < need) throw new IOException("space:"+(need >> 20));
        String base = "https://github.com/"+REPOSITORY+"/releases/download/"+release.json.getString("tag")+"/";
        long[] done = {0};
        get(base+system.getString("file"), new File(dir, "gabos-system.img.gz"), system.getString("gz_sha256"), done, release.downloadBytes, progress);
        get(base+boot.getString("file"), new File(dir, "gabos-boot.img"), boot.getString("public_sha256"), done, release.downloadBytes, progress);
        writePlan(release, new File(dir, "plan.txt"));
        // The staging service (root) moves the files to userdata and restarts into GabOS Rescue.
        Runtime.getRuntime().exec(new String[]{"setprop", "sys.gabos.update.stage", "1"}).waitFor();
    }

    private static void writePlan(Release release, File file) throws Exception {
        JSONObject system = release.json.getJSONObject("system"), boot = release.json.getJSONObject("boot");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            out.write("version "+release.version+"\n");
            out.write("system_gz_sha256 "+system.getString("gz_sha256")+"\n");
            out.write("system_sha256 "+system.getString("sha256")+"\n");
            out.write("system_bytes "+system.getLong("bytes")+"\n");
            out.write("boot_public_sha256 "+boot.getString("public_sha256")+"\n");
            out.write("boot_sha256 "+boot.getString("sha256")+"\n");
            out.write("boot_md5 "+boot.getString("md5")+"\n");
            out.write("page "+boot.getInt("page")+"\n");
            for (String part : new String[]{"kernel", "second"}) {
                JSONObject p = boot.getJSONObject(part);
                out.write(part+" "+p.getLong("offset")+" "+p.getLong("bytes")+" "+p.getString("sha256")+"\n");
            }
            out.write("data_bytes "+release.json.getJSONObject("data").getLong("bytes")+"\n");
            JSONArray blobs = system.getJSONArray("blobs");
            for (int i = 0; i < blobs.length(); i++) {
                JSONObject blob = blobs.getJSONObject(i);
                String path = blob.getString("path");
                if (!path.matches("[A-Za-z0-9._@+/-]+") || path.contains("..")) throw new IOException("path");
                out.write("blob "+blob.getString("sha256")+" "+blob.getLong("bytes")+" "+path+"\n");
                JSONArray runs = blob.getJSONArray("blocks");
                for (int r = 0; r < runs.length(); r++) {
                    JSONArray run = runs.getJSONArray(r);
                    out.write("run "+run.getLong(0)+" "+run.getLong(1)+"\n");
                }
            }
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(60000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "GabOS-Updater");
        int code = c.getResponseCode();
        if (code != 200) { c.disconnect(); throw new IOException("HTTP "+code); }
        return c;
    }

    private static byte[] fetch(String url, int limit) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) > 0) { out.write(buffer, 0, n); if (out.size() > limit) throw new IOException("too large"); }
            return out.toByteArray();
        } finally { c.disconnect(); }
    }

    private static void get(String url, File target, String sha256, long[] done, long total, Progress progress) throws Exception {
        File part = new File(target.getPath()+".part");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[64 << 10]; int n; long last = 0;
            while ((n = in.read(buffer)) > 0) {
                if (progress.cancelled()) throw new InterruptedException();
                out.write(buffer, 0, n); digest.update(buffer, 0, n); done[0] += n;
                long now = System.currentTimeMillis();
                if (now-last > 250) { last = now; progress.update(done[0], total); }
            }
        } finally { c.disconnect(); }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        if (!hex.toString().equals(sha256)) { part.delete(); throw new SecurityException("checksum"); }
        if (!part.renameTo(target)) throw new IOException("rename");
        progress.update(done[0], total);
    }
}
