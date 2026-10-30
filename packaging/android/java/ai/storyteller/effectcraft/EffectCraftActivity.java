package ai.storyteller.effectcraft;

import android.Manifest;
import android.app.Activity;
import android.app.NativeActivity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.webkit.MimeTypeMap;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EffectCraft Android activity wrapping {@link NativeActivity} (`libeffectcraft.so`).
 *
 * Bridges EffectCraft's file dialogs (`File ▸ Import`, `File ▸ Open`, `File ▸ Save As`,
 * `File ▸ Export`, Render Queue `Output To`, Home Screen "Import Footage…", Project panel
 * "Import File…") to Android's native system document picker
 * ({@link Intent#ACTION_OPEN_DOCUMENT} / {@link Intent#ACTION_CREATE_DOCUMENT}),
 * materializes `content://` URIs into readable/writable local files, syncs completed exports
 * back to SAF destinations and Android's public Movies/Downloads folders, and bridges hardware
 * gamepad input (buttons, triggers, D-pad, and dual analog sticks).
 */
public final class EffectCraftActivity extends NativeActivity {
    private static final int REQUEST_STORAGE_PERMISSIONS = 1001;
    private static final int REQUEST_FILE_DIALOG = 1002;

    // Gamepad button bit flags shared with apps/effectcraft/src/android.rs
    private static final int GP_BTN_SOUTH  = 1 << 0;
    private static final int GP_BTN_EAST   = 1 << 1;
    private static final int GP_BTN_WEST   = 1 << 2;
    private static final int GP_BTN_NORTH  = 1 << 3;
    private static final int GP_BTN_L1     = 1 << 4;
    private static final int GP_BTN_R1     = 1 << 5;
    private static final int GP_BTN_SELECT = 1 << 6;
    private static final int GP_BTN_START  = 1 << 7;
    private static final int GP_BTN_L3     = 1 << 8;
    private static final int GP_BTN_R3     = 1 << 9;
    private static final int GP_BTN_DPAD_UP    = 1 << 10;
    private static final int GP_BTN_DPAD_DOWN  = 1 << 11;
    private static final int GP_BTN_DPAD_LEFT  = 1 << 12;
    private static final int GP_BTN_DPAD_RIGHT = 1 << 13;

    private volatile String activeRequestId = "";
    private volatile String activeRequestMode = "OPEN";
    private volatile String activeDefaultName = "";
    private volatile File activeRequestDir = null;
    private volatile boolean running = true;
    private volatile boolean activityResumed = false;
    private volatile boolean nativeWindowReady = false;
    private volatile int lastSurfaceWidth = 0;
    private volatile int lastSurfaceHeight = 0;

    private final Map<String, UriAndMtime> saveSyncTargets = new ConcurrentHashMap<>();
    private final Map<String, ExportMirrorState> mirroredExports = new ConcurrentHashMap<>();

    // Gamepad state fields
    private final Object gamepadLock = new Object();
    private int gamepadButtons = 0;
    private float gpLx = 0.0f;
    private float gpLy = 0.0f;
    private float gpRx = 0.0f;
    private float gpRy = 0.0f;
    private float gpLt = 0.0f;
    private float gpRt = 0.0f;
    private float gpHatX = 0.0f;
    private float gpHatY = 0.0f;
    private long gamepadSeq = 0L;
    private long lastGamepadWriteMs = 0L;
    private volatile boolean gamepadDirty = false;

    private static final class UriAndMtime {
        final Uri uri;
        final String expectedStem;
        long observedMtime = -1L;
        long observedSize = -1L;
        long stableSinceMs = 0L;
        long lastSyncedMtime = -1L;
        long lastSyncedSize = -1L;

        UriAndMtime(Uri uri, String expectedStem) {
            this.uri = uri;
            this.expectedStem = expectedStem;
        }
    }

    private static final class ExportMirrorState {
        long observedMtime = -1L;
        long observedSize = -1L;
        long stableSinceMs = 0L;
        long mirroredMtime = -1L;
        long mirroredSize = -1L;
    }

    static {
        System.loadLibrary("effectcraft");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureDefaultDirectories();
        requestFilePermissionsIfNeeded();
        startDialogBridgeThread();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        super.onPause();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        super.surfaceCreated(holder);
        if (holder != null && holder.getSurfaceFrame() != null) {
            lastSurfaceWidth = holder.getSurfaceFrame().width();
            lastSurfaceHeight = holder.getSurfaceFrame().height();
        }
        nativeWindowReady = true;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        int prevW = lastSurfaceWidth;
        int prevH = lastSurfaceHeight;
        if (width > 0 && height > 0) {
            lastSurfaceWidth = width;
            lastSurfaceHeight = height;
        }
        // AOSP NativeActivity.onSurfaceChangedNative is a no-op when the Surface pointer is
        // unchanged, and winit's Android backend does not emit WindowEvent::Resized on
        // MainEvent::ConfigChanged. Cycling surfaceDestroyed/surfaceCreated when the surface
        // dimensions change (e.g. portrait <-> landscape rotation or split-screen resize)
        // triggers TerminateWindow -> InitWindow so eframe/egui-wgpu reconfigures the Vulkan
        // swapchain at the new width x height without losing app state.
        if (nativeWindowReady && prevW > 0 && prevH > 0 && width > 0 && height > 0
                && (width != prevW || height != prevH)) {
            nativeWindowReady = false;
            super.surfaceDestroyed(holder);
            super.surfaceCreated(holder);
            nativeWindowReady = true;
            return;
        }
        super.surfaceChanged(holder, format, width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        nativeWindowReady = false;
        lastSurfaceWidth = 0;
        lastSurfaceHeight = 0;
        super.surfaceDestroyed(holder);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && isGamepadKeyEvent(event)) {
            int keyCode = event.getKeyCode();
            int mask = keyCodeToGamepadMask(keyCode);
            if (mask != 0) {
                boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                boolean changed;
                synchronized (gamepadLock) {
                    int prev = gamepadButtons;
                    if (down) {
                        gamepadButtons |= mask;
                    } else if (event.getAction() == KeyEvent.ACTION_UP) {
                        gamepadButtons &= ~mask;
                    }
                    changed = (gamepadButtons != prev);
                    if (changed) {
                        gamepadSeq++;
                        gamepadDirty = true;
                    }
                }
                if (changed) {
                    flushGamepadState(true);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event != null && isGamepadMotionEvent(event) && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            synchronized (gamepadLock) {
                gpLx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X), 0.12f);
                gpLy = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y), 0.12f);
                float rx = event.getAxisValue(MotionEvent.AXIS_Z);
                if (Math.abs(rx) < 0.05f) {
                    rx = event.getAxisValue(MotionEvent.AXIS_RX);
                }
                float ry = event.getAxisValue(MotionEvent.AXIS_RZ);
                if (Math.abs(ry) < 0.05f) {
                    ry = event.getAxisValue(MotionEvent.AXIS_RY);
                }
                gpRx = applyDeadzone(rx, 0.12f);
                gpRy = applyDeadzone(ry, 0.12f);
                float lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
                if (lt < 0.02f) {
                    lt = event.getAxisValue(MotionEvent.AXIS_BRAKE);
                }
                float rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
                if (rt < 0.02f) {
                    rt = event.getAxisValue(MotionEvent.AXIS_GAS);
                }
                gpLt = clamp01(lt);
                gpRt = clamp01(rt);
                gpHatX = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_HAT_X), 0.25f);
                gpHatY = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_HAT_Y), 0.25f);
                gamepadSeq++;
                gamepadDirty = true;
            }
            flushGamepadState(false);
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private static boolean isGamepadKeyEvent(KeyEvent event) {
        int src = event.getSource();
        if ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (src & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) {
            int kc = event.getKeyCode();
            return keyCodeToGamepadMask(kc) != 0;
        }
        return KeyEvent.isGamepadButton(event.getKeyCode());
    }

    private static boolean isGamepadMotionEvent(MotionEvent event) {
        int src = event.getSource();
        return (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
    }

    private static int keyCodeToGamepadMask(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return GP_BTN_SOUTH;
            case KeyEvent.KEYCODE_BUTTON_B:
                return GP_BTN_EAST;
            case KeyEvent.KEYCODE_BUTTON_X:
                return GP_BTN_WEST;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return GP_BTN_NORTH;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return GP_BTN_L1;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return GP_BTN_R1;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_MODE:
                return GP_BTN_SELECT;
            case KeyEvent.KEYCODE_BUTTON_START:
                return GP_BTN_START;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return GP_BTN_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return GP_BTN_R3;
            case KeyEvent.KEYCODE_DPAD_UP:
                return GP_BTN_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return GP_BTN_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return GP_BTN_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return GP_BTN_DPAD_RIGHT;
            default:
                return 0;
        }
    }

    private static float applyDeadzone(float v, float dz) {
        if ( Float.isNaN(v) || Float.isInfinite(v)) {
            return 0.0f;
        }
        if (Math.abs(v) < dz) {
            return 0.0f;
        }
        float sign = v >= 0.0f ? 1.0f : -1.0f;
        float scaled = (Math.abs(v) - dz) / (1.0f - dz);
        return sign * Math.min(1.0f, Math.max(0.0f, scaled));
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            return 0.0f;
        }
        return Math.min(1.0f, Math.max(0.0f, v));
    }

    private void flushGamepadState(boolean immediate) {
        long now = SystemClock.uptimeMillis();
        if (!immediate && (now - lastGamepadWriteMs) < 14L) {
            return;
        }
        final String line;
        synchronized (gamepadLock) {
            if (!gamepadDirty && !immediate) {
                return;
            }
            int buttons = gamepadButtons;
            if (gpHatX < -0.5f) buttons |= GP_BTN_DPAD_LEFT;
            if (gpHatX > 0.5f) buttons |= GP_BTN_DPAD_RIGHT;
            if (gpHatY < -0.5f) buttons |= GP_BTN_DPAD_UP;
            if (gpHatY > 0.5f) buttons |= GP_BTN_DPAD_DOWN;
            line = String.format(
                    Locale.US,
                    "%d %d %.4f %.4f %.4f %.4f %.4f %.4f",
                    gamepadSeq,
                    buttons,
                    gpLx,
                    gpLy,
                    gpRx,
                    gpRy,
                    gpLt,
                    gpRt
            );
            gamepadDirty = false;
            lastGamepadWriteMs = now;
        }
        File dir = primaryIpcDir();
        if (dir != null) {
            writeLinesAtomically(new File(dir, ".ec_gamepad_state"), Collections.singletonList(line));
        }
    }

    private void ensureDefaultDirectories() {
        for (File dir : ipcDirs()) {
            new File(dir, "Exports").mkdirs();
            new File(dir, "Projects").mkdirs();
            new File(dir, "Imported").mkdirs();
        }
    }

    private void requestFilePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> needed = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addIfNotGranted(needed, Manifest.permission.READ_MEDIA_IMAGES);
                addIfNotGranted(needed, Manifest.permission.READ_MEDIA_VIDEO);
                addIfNotGranted(needed, Manifest.permission.READ_MEDIA_AUDIO);
            } else {
                addIfNotGranted(needed, Manifest.permission.READ_EXTERNAL_STORAGE);
                addIfNotGranted(needed, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), REQUEST_STORAGE_PERMISSIONS);
            }
        }
    }

    private void addIfNotGranted(List<String> list, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            list.add(permission);
        }
    }

    private List<File> ipcDirs() {
        List<File> dirs = new ArrayList<>(2);
        try {
            File ext = getExternalFilesDir(null);
            if (ext != null) {
                ext.mkdirs();
                dirs.add(ext);
            }
        } catch (Exception ignored) {
        }
        try {
            File internal = getFilesDir();
            if (internal != null && !dirs.contains(internal)) {
                internal.mkdirs();
                dirs.add(internal);
            }
        } catch (Exception ignored) {
        }
        return dirs;
    }

    private File primaryIpcDir() {
        List<File> dirs = ipcDirs();
        return dirs.isEmpty() ? getFilesDir() : dirs.get(0);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        final Uri uri;
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else {
            uri = null;
        }
        if (uri == null) {
            return;
        }
        new Thread(() -> {
            File dir = primaryIpcDir();
            String localPath = materializeUriForRead(dir, uri);
            if (localPath != null && !localPath.isEmpty()) {
                writeLinesAtomically(new File(dir, ".ec_incoming_open"), Collections.singletonList(localPath));
            }
        }, "ec-intent-import").start();
    }

    private void startDialogBridgeThread() {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    for (File dir : ipcDirs()) {
                        File req = new File(dir, ".ec_dialog_req");
                        if (req.isFile()) {
                            handleDialogRequestFile(dir, req);
                        }
                    }
                    if (gamepadDirty) {
                        flushGamepadState(false);
                    }
                    syncSavedFilesToContentUris();
                    syncExportsFolderToPublicStorage();
                    Thread.sleep(45L);
                } catch (InterruptedException ie) {
                    break;
                } catch (Exception ignored) {
                }
            }
        }, "ec-dialog-bridge");
        t.setDaemon(true);
        t.start();
    }

    private void handleDialogRequestFile(File dir, File req) {
        List<String> lines = readLines(req);
        req.delete();
        if (lines.isEmpty()) {
            return;
        }
        String reqId = lines.size() > 0 ? lines.get(0).trim() : "1";
        String mode = lines.size() > 1 ? lines.get(1).trim() : "OPEN";
        String extsCsv = lines.size() > 2 ? lines.get(2).trim() : "";
        String defaultName = lines.size() > 3 ? lines.get(3).trim() : "";

        activeRequestId = reqId;
        activeRequestMode = mode;
        activeDefaultName = defaultName;
        activeRequestDir = dir;

        runOnUiThread(() -> launchSystemPicker(mode, extsCsv, defaultName));
    }

    private void launchSystemPicker(String mode, String extsCsv, String defaultName) {
        final File reqDir = activeRequestDir != null ? activeRequestDir : primaryIpcDir();
        final String reqId = activeRequestId;
        try {
            Intent intent;
            if ("SAVE".equalsIgnoreCase(mode)) {
                intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                String title = (defaultName == null || defaultName.isEmpty())
                        ? defaultFilenameForExtensions(extsCsv)
                        : new File(defaultName).getName();
                intent.setType(mimeTypeForSave(title, extsCsv));
                intent.putExtra(Intent.EXTRA_TITLE, title);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else if ("FOLDER".equalsIgnoreCase(mode)) {
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else {
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                if ("OPEN_MULTI".equalsIgnoreCase(mode)) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            startActivityForResult(intent, REQUEST_FILE_DIALOG);
        } catch (Exception firstError) {
            if ("SAVE".equalsIgnoreCase(mode)) {
                File exportsDir = new File(reqDir, "Exports");
                exportsDir.mkdirs();
                String title = (defaultName == null || defaultName.isEmpty())
                        ? defaultFilenameForExtensions(extsCsv)
                        : sanitizeFileName(new File(defaultName).getName());
                File target = new File(exportsDir, title);
                writeDialogResponse(reqDir, reqId, Collections.singletonList(target.getAbsolutePath()));
                return;
            }
            if ("FOLDER".equalsIgnoreCase(mode)) {
                File exportsDir = new File(reqDir, "Exports");
                exportsDir.mkdirs();
                writeDialogResponse(reqDir, reqId, Collections.singletonList(exportsDir.getAbsolutePath()));
                return;
            }
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("*/*");
                if ("OPEN_MULTI".equalsIgnoreCase(mode)) {
                    fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                startActivityForResult(Intent.createChooser(fallback, "Select File"), REQUEST_FILE_DIALOG);
            } catch (Exception secondError) {
                writeDialogResponse(reqDir, reqId, new ArrayList<>());
            }
        }
    }

    private static String defaultFilenameForExtensions(String extsCsv) {
        String ext = firstExtension(extsCsv);
        if (ext.isEmpty()) {
            return "Export.mp4";
        }
        return "Untitled." + ext;
    }

    private static String firstExtension(String extsCsv) {
        if (extsCsv == null || extsCsv.trim().isEmpty()) {
            return "";
        }
        String[] parts = extsCsv.split(",");
        for (String p : parts) {
            String trimmed = p.trim().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith(".")) {
                trimmed = trimmed.substring(1);
            }
            if (!trimmed.isEmpty() && !"*".equals(trimmed)) {
                return trimmed;
            }
        }
        return "";
    }

    private static String extensionOfName(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= name.length()) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String mimeTypeForSave(String fileName, String extsCsv) {
        String ext = extensionOfName(fileName);
        if (ext.isEmpty()) {
            ext = firstExtension(extsCsv);
        }
        switch (ext) {
            case "mp4":
            case "m4v":
                return "video/mp4";
            case "mov":
                return "video/quicktime";
            case "webm":
            case "mkv":
                return "video/webm";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "webp":
                return "image/webp";
            case "gif":
                return "image/gif";
            case "wav":
                return "audio/wav";
            case "flac":
                return "audio/flac";
            case "json":
                return "application/json";
            case "svg":
                return "image/svg+xml";
            default:
                return "application/octet-stream";
        }
    }

    private void waitForActivityReady(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (running && SystemClock.uptimeMillis() < deadline) {
            if (activityResumed && nativeWindowReady) {
                break;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException ie) {
                break;
            }
        }
        // Give the Vulkan swapchain one frame to settle after resume before running export commands
        try {
            Thread.sleep(90L);
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE_DIALOG) {
            return;
        }
        final String reqId = activeRequestId;
        final String mode = activeRequestMode;
        final String defaultName = activeDefaultName;
        final File reqDir = activeRequestDir != null ? activeRequestDir : primaryIpcDir();

        final List<Uri> uris = new ArrayList<>();
        if (resultCode == Activity.RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) {
                        uris.add(u);
                    }
                }
            }
            if (uris.isEmpty() && data.getData() != null) {
                uris.add(data.getData());
            }
        }

        new Thread(() -> {
            List<String> paths = new ArrayList<>();
            for (Uri uri : uris) {
                if ("SAVE".equalsIgnoreCase(mode)) {
                    String savePath = materializeUriForWrite(reqDir, uri, defaultName);
                    if (savePath != null && !savePath.isEmpty()) {
                        paths.add(savePath);
                    }
                } else if ("FOLDER".equalsIgnoreCase(mode)) {
                    String folderPath = resolveTreeUriPath(reqDir, uri);
                    if (folderPath != null && !folderPath.isEmpty()) {
                        paths.add(folderPath);
                    }
                } else {
                    String readPath = materializeUriForRead(reqDir, uri);
                    if (readPath != null && !readPath.isEmpty()) {
                        paths.add(readPath);
                    }
                }
            }
            waitForActivityReady(3500L);
            writeDialogResponse(reqDir, reqId, paths);
        }, "ec-dialog-materialize").start();
    }

    private String materializeUriForRead(File baseDir, Uri uri) {
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String p = uri.getPath();
                if (p != null && new File(p).canRead()) {
                    return p;
                }
            }
            String direct = tryResolveDirectStoragePath(uri);
            if (direct != null) {
                File f = new File(direct);
                if (f.isFile() && f.canRead()) {
                    return f.getAbsolutePath();
                }
            }
            ContentResolver resolver = getContentResolver();
            String fileName = queryDisplayName(resolver, uri, "imported_file");
            File importDir = new File(baseDir, "Imported");
            importDir.mkdirs();
            File dest = uniqueDestination(importDir, fileName);
            try (InputStream in = resolver.openInputStream(uri);
                 OutputStream out = new FileOutputStream(dest)) {
                if (in == null) {
                    return null;
                }
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            return dest.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private String materializeUriForWrite(File baseDir, Uri uri, String fallbackName) {
        try {
            ContentResolver resolver = getContentResolver();
            String defName = (fallbackName == null || fallbackName.isEmpty())
                    ? "Export.mp4"
                    : new File(fallbackName).getName();
            String expectedExt = extensionOfName(defName);
            String fileName = queryDisplayName(resolver, uri, defName);
            if (!expectedExt.isEmpty() && extensionOfName(fileName).isEmpty()) {
                fileName = fileName + "." + expectedExt;
            }
            File exportsDir = new File(baseDir, "Exports");
            exportsDir.mkdirs();
            File localFile = new File(exportsDir, fileName);
            String stem = stripExtension(localFile.getName());
            saveSyncTargets.put(localFile.getAbsolutePath(), new UriAndMtime(uri, stem));
            return localFile.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String resolveTreeUriPath(File baseDir, Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            String direct = documentIdToStoragePath(docId);
            if (direct != null) {
                File dir = new File(direct);
                if (dir.isDirectory() && dir.canWrite()) {
                    return dir.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {
        }
        File fallback = new File(baseDir, "Exports");
        fallback.mkdirs();
        return fallback.getAbsolutePath();
    }

    private String tryResolveDirectStoragePath(Uri uri) {
        try {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                String authority = uri.getAuthority();
                String docId = DocumentsContract.getDocumentId(uri);
                if ("com.android.externalstorage.documents".equals(authority)) {
                    return documentIdToStoragePath(docId);
                }
                if ("com.android.providers.downloads.documents".equals(authority) && docId != null && docId.startsWith("raw:")) {
                    return docId.substring(4);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String documentIdToStoragePath(String docId) {
        if (docId == null) {
            return null;
        }
        int colon = docId.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String type = docId.substring(0, colon);
        String rel = docId.substring(colon + 1);
        if ("primary".equalsIgnoreCase(type)) {
            return rel.isEmpty() ? "/storage/emulated/0" : "/storage/emulated/0/" + rel;
        }
        if ("home".equalsIgnoreCase(type)) {
            return rel.isEmpty() ? "/storage/emulated/0/Documents" : "/storage/emulated/0/Documents/" + rel;
        }
        return rel.isEmpty() ? "/storage/" + type : "/storage/" + type + "/" + rel;
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri, String fallback) {
        String name = null;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            String seg = uri.getLastPathSegment();
            if (seg != null) {
                int slash = seg.lastIndexOf('/');
                int colon = seg.lastIndexOf(':');
                int cut = Math.max(slash, colon);
                name = cut >= 0 ? seg.substring(cut + 1) : seg;
            }
        }
        if (name == null || name.trim().isEmpty()) {
            name = fallback;
        }
        name = sanitizeFileName(name);
        if (!name.contains(".")) {
            String fallbackExt = extensionOfName(fallback);
            if (!fallbackExt.isEmpty()) {
                name = name + "." + fallbackExt;
            } else {
                String mime = resolver.getType(uri);
                if (mime != null) {
                    String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                    if (ext != null && !ext.isEmpty()) {
                        name = name + "." + ext;
                    }
                }
            }
        }
        return name;
    }

    private static String sanitizeFileName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|' || c < 32) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        return cleaned.isEmpty() ? "imported_file" : cleaned;
    }

    private static File uniqueDestination(File dir, String fileName) {
        return new File(dir, fileName);
    }

    private static File findActualExportFile(File target, String stem) {
        if (target.isFile() && target.length() > 0) {
            return target;
        }
        File parent = target.getParentFile();
        if (parent != null && parent.isDirectory() && stem != null && !stem.isEmpty()) {
            File[] siblings = parent.listFiles();
            if (siblings != null) {
                File newest = null;
                long newestMtime = -1L;
                for (File sibling : siblings) {
                    if (sibling.isFile() && !sibling.getName().endsWith(".tmp") && sibling.length() > 0) {
                        String sibStem = stripExtension(sibling.getName());
                        if (stem.equalsIgnoreCase(sibStem) && sibling.lastModified() > newestMtime) {
                            newest = sibling;
                            newestMtime = sibling.lastModified();
                        }
                    }
                }
                if (newest != null) {
                    return newest;
                }
            }
        }
        return target;
    }

    private OutputStream openUriOutputStream(Uri uri) throws Exception {
        ContentResolver resolver = getContentResolver();
        try {
            OutputStream out = resolver.openOutputStream(uri, "wt");
            if (out != null) {
                return out;
            }
        } catch (Exception ignored) {
        }
        return resolver.openOutputStream(uri, "w");
    }

    private void syncSavedFilesToContentUris() {
        long now = SystemClock.uptimeMillis();
        for (Map.Entry<String, UriAndMtime> entry : saveSyncTargets.entrySet()) {
            UriAndMtime state = entry.getValue();
            File f = findActualExportFile(new File(entry.getKey()), state.expectedStem);
            if (!f.isFile()) {
                continue;
            }
            long mtime = f.lastModified();
            long size = f.length();
            if (size <= 0) {
                continue;
            }
            if (mtime != state.observedMtime || size != state.observedSize) {
                state.observedMtime = mtime;
                state.observedSize = size;
                state.stableSinceMs = now;
                continue;
            }
            if ((now - state.stableSinceMs) < 450L) {
                continue;
            }
            if (mtime != state.lastSyncedMtime || size != state.lastSyncedSize) {
                try (InputStream in = new FileInputStream(f);
                     OutputStream out = openUriOutputStream(state.uri)) {
                    if (out != null) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                        out.flush();
                        state.lastSyncedMtime = mtime;
                        state.lastSyncedSize = size;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void syncExportsFolderToPublicStorage() {
        long now = SystemClock.uptimeMillis();
        for (File ipcDir : ipcDirs()) {
            File exportsDir = new File(ipcDir, "Exports");
            File[] files = exportsDir.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (!f.isFile() || f.getName().startsWith(".") || f.getName().endsWith(".tmp")) {
                    continue;
                }
                long size = f.length();
                long mtime = f.lastModified();
                if (size <= 0) {
                    continue;
                }
                String key = f.getAbsolutePath();
                ExportMirrorState st = mirroredExports.get(key);
                if (st == null) {
                    st = new ExportMirrorState();
                    mirroredExports.put(key, st);
                }
                if (mtime != st.observedMtime || size != st.observedSize) {
                    st.observedMtime = mtime;
                    st.observedSize = size;
                    st.stableSinceMs = now;
                    continue;
                }
                if ((now - st.stableSinceMs) < 600L) {
                    continue;
                }
                if (mtime != st.mirroredMtime || size != st.mirroredSize) {
                    st.mirroredMtime = mtime;
                    st.mirroredSize = size;
                    mirrorExportFileToPublicFolder(f);
                }
            }
        }
    }

    private void mirrorExportFileToPublicFolder(File sourceFile) {
        try {
            String ext = extensionOfName(sourceFile.getName());
            boolean isMedia = "mp4".equals(ext) || "mov".equals(ext) || "webm".equals(ext)
                    || "mkv".equals(ext) || "gif".equals(ext) || "png".equals(ext)
                    || "jpg".equals(ext) || "jpeg".equals(ext) || "webp".equals(ext);
            File pubRoot = Environment.getExternalStoragePublicDirectory(
                    isMedia ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_DOWNLOADS
            );
            if (pubRoot != null) {
                File pubDir = new File(pubRoot, "EffectCraft");
                if (pubDir.mkdirs() || pubDir.isDirectory()) {
                    File dest = new File(pubDir, sourceFile.getName());
                    if (!dest.getAbsolutePath().equals(sourceFile.getAbsolutePath())) {
                        try (InputStream in = new FileInputStream(sourceFile);
                             OutputStream out = new FileOutputStream(dest)) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                out.write(buf, 0, n);
                            }
                            out.flush();
                        }
                        MediaScannerConnection.scanFile(
                                this,
                                new String[]{dest.getAbsolutePath()},
                                null,
                                null
                        );
                        return;
                    }
                }
            }
            MediaScannerConnection.scanFile(
                    this,
                    new String[]{sourceFile.getAbsolutePath()},
                    null,
                    null
            );
        } catch (Exception ignored) {
        }
    }

    private static List<String> readLines(File file) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (Exception ignored) {
        }
        return lines;
    }

    private static void writeDialogResponse(File dir, String reqId, List<String> paths) {
        List<String> lines = new ArrayList<>(paths.size() + 1);
        lines.add(reqId != null ? reqId : "");
        lines.addAll(paths);
        writeLinesAtomically(new File(dir, ".ec_dialog_resp"), lines);
    }

    private static void writeLinesAtomically(File target, List<String> lines) {
        try {
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            if (target.exists()) {
                target.delete();
            }
            tmp.renameTo(target);
        } catch (Exception ignored) {
        }
    }
}
