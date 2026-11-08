// GeneralsX @feature Flux Build 05/11/2026 The HTML map editor (assets/mapeditor/index.html) in a
// WebView, with the native side it was written for (window.AndroidApp):
//   - listGameFiles(): the game's .big archives (the game folder and one folder below, not mods/),
//     read-only. Their bytes are served at https://zhgame.local/f/<i>?o=<offset>&l=<length>.
//   - pickGameFolder(): the launcher already knows the game folder; the page is told to read it.
//   - save(name, base64): a .map goes to <user data>/Maps/<name>/<name>.map, where the game lists
//     it; a .zip of a map folder is unpacked there (paths checked).
//   - setOrientation(landscape|portrait).
// requestAllFiles (a search of the whole phone) is deliberately not offered. The editor works on Zero
// Hour data, so the Zero Hour folders are used whichever game the launcher has selected. A save never
// replaces a map folder the editor did not create (EDITOR_MARKER).
//
// The page is served from https://zhgame.local/ so its range reads are same-origin; every other
// request (the Google Fonts stylesheet included) is refused, so the editor works offline, sends
// nothing anywhere and falls back to the system fonts. The launcher theme's colours are applied to
// the page's CSS variables once it has loaded.
package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MapEditorActivity extends Activity {
    private static final String TAG = "MapEditor";
    private static final String HOST = "zhgame.local";
    private static final String PAGE = "https://" + HOST + "/index.html";
    private static final int REQUEST_FILE = 41;
    /** Largest single range the page may ask for; it reads archives piece by piece. */
    private static final long MAX_RANGE = 64L << 20;
    /** Largest map or map ZIP accepted from the page. */
    private static final int MAX_SAVE = 64 << 20;
    /** Left in each map folder the editor creates, so it may save over its own maps and no other. */
    private static final String EDITOR_MARKER = ".flux-map-editor";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private volatile List<File> gameFiles;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        ThemeHelper.applyBars(this);

        web = new WebView(this);
        web.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);          // the editor keeps its draft in localStorage
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        // GeneralsX @bugfix Flux Build 05/11/2026 The system bars are kept out by padding a container:
        // a WebView ignores its own padding when it lays out the page, so padding the WebView itself
        // (as before) left the editor's top bar under the status bar and its tools under the gesture
        // bar. The container shows the theme background behind the bars.
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        frame.addView(web, new android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        InsetUtil.applySafeInsets(frame);
        if (savedInstanceState == null || !restoreWebState(savedInstanceState)) {
            web.loadUrl(PAGE + "?lang=" + pageLanguage());
        }
    }

    /**
     * GeneralsX @feature Flux Build 05/11/2026 The editor's own texts exist in Arabic (its source) and
     * English (a dictionary in index.html): Arabic for an Arabic launcher, English for every other.
     */
    private String pageLanguage() {
        String lang = getResources().getConfiguration().getLocales().get(0).getLanguage();
        return "ar".equals(lang) ? "ar" : "en";
    }

    private boolean restoreWebState(Bundle state) {
        return web.restoreState(state) != null;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        web.removeJavascriptInterface("AndroidApp");
        web.destroy();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    // ------------------------------------------------------------------ page and game files

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // The editor is one page: no navigation away from it.
            return !HOST.equalsIgnoreCase(request.getUrl().getHost());
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (!HOST.equalsIgnoreCase(url.getHost())) {
                return refused();
            }
            String path = url.getPath() == null ? "/" : url.getPath();
            try {
                if (path.equals("/") || path.equals("/index.html")) {
                    InputStream page = getAssets().open("mapeditor/index.html");
                    return new WebResourceResponse("text/html", "utf-8", page);
                }
                if (path.startsWith("/f/")) {
                    return gameFileRange(Integer.parseInt(path.substring(3)),
                        Long.parseLong(url.getQueryParameter("o")), Long.parseLong(url.getQueryParameter("l")));
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "request failed: " + url + ": " + e.getMessage());
            }
            return refused();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            applyTheme();
        }
    }

    private static WebResourceResponse refused() {
        return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden",
            Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    /** Bytes [offset, offset + length) of game archive i; read-only. */
    private WebResourceResponse gameFileRange(int index, long offset, long length) throws IOException {
        List<File> files = listArchives();
        if (index < 0 || index >= files.size() || offset < 0 || length < 0 || length > MAX_RANGE) {
            return refused();
        }
        File file = files.get(index);
        long size = file.length();
        if (offset > size) {
            return refused();
        }
        int count = (int) Math.min(length, size - offset);
        byte[] data = new byte[count];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            raf.readFully(data);
        }
        return new WebResourceResponse("application/octet-stream", null, new ByteArrayInputStream(data));
    }

    /** The game folder's archives (the folder and one below, not mods/), named order; cached. */
    private List<File> listArchives() {
        List<File> files = gameFiles;
        if (files != null) {
            return files;
        }
        files = new ArrayList<>();
        String path = SetupActivity.getSavedGamePath(this, SetupActivity.GAME_ZERO_HOUR);
        File root = path == null ? null : new File(path);
        File[] top = root == null ? null : root.listFiles();
        if (top != null) {
            for (File f : top) {
                if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".big")) {
                    files.add(f);
                } else if (f.isDirectory() && !f.getName().equalsIgnoreCase("mods")) {
                    File[] sub = f.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".big"));
                    if (sub != null) {
                        Collections.addAll(files, sub);
                    }
                }
            }
        }
        files.sort((a, b) -> a.getPath().compareToIgnoreCase(b.getPath()));
        gameFiles = files;
        return files;
    }

    // ------------------------------------------------------------------ theme

    private void applyTheme() {
        String css = ":root{"
            + "--bg:" + hex(R.color.gzh_background) + ";"
            + "--panel:" + hex(R.color.gzh_surface_container) + ";"
            + "--raise:" + hex(R.color.gzh_surface_container_high) + ";"
            + "--line:" + hex(R.color.gzh_outline) + ";"
            + "--fg:" + hex(R.color.gzh_on_surface) + ";"
            + "--muted:" + hex(R.color.gzh_on_surface_variant) + ";"
            + "--accent:" + hex(R.color.gzh_primary) + ";"
            + "--accent-ink:" + hex(R.color.gzh_on_primary) + ";"
            + "--danger:" + hex(R.color.gzh_error) + ";"
            + "--ok:" + hex(R.color.gzh_status_green) + ";"
            + "color-scheme:" + (ThemeHelper.isLight(this) ? "light" : "dark") + ";}";
        String js = "(function(){var s=document.getElementById('flux-theme');"
            + "if(!s){s=document.createElement('style');s.id='flux-theme';document.head.appendChild(s);}"
            + "s.textContent=" + JSONObject.quote(css) + ";})();";
        web.evaluateJavascript(js, null);
    }

    private String hex(int colorRes) {
        return String.format(Locale.ROOT, "#%06X", UiKit.color(this, colorRes) & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ file chooser (open a .map)

    private final class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
            }
            fileCallback = callback;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            try {
                startActivityForResult(intent, REQUEST_FILE);
                return true;
            } catch (android.content.ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ window.AndroidApp

    private final class Bridge {
        @JavascriptInterface
        public String listGameFiles() {
            JSONArray out = new JSONArray();
            List<File> files = listArchives();
            try {
                for (int i = 0; i < files.size(); i++) {
                    JSONObject item = new JSONObject();
                    item.put("i", i);
                    item.put("name", files.get(i).getName());
                    item.put("size", files.get(i).length());
                    out.put(item);
                }
            } catch (org.json.JSONException e) {
                Log.w(TAG, "listGameFiles: " + e.getMessage());
            }
            return out.toString();
        }

        @JavascriptInterface
        public void pickGameFolder() {
            // Listed here, on the bridge thread, not on the UI thread.
            final boolean none = listArchives().isEmpty();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (none) {
                    Toast.makeText(MapEditorActivity.this, R.string.map_editor_no_game_folder, Toast.LENGTH_LONG).show();
                    return;
                }
                web.evaluateJavascript("window.onGameFolder && window.onGameFolder();", null);
            });
        }

        /**
         * GeneralsX @feature Flux Build 05/11/2026 Save to "game" (the game's Maps folder, as save())
         * or "downloads" (the phone's public Download folder, to share or keep a copy). Shows a toast
         * on success; returns where it went, or "ERR:..." for the page's own message.
         */
        @JavascriptInterface
        public String saveMapToLocation(String fileName, String base64, String destination) {
            try {
                String where = "downloads".equals(destination) ? exportToDownloads(fileName, base64) : saveMap(fileName, base64);
                runOnUiThread(() -> Toast.makeText(MapEditorActivity.this,
                    getString("downloads".equals(destination) ? R.string.map_editor_exported : R.string.map_editor_saved, where),
                    Toast.LENGTH_LONG).show());
                return where;
            } catch (IOException | IllegalArgumentException e) {
                Log.w(TAG, "save to " + destination + " failed: " + e.getMessage());
                return "ERR:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public String save(String fileName, String base64) {
            try {
                return saveMap(fileName, base64);
            } catch (IOException | IllegalArgumentException e) {
                Log.w(TAG, "save failed: " + e.getMessage());
                return "ERR:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public void setOrientation(String orientation) {
            runOnUiThread(() -> setRequestedOrientation("portrait".equals(orientation)
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
        }
    }

    /** Checks a file name from the page: a plain .map or .zip name. Returns it trimmed. */
    private static String checkedName(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.length() > 120 || name.contains("/") || name.contains("\\") || name.startsWith(".")
                || !(lower.endsWith(".map") || lower.endsWith(".zip"))) {
            throw new IllegalArgumentException("invalid file name");
        }
        return name;
    }

    /**
     * Copies a map out to the public Download folder under a free name ("MyMap (2).map"). With
     * All-files access, a plain file; without it (the permission was withdrawn) through MediaStore.
     */
    private String exportToDownloads(String fileName, String base64) throws IOException {
        String name = checkedName(fileName);
        if (base64 == null || base64.length() > (MAX_SAVE / 3) * 4 + 4) {
            throw new IllegalArgumentException("too large");
        }
        byte[] data = Base64.decode(base64, Base64.DEFAULT);
        int dot = name.lastIndexOf('.');
        String stem = name.substring(0, dot), ext = name.substring(dot);
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
                || android.os.Environment.isExternalStorageManager()) {
            File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            File target = new File(dir, name);
            for (int i = 2; target.exists() && i < 1000; i++) {
                target = new File(dir, stem + " (" + i + ")" + ext);
            }
            if (target.exists()) {
                throw new IOException("no free name in " + dir);
            }
            writeAtomically(target, data);
            return target.getPath();
        }
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
        Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("cannot create a file in Download");
        }
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                throw new IOException("cannot write to Download");
            }
            out.write(data);
        } catch (IOException e) {
            getContentResolver().delete(uri, null, null);
            throw e;
        }
        return android.os.Environment.DIRECTORY_DOWNLOADS + "/" + name;
    }

    /** Writes a saved map into the user Maps folder. Returns the folder it went to. */
    private String saveMap(String fileName, String base64) throws IOException {
        String name = checkedName(fileName);
        String lower = name.toLowerCase(Locale.ROOT);
        if (base64 == null || base64.length() > (MAX_SAVE / 3) * 4 + 4) {
            throw new IllegalArgumentException("too large");
        }
        byte[] data = Base64.decode(base64, Base64.DEFAULT);
        File maps = new File(DataPackInstaller.userDataDir(), "Maps");
        if (!maps.isDirectory() && !maps.mkdirs()) {
            throw new IOException("cannot create " + maps);
        }
        String stem = name.substring(0, name.length() - 4);
        File dir = new File(maps, stem);
        if (dir.exists() && !new File(dir, EDITOR_MARKER).isFile()) {
            throw new IOException("a map named \"" + stem + "\" already exists in Maps; give yours another name");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        writeAtomically(new File(dir, EDITOR_MARKER), new byte[0]);
        if (lower.endsWith(".map")) {
            writeAtomically(new File(dir, name), data);
            return dir.getPath();
        }
        int written = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                File out = new File(maps, entry.getName().replace('\\', '/'));
                // Only into the map's own folder (checked above), never next to other maps.
                if (!out.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                    throw new IOException("archive entry outside the map folder: " + entry.getName());
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent);
                }
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[65536];
                int n;
                while ((n = zip.read(chunk)) > 0) {
                    buffer.write(chunk, 0, n);
                    if (buffer.size() > MAX_SAVE) {
                        throw new IOException("archive entry too large");
                    }
                }
                writeAtomically(out, buffer.toByteArray());
                written++;
            }
        }
        if (written == 0) {
            throw new IOException("empty archive");
        }
        return new File(maps, stem).getPath();
    }

    private static void writeAtomically(File target, byte[] data) throws IOException {
        File temp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
        }
        if (target.exists() && !target.delete()) {
            temp.delete();
            throw new IOException("cannot replace " + target);
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException("cannot write " + target);
        }
    }
}
