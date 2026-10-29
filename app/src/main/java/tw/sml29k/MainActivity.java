package tw.sml29k;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/** 畫面外殼：WebView 載入 assets/index.html，透過 Android 橋接取得服務狀態。 */
public class MainActivity extends Activity {
    private WebView web;
    private SharedPreferences sp;
    private static final int REQ_LOC = 1, REQ_NOTIF = 2;
    private boolean pendingStart = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("race", MODE_PRIVATE);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(true);
        s.setTextZoom(100); s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setBackgroundColor(0xFF0B0F11);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url.startsWith("file:")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    @Override protected void onResume() {
        super.onResume();
        GpsService.listener = json -> runOnUiThread(() -> web.evaluateJavascript("window.onNativeState&&window.onNativeState(" + json + ")", null));
        applyKeepOn();
    }
    @Override protected void onPause() { super.onPause(); GpsService.listener = null; }

    void applyKeepOn() {
        if (sp.getBoolean("keepOn", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ---------- 權限流程：定位 → 通知 → 啟動服務 ----------
    void startRace() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true; requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF); return;
        }
        pendingStart = false;
        GpsService.start(this);
        askIgnoreBattery();
    }
    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_LOC) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) { if (pendingStart) startRace(); }
            else web.evaluateJavascript("window.onNativeState&&window.onNativeState({err:'noperm'})", null);
        } else if (req == REQ_NOTIF && pendingStart) { pendingStart = false; GpsService.start(this); askIgnoreBattery(); }
    }
    void askIgnoreBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName()) && !sp.getBoolean("askedBat", false)) {
                sp.edit().putBoolean("askedBat", true).apply();
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) { }
    }

    /** JS 可呼叫的介面 */
    class Bridge {
        @JavascriptInterface public void start() { runOnUiThread(MainActivity.this::startRace); }
        @JavascriptInterface public void stop() { GpsService.stop(MainActivity.this); }
        @JavascriptInterface public boolean isRunning() { return GpsService.running; }
        @JavascriptInterface public String getPrefs() {
            try {
                return new JSONObject().put("target", sp.getInt("target", 255)).put("vib", sp.getBoolean("vib", true)).put("tts", sp.getBoolean("tts", true))
                    .put("keepOn", sp.getBoolean("keepOn", false)).put("name", sp.getString("name", "")).put("start", sp.getLong("start", 0))
                    .put("splits", new JSONObject(sp.getString("splits", "{}"))).put("theme", sp.getString("theme", "")).toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void setPref(String k, String v) {
            SharedPreferences.Editor e = sp.edit();
            switch (k) {
                case "target": e.putInt(k, Integer.parseInt(v)); break;
                case "vib": case "tts": case "keepOn": e.putBoolean(k, "1".equals(v)); break;
                case "start": e.putLong(k, Long.parseLong(v)); break;
                default: e.putString(k, v);
            }
            e.apply();
            if ("keepOn".equals(k)) runOnUiThread(MainActivity.this::applyKeepOn);
        }
        @JavascriptInterface public void clearSplits() { sp.edit().remove("splits").apply(); }
        @JavascriptInterface public void setStart(long ms) { if (ms <= 0) sp.edit().remove("start").remove("splits").apply(); else sp.edit().putLong("start", ms).remove("splits").apply(); }
        @JavascriptInterface public void testVibrate() { try { android.os.Vibrator v = Build.VERSION.SDK_INT >= 31 ? ((android.os.VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE)).getDefaultVibrator() : (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE); v.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 200, 100, 200}, -1)); } catch (Exception ignored) { } }
        @JavascriptInterface public void testSpeak() {
            runOnUiThread(() -> { final android.speech.tts.TextToSpeech[] t = new android.speech.tts.TextToSpeech[1];
                t[0] = new android.speech.tts.TextToSpeech(MainActivity.this, st -> { if (st == android.speech.tts.TextToSpeech.SUCCESS) { t[0].setLanguage(java.util.Locale.TAIWAN); t[0].speak("語音提醒測試，該吃第一包能量膠了", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "t"); } }); });
        }
        @JavascriptInterface public void copy(String text) { runOnUiThread(() -> { android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE); cm.setPrimaryClip(android.content.ClipData.newPlainText("splits", text)); }); }
        @JavascriptInterface public void openSettings() { try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { } }
    }

    @Override public void onBackPressed() { moveTaskToBack(true); }
}
