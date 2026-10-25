package io.github.mrpitonych.nogmsvoice;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.SystemClock;
import android.util.Log;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.BooleanSupplier;

/**
 * The Vosk model of this build's language (the ru or en flavour puts it into the assets): unpacked after
 * every install, loaded once per process and kept.
 */
final class ModelHolder {
    private static final String TAG = VoskRecognitionService.TAG;
    private static final String ASSET_DIR = "model";
    // A failed load is retried once, at the next request, from a fresh copy (storage may have been freed).
    // After that every request fails at once with ERROR_SERVER until the process restarts.
    private static final int MAX_ATTEMPTS = 2;
    private static final long ABORT_POLL_MS = 200;

    private static final Object LOCK = new Object();
    // Guarded by LOCK. While a load runs, loading is set and model and failure are null; a finished load
    // leaves exactly one of them set.
    private static Model model;
    private static boolean loading;
    private static Exception failure;
    private static int attempts;        // loads started in this process

    private ModelHolder() {}

    /** Starts loading in the background unless the model is loaded, loading, or MAX_ATTEMPTS are used up. */
    static void ensureLoading(Context context) {
        synchronized (LOCK) {
            if (model != null || loading || attempts >= MAX_ATTEMPTS) return;
            attempts++;
            loading = true;
            failure = null;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> load(app), "NoGmsVoice-model").start();
    }

    /** The model when it is loaded, else null. */
    static Model peek() {
        synchronized (LOCK) {
            return model;
        }
    }

    /** The exception the last load failed with; null while a load runs or once loaded. Non-null: don't wait. */
    static Exception failure() {
        synchronized (LOCK) {
            return failure;
        }
    }

    /**
     * Waits while a load runs, at most timeoutMs, and returns the model, or null if it is not loaded by then.
     * abort is polled every ABORT_POLL_MS, as nothing notifies LOCK when it changes.
     */
    static Model await(long timeoutMs, BooleanSupplier abort) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        synchronized (LOCK) {
            while (loading && !abort.getAsBoolean()) {
                long left = deadline - SystemClock.uptimeMillis();
                if (left <= 0) break;
                try {
                    LOCK.wait(Math.min(left, ABORT_POLL_MS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return model;
        }
    }

    private static void load(Context app) {
        Model loaded = null;
        Exception error = null;
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);    // the first Vosk call on every path; loads libvosk.so
            File dir = unpack(app);
            long started = SystemClock.uptimeMillis();
            loaded = new Model(dir.getAbsolutePath());
            Log.i(TAG, "model loaded in " + (SystemClock.uptimeMillis() - started) + " ms");
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "model load failed", e);
            deleteTree(new File(app.getFilesDir(), ASSET_DIR));   // the next attempt starts from a fresh copy
            error = e;
        }
        synchronized (LOCK) {
            model = loaded;
            failure = error;
            loading = false;
            LOCK.notifyAll();
        }
    }

    /**
     * Vosk reads the model from plain files, so the assets are copied to the app's private storage. The copy
     * is stamped with the APK's lastUpdateTime, which every install changes, so a new model is never shadowed
     * by an old copy. The stamp is written last: a copy cut short (process killed, storage full) is redone.
     */
    private static File unpack(Context app) throws IOException {
        File dir = new File(app.getFilesDir(), ASSET_DIR);
        File stamp = new File(dir, ".stamp");
        String expected = installStamp(app);
        String found = stamp.isFile() ? new String(Files.readAllBytes(stamp.toPath()), StandardCharsets.UTF_8) : null;
        if (expected.equals(found)) return dir;
        long started = SystemClock.uptimeMillis();
        deleteTree(dir);
        if (dir.exists()) throw new IOException("cannot delete the old copy in " + dir);
        copyAssets(app.getAssets(), ASSET_DIR, dir);
        Files.write(stamp.toPath(), expected.getBytes(StandardCharsets.UTF_8));
        Log.i(TAG, "model unpacked in " + (SystemClock.uptimeMillis() - started) + " ms");
        return dir;
    }

    private static String installStamp(Context app) throws IOException {
        try {
            return Long.toString(app.getPackageManager().getPackageInfo(app.getPackageName(), 0).lastUpdateTime);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("own package not found", e);
        }
    }

    private static void copyAssets(AssetManager assets, String source, File target) throws IOException {
        String[] children = assets.list(source);
        if (children == null || children.length == 0) {   // AssetManager has no isDirectory(): a file lists no children
            try (InputStream in = assets.open(source)) {
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("cannot create " + target);
        for (String child : children) copyAssets(assets, source + "/" + child, new File(target, child));
    }

    /** Best effort: unpack() checks that nothing is left. */
    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
