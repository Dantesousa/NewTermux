package com.newtermux.features;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Auto-update do nosso fork via GitHub Releases.
 * Verifica a latest em Dantesousa/NewTermux, baixa o APK da ABI do
 * aparelho (ignorando builds demo) e chama o instalador do sistema.
 */
public class NewTermuxUpdater {
    private static final String LOG_TAG = "NewTermuxUpdater";
    private static final String REPO = "Dantesousa/NewTermux";
    private static final String LATEST_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    public static class UpdateInfo {
        public final String version; // sem o 'v'
        public final String apkUrl;
        public final String apkName;
        public final long size;
        UpdateInfo(String version, String apkUrl, String apkName, long size) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.apkName = apkName;
            this.size = size;
        }
    }

    public interface UpdateCallback {
        void onResult(UpdateInfo info); // null = já está atualizado
        void onError(String message);
    }

    /** Roda em background e devolve o resultado na UI thread. */
    public static void checkForUpdate(Context context, UpdateCallback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                JSONObject rel = getJson(LATEST_URL);
                String tag = rel.optString("tag_name", "");
                String remote = tag.startsWith("v") ? tag.substring(1) : tag;
                if (remote.isEmpty()) throw new Exception("release sem tag");
                if (compareVersions(remote, BuildConfig.VERSION_NAME) <= 0) {
                    main.post(() -> callback.onResult(null));
                    return;
                }
                UpdateInfo info = pickAsset(rel, remote);
                if (info == null) throw new Exception("nenhum APK na release " + tag);
                main.post(() -> callback.onResult(info));
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Falha ao verificar update: " + e);
                main.post(() -> callback.onError("Sem conexão ou erro ao verificar"));
            }
        }).start();
    }

    /** Botão manual: toast de progresso + diálogo ou "já atualizado". */
    public static void checkNow(Activity activity) {
        Toast.makeText(activity, "Verificando atualizações…", Toast.LENGTH_SHORT).show();
        checkForUpdate(activity, new UpdateCallback() {
            @Override public void onResult(UpdateInfo info) {
                if (activity.isFinishing()) return;
                if (info == null)
                    Toast.makeText(activity, "Já está na última versão (v" + BuildConfig.VERSION_NAME + ")",
                        Toast.LENGTH_SHORT).show();
                else
                    showUpdateDialog(activity, info);
            }
            @Override public void onError(String message) {
                if (!activity.isFinishing())
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Checagem silenciosa 1x/dia na abertura; só incomoda se houver update. */
    public static void checkOnStartupIfEnabled(Activity activity) {
        if (!NewTermuxSettings.isAutoUpdateCheck(activity)) return;
        long now = System.currentTimeMillis();
        if (now - NewTermuxSettings.getLastUpdateCheck(activity) < CHECK_INTERVAL_MS) return;
        NewTermuxSettings.setLastUpdateCheck(activity, now);
        checkForUpdate(activity, new UpdateCallback() {
            @Override public void onResult(UpdateInfo info) {
                if (info != null && !activity.isFinishing()) showUpdateDialog(activity, info);
            }
            @Override public void onError(String message) { /* silencioso */ }
        });
    }

    public static void showUpdateDialog(Activity activity, UpdateInfo info) {
        new AlertDialog.Builder(activity)
            .setTitle("Nova versão disponível")
            .setMessage("v" + info.version + " pronta (" + formatSize(info.size) + "). Baixar e instalar?")
            .setPositiveButton("Baixar", (d, w) -> downloadAndInstall(activity, info))
            .setNegativeButton("Depois", null)
            .show();
    }

    public static void downloadAndInstall(Activity activity, UpdateInfo info) {
        Toast.makeText(activity, "Baixando v" + info.version + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Handler main = new Handler(Looper.getMainLooper());
            try {
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("sem cache");
                File apk = new File(dir, info.apkName);
                HttpURLConnection conn = (HttpURLConnection) new URL(info.apkUrl).openConnection();
                conn.setRequestProperty("User-Agent", "NewTermux-Updater");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.connect();
                if (conn.getResponseCode() / 100 != 2) throw new Exception("HTTP " + conn.getResponseCode());
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                main.post(() -> installApk(activity, apk));
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Falha ao baixar update: " + e);
                main.post(() -> {
                    if (!activity.isFinishing())
                        Toast.makeText(activity, "Falha ao baixar", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private static void installApk(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".updatefiles", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Falha ao instalar update: " + e);
            Toast.makeText(activity, "Permita \"instalar apps desconhecidos\" e tente de novo",
                Toast.LENGTH_LONG).show();
        }
    }

    // ---- helpers ----

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "NewTermux-Updater");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.connect();
        if (conn.getResponseCode() / 100 != 2) throw new Exception("HTTP " + conn.getResponseCode());
        byte[] bytes;
        try (InputStream in = conn.getInputStream()) {
            bytes = in.readAllBytes();
        }
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    /** Escolhe o APK da ABI do aparelho, ignorando builds demo; cai pro universal. */
    private static UpdateInfo pickAsset(JSONObject rel, String version) {
        JSONArray assets = rel.optJSONArray("assets");
        if (assets == null) return null;
        String abi = primaryAbiNeedle();
        UpdateInfo fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            if (!name.endsWith(".apk") || name.contains("demo")) continue;
            String url = a.optString("browser_download_url", "");
            if (url.isEmpty()) continue;
            UpdateInfo cand = new UpdateInfo(version, url, name, a.optLong("size", 0));
            if (name.contains(abi)) return cand; // match exato primeiro
            if (fallback == null && name.contains("universal")) fallback = cand;
            if (fallback == null) fallback = cand; // qualquer APK serve de último caso
        }
        return fallback;
    }

    private static String primaryAbiNeedle() {
        String[] abis = Build.SUPPORTED_ABIS;
        String abi = abis.length > 0 ? abis[0] : "";
        if (abi.startsWith("arm64")) return "arm64";
        if (abi.startsWith("armeabi")) return "armeabi";
        if (abi.contains("x86_64")) return "x86_64";
        if (abi.contains("x86")) return "x86";
        return abi;
    }

    /** -1 a<b, 0 iguais, 1 a>b. Compara partes numéricas ("1.6.3" vs "1.6.10"). */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parsePart(pa[i]) : 0;
            int vb = i < pb.length ? parsePart(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parsePart(String s) {
        try {
            String digits = s.replaceAll("^([0-9]+).*$", "$1");
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "tamanho desconhecido";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
