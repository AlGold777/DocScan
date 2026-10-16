package app.docscan.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Instrumentation-only provider for deterministic PDF source failure and grant-revocation tests. */
public final class PdfSourceFaultTestProvider extends ContentProvider {
    public static final String AUTHORITY = "app.docscan.scanner.test.pdfsource";
    public static final String MODE_COMPLETE = "COMPLETE";
    public static final String MODE_FAIL_AFTER_PREFIX = "FAIL_AFTER_PREFIX";
    public static final String MODE_BLOCK_THEN_COMPLETE = "BLOCK_THEN_COMPLETE";

    private static final String PREFS = "pdf_source_fault_provider";
    private static final String KEY_BYTES = "bytes";
    private static final String KEY_MIME_TYPE = "mime_type";
    private static final String KEY_MODE = "mode";
    private static final String KEY_SPLIT = "split";
    private static final String KEY_OPENS = "opens";
    private static final String KEY_BYTES_WRITTEN = "bytes_written";
    private static final String KEY_PREFIX_WRITTEN = "prefix_written";
    private static final String KEY_RELEASED = "released";
    private static final String KEY_OUTCOME = "outcome";
    private static final String KEY_OK = "ok";
    private static final long STREAM_RELEASE_TIMEOUT_MS = 30_000L;
    private static final ExecutorService WRITERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "pdf-source-fault-provider");
        thread.setDaemon(true);
        return thread;
    });

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        String fixtureId = uri.getLastPathSegment();
        if (fixtureId == null) return "application/pdf";
        return getContext().getSharedPreferences(PREFS, 0)
                .getString(fixtureId + "." + KEY_MIME_TYPE, "application/pdf");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only PDF source fixture");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only PDF source fixture");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only PDF source fixture");
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (arg == null) throw new IllegalArgumentException("Fixture ID is required");
        android.content.SharedPreferences prefs = getContext().getSharedPreferences(PREFS, 0);
        String key = arg + ".";
        switch (method) {
            case "configure": {
                if (extras == null || extras.getByteArray(KEY_BYTES) == null) {
                    throw new IllegalArgumentException("Source bytes are required");
                }
                byte[] data = extras.getByteArray(KEY_BYTES);
                String mode = extras.getString(KEY_MODE);
                if (!MODE_COMPLETE.equals(mode) && !MODE_FAIL_AFTER_PREFIX.equals(mode)
                        && !MODE_BLOCK_THEN_COMPLETE.equals(mode)) {
                    throw new IllegalArgumentException("Unknown provider stream mode: " + mode);
                }
                int split = Math.max(1, Math.min(data.length - 1, extras.getInt(KEY_SPLIT, 64)));
                boolean saved = prefs.edit()
                        .putString(key + KEY_BYTES, android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
                        .putString(key + KEY_MIME_TYPE, extras.getString(KEY_MIME_TYPE, "application/pdf"))
                        .putString(key + KEY_MODE, mode)
                        .putInt(key + KEY_SPLIT, split)
                        .putInt(key + KEY_OPENS, 0)
                        .putInt(key + KEY_BYTES_WRITTEN, 0)
                        .putBoolean(key + KEY_PREFIX_WRITTEN, false)
                        .putBoolean(key + KEY_RELEASED, false)
                        .putString(key + KEY_OUTCOME, "NOT_OPENED")
                        .commit();
                if (!saved) throw new IllegalStateException("Could not persist provider fixture configuration");
                Bundle configured = new Bundle();
                configured.putBoolean(KEY_OK, true);
                return configured;
            }
            case "release": {
                if (!prefs.edit().putBoolean(key + KEY_RELEASED, true).commit()) {
                    throw new IllegalStateException("Could not release provider stream");
                }
                Bundle released = new Bundle();
                released.putBoolean(KEY_OK, true);
                return released;
            }
            case "grant": {
                String[] targetPackages = extras == null ? new String[0] : extras.getStringArray("target_packages");
                if (targetPackages == null || targetPackages.length == 0) {
                    throw new IllegalArgumentException("Target package names are required");
                }
                Uri fixtureUri = Uri.parse("content://" + AUTHORITY + "/pdf/" + arg);
                for (String targetPackage : targetPackages) {
                    getContext().grantUriPermission(targetPackage, fixtureUri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                Bundle granted = new Bundle();
                granted.putBoolean(KEY_OK, true);
                return granted;
            }
            case "revoke": {
                Uri fixtureUri = Uri.parse("content://" + AUTHORITY + "/pdf/" + arg);
                getContext().revokeUriPermission(fixtureUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Bundle revoked = new Bundle();
                revoked.putBoolean(KEY_OK, true);
                return revoked;
            }
            case "state": {
                Bundle state = new Bundle();
                state.putInt(KEY_OPENS, prefs.getInt(key + KEY_OPENS, 0));
                state.putInt(KEY_BYTES_WRITTEN, prefs.getInt(key + KEY_BYTES_WRITTEN, 0));
                state.putBoolean(KEY_PREFIX_WRITTEN, prefs.getBoolean(key + KEY_PREFIX_WRITTEN, false));
                state.putString(KEY_OUTCOME, prefs.getString(key + KEY_OUTCOME, "NOT_OPENED"));
                return state;
            }
            case "clear": {
                android.content.SharedPreferences.Editor edit = prefs.edit();
                String[] keys = {KEY_BYTES, KEY_MIME_TYPE, KEY_MODE, KEY_SPLIT, KEY_OPENS, KEY_BYTES_WRITTEN,
                        KEY_PREFIX_WRITTEN, KEY_RELEASED, KEY_OUTCOME};
                for (String item : keys) edit.remove(key + item);
                if (!edit.commit()) throw new IllegalStateException("Could not clear provider fixture");
                Bundle cleared = new Bundle();
                cleared.putBoolean(KEY_OK, true);
                return cleared;
            }
            default:
                throw new IllegalArgumentException("Unknown provider fixture command: " + method);
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("PDF source fixture is read-only");
        String fixtureId = uri.getLastPathSegment();
        if (fixtureId == null) throw new FileNotFoundException("Fixture ID is missing");
        android.content.SharedPreferences prefs = getContext().getSharedPreferences(PREFS, 0);
        String key = fixtureId + ".";
        String encoded = prefs.getString(key + KEY_BYTES, null);
        String streamMode = prefs.getString(key + KEY_MODE, null);
        if (encoded == null || streamMode == null) throw new FileNotFoundException("Fixture is not configured");
        final byte[] bytes;
        try {
            bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP);
        } catch (IllegalArgumentException failure) {
            throw new FileNotFoundException("Fixture bytes are malformed");
        }
        int split = Math.max(1, Math.min(bytes.length - 1, prefs.getInt(key + KEY_SPLIT, 64)));
        if (!prefs.edit().putInt(key + KEY_OPENS, prefs.getInt(key + KEY_OPENS, 0) + 1)
                .putString(key + KEY_OUTCOME, "OPENED").commit()) {
            throw new FileNotFoundException("Could not record provider open");
        }

        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
        } catch (IOException failure) {
            throw new FileNotFoundException("Could not create reliable PDF source pipe: " + failure.getMessage());
        }
        WRITERS.execute(() -> writeStream(pipe, prefs, key, bytes, streamMode, split));
        return pipe[0];
    }

    private static void writeStream(ParcelFileDescriptor[] pipe, android.content.SharedPreferences prefs,
                                    String key, byte[] bytes, String mode, int split) {
        ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
        try {
            if (MODE_COMPLETE.equals(mode)) {
                output.write(bytes);
                output.flush();
                prefs.edit().putInt(key + KEY_BYTES_WRITTEN, bytes.length)
                        .putString(key + KEY_OUTCOME, "COMPLETE").commit();
                output.close();
                return;
            }
            output.write(bytes, 0, split);
            output.flush();
            prefs.edit().putInt(key + KEY_BYTES_WRITTEN, split)
                    .putBoolean(key + KEY_PREFIX_WRITTEN, true)
                    .putString(key + KEY_OUTCOME, "WAITING_AFTER_PREFIX").commit();
            awaitRelease(prefs, key);
            if (MODE_BLOCK_THEN_COMPLETE.equals(mode)) {
                output.write(bytes, split, bytes.length - split);
                output.flush();
                prefs.edit().putInt(key + KEY_BYTES_WRITTEN, bytes.length)
                        .putString(key + KEY_OUTCOME, "COMPLETE_AFTER_RELEASE").commit();
                output.close();
            } else {
                prefs.edit().putString(key + KEY_OUTCOME, "FAILED_AFTER_PREFIX").commit();
                pipe[1].closeWithError("Injected provider failure after " + split + " bytes");
            }
        } catch (Exception failure) {
            String outcome = prefs.getString(key + KEY_OUTCOME, "");
            if (!"FAILED_AFTER_PREFIX".equals(outcome)) {
                prefs.edit().putString(key + KEY_OUTCOME, "WRITER_ERROR:" + failure.getClass().getSimpleName()).commit();
            }
            try { output.close(); } catch (IOException ignored) { }
        }
    }

    private static void awaitRelease(android.content.SharedPreferences prefs, String key) throws IOException {
        long deadline = System.currentTimeMillis() + STREAM_RELEASE_TIMEOUT_MS;
        while (!prefs.getBoolean(key + KEY_RELEASED, false)) {
            if (System.currentTimeMillis() >= deadline) throw new IOException("Test did not release the provider stream");
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Provider writer was interrupted", interrupted);
            }
        }
    }
}
