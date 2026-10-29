package tw.sml29k;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * 前景服務：螢幕關著也持續抓 GPS、對到路線、發出震動 / 語音提醒、記錄分段、更新通知列。
 * 畫面（WebView）只是把這裡的狀態畫出來。
 */
public class GpsService extends Service implements LocationListener {
    static final String ACT_START = "start", ACT_STOP = "stop";
    static final String CH = "race";
    static final long GUN = 1761950100000L;            // 2026-11-01 06:35:00 +08:00
    static final double DEMO_SPEED = 60;

    /** 畫面要的回呼（Activity 在前景時設定） */
    interface Listener { void onState(String json); }
    static volatile Listener listener;
    static volatile boolean running;

    private LocationManager lm;
    private PowerManager.WakeLock wl;
    private Vibrator vib;
    private TextToSpeech tts; private boolean ttsReady;
    private SharedPreferences sp;
    private final Handler h = new Handler(Looper.getMainLooper());

    // 狀態
    double curS = 0, acc = -1, dev = -1; long lastFix = 0, lastSave = 0, lastNotif = 0;
    boolean hasHist = false; int badJumps = 0;
    final ArrayDeque<long[]> hist = new ArrayDeque<>();    // {時間ms, 里程m*1000}
    String lastAlert = ""; long lastAlertAt = 0;
    double lastSplitS = 0;

    @Override public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences("race", MODE_PRIVATE);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 31) vib = ((VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE)).getDefaultVibrator();
        else vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        tts = new TextToSpeech(this, st -> { if (st == TextToSpeech.SUCCESS) { int r = tts.setLanguage(Locale.TAIWAN); ttsReady = r >= 0; tts.setSpeechRate(1.05f); } });
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false); nm.createNotificationChannel(ch);
        curS = sp.getFloat("pos", 0); long pt = sp.getLong("posT", 0);
        if (System.currentTimeMillis() - pt > 2 * 3600_000L) curS = 0;
        lastSplitS = curS;
    }

    @Override public int onStartCommand(Intent in, int flags, int id) {
        String a = in == null ? ACT_START : in.getAction();
        if (ACT_STOP.equals(a)) { stopSelf(); return START_NOT_STICKY; }
        Notification n = buildNotif("正在搜尋 GPS…", "請到空曠處");
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION); else startForeground(1, n);
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (wl == null) { wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sml29k:gps"); wl.acquire(6 * 3600_000L); }
            lm.removeUpdates(this);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        } catch (SecurityException e) { push("{\"err\":\"noperm\"}"); stopSelf(); return START_NOT_STICKY; }
        running = true;
        h.removeCallbacks(tickR); h.post(tickR);
        return START_STICKY;
    }

    private final Runnable tickR = new Runnable() { @Override public void run() { push(state()); h.postDelayed(this, 1000); } };

    @Override public void onDestroy() {
        running = false; h.removeCallbacks(tickR);
        try { lm.removeUpdates(this); } catch (Exception ignored) { }
        if (wl != null && wl.isHeld()) wl.release();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }

    // ---------- GPS → 路線里程 ----------
    @Override public void onLocationChanged(Location p) {
        long now = System.currentTimeMillis();
        acc = p.getAccuracy(); lastFix = now;
        if (acc > 80) return;
        double x = Route.toX(p.getLongitude()), y = Route.toY(p.getLatitude());
        double[] b;
        if (hasHist) {
            int li = (int) Math.floor(curS / 100);
            b = Route.best(x, y, li - 3, li + 20);
            if (b[0] > 150) { double[] g = Route.best(x, y, 0, Route.N - 2); if (g[0] < b[0] - 30 && g[0] < 100) b = g; }
            long[] last = hist.peekLast();
            if (last != null) {
                double dt = (now - last[0]) / 1000.0;
                if (dt > 0 && Math.abs(b[1] - last[1] / 1000.0) / dt > 8 && badJumps < 3) { badJumps++; return; }
            }
            badJumps = 0;
            if (b[1] < curS && curS - b[1] < 120) b[1] = curS;
        } else {
            int li = curS > 0 ? (int) Math.floor(curS / 100) : -1;
            b = li >= 0 ? Route.best(x, y, li - 10, li + 10) : new double[]{1e9, 0};
            if (b[0] > 100) b = Route.best(x, y, 0, Route.N - 2);
            if (b[1] > 27000) { double[] b0 = Route.best(x, y, 0, 5); if (b0[0] < b[0] + 60) b = b0; }
            hasHist = true;
        }
        curS = b[1]; dev = b[0];
        hist.addLast(new long[]{now, (long) (curS * 1000)});
        while (!hist.isEmpty() && now - hist.peekFirst()[0] > 120_000) hist.pollFirst();
        if (now - lastSave > 20_000) { sp.edit().putFloat("pos", (float) curS).putLong("posT", now).apply(); lastSave = now; }
        checkSplits(now); checkAlerts(now);
        if (now - lastNotif > 5000) { updateNotif(); lastNotif = now; }
        push(state());
    }
    @Override public void onProviderDisabled(String p) { push("{\"err\":\"gpsoff\"}"); }
    @Override public void onProviderEnabled(String p) { }

    // ---------- 時間 ----------
    long startMs() { long s = sp.getLong("start", 0); if (s > 0 && Math.abs(System.currentTimeMillis() - s) < 12 * 3600_000L) return s; long now = System.currentTimeMillis(); if (now > GUN - 3600_000L && now < GUN + 7 * 3600_000L) return GUN; return 0; }
    double elapsedMin() { long s = startMs(); return s == 0 ? -1 : (System.currentTimeMillis() - s) / 60000.0; }
    double targetMin() { return sp.getInt("target", 255); }
    /** 近 90 秒配速（秒/km），無法算則 -1 */
    double paceSec(long now) {
        if (hist.size() < 2 || now - lastFix > 20000) return -1;
        long[] old = null; for (long[] e : hist) { if (now - e[0] <= 90000) { old = e; break; } }
        long[] last = hist.peekLast();
        if (old == null || last[0] - old[0] < 30000) return -1;
        double ds = (last[1] - old[1]) / 1000.0; if (ds < 20) return 0;
        return (last[0] - old[0]) / 1000.0 / ds * 1000;
    }

    // ---------- 分段紀錄 ----------
    void checkSplits(long now) {
        double emin = elapsedMin(); if (emin < 0) { lastSplitS = curS; return; }
        try {
            JSONObject sj = new JSONObject(sp.getString("splits", "{}"));
            boolean changed = false;
            for (int i = 1; i < Route.LM_KM.length; i++) {
                double m = Route.LM_KM[i] * 1000;
                if (m > lastSplitS && m <= curS && !sj.has(Route.LM_N[i])) {
                    int t = (int) Math.round(emin * 60), plan = (int) Math.round(Route.planMin(Route.LM_KM[i], targetMin()) * 60);
                    sj.put(Route.LM_N[i], new JSONObject().put("t", t).put("c", now).put("plan", plan));
                    changed = true;
                    int d = plan - t;
                    String say = Route.LM_NAME[i] + "，比計畫" + (d >= 0 ? "快" : "慢") + mmss(Math.abs(d));
                    int nx = Route.nextLandmark(curS);
                    if (nx >= 0) { double pm = Route.planMin(Route.LM_KM[nx], targetMin()); say += "。下一個" + Route.LM_NAME[nx] + "，計畫" + clockOf(pm) + "到"; }
                    buzz(new long[]{0, 120}); speak(say);
                }
            }
            if (changed) sp.edit().putString("splits", sj.toString()).apply();
        } catch (JSONException ignored) { }
        lastSplitS = curS;
    }

    // ---------- 提醒（同一事件只提醒一次） ----------
    void checkAlerts(long now) {
        String key = "", say = ""; long[] pat = null;
        double emin = elapsedMin();
        if (curS > 24800 && curS < 26200) { key = "turn"; say = "注意，頭社路口，二十九K往右轉回向山，不要跟四十二K往左"; pat = new long[]{0, 300, 150, 300, 150, 600}; }
        else if (dev > 80 && now - lastFix < 20000) { key = "off" + (int) (curS / 500); say = "你離路線" + (int) dev + "公尺，確認有沒有跑錯"; pat = new long[]{0, 400, 200, 400}; }
        else {
            int nc = -1; for (int i = 0; i < Route.CUT_KM.length; i++) if (Route.CUT_KM[i] * 1000 > curS + 20) { nc = i; break; }
            if (nc >= 0 && nc < 2 && emin >= 0) {
                double left = Route.CUT_MIN[nc] - emin, dist = Route.CUT_KM[nc] - curS / 1000;
                if (left > 0 && dist > 0.3 && left / dist < 9.5) { key = "cut" + nc; say = "關門吃緊，" + Route.CUT_NAME[nc] + "還有" + (int) left + "分鐘，請加快"; pat = new long[]{0, 400, 200, 400}; }
            }
            if (key.isEmpty()) for (int i = 0; i < Route.GEL_KM.length; i++) {
                double d = Route.GEL_KM[i] * 1000 - curS;
                if (d > -80 && d < 250) { key = "gel" + i; say = "該吃" + Route.GEL_T[i] + "能量膠了，配一百毫升水"; pat = new long[]{0, 200, 100, 200}; break; }
            }
        }
        if (!key.isEmpty() && !key.equals(lastAlert)) { buzz(pat); speak(say); lastAlert = key; lastAlertAt = now; }
        if (key.isEmpty() && now - lastAlertAt > 60000) lastAlert = "";
        if (!key.equals(sp.getString("alertKey", ""))) sp.edit().putString("alertKey", key).apply();
    }

    void buzz(long[] pat) { if (sp.getBoolean("vib", true) && vib != null) try { vib.vibrate(VibrationEffect.createWaveform(pat, -1)); } catch (Exception ignored) { } }
    void speak(String s) { if (sp.getBoolean("tts", true) && ttsReady) tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "sml"); }

    // ---------- 狀態 JSON（給畫面） ----------
    String state() {
        long now = System.currentTimeMillis();
        try {
            JSONObject o = new JSONObject();
            o.put("s", curS).put("acc", acc).put("dev", dev).put("fixAge", lastFix == 0 ? -1 : (now - lastFix) / 1000)
             .put("pace", paceSec(now)).put("start", startMs()).put("splits", new JSONObject(sp.getString("splits", "{}")))
             .put("alertKey", sp.getString("alertKey", "")).put("running", running);
            return o.toString();
        } catch (JSONException e) { return "{}"; }
    }
    void push(String json) { Listener l = listener; if (l != null) l.onState(json); }

    // ---------- 通知列 ----------
    Notification buildNotif(String title, String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, GpsService.class).setAction(ACT_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).setCategory(Notification.CATEGORY_NAVIGATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(new Notification.Action.Builder(null, "停止定位", ps).build()).build();
    }
    void updateNotif() {
        double km = curS / 1000; int nx = Route.nextLandmark(curS); double emin = elapsedMin();
        String title = String.format(Locale.ROOT, "%.1fK", km) + (nx >= 0 ? "｜" + Route.LM_NAME[nx] + " 還 " + dist(Route.LM_KM[nx] * 1000 - curS) : "｜終點");
        String text;
        if (emin >= 0) {
            double d = Route.planMin(km, targetMin()) - emin;
            text = "已跑 " + hm(emin) + "｜" + (d >= 0 ? "快 " : "慢 ") + mmss((int) Math.round(Math.abs(d) * 60));
            if (nx >= 0) { double ps = paceSec(System.currentTimeMillis()); double pm = ps > 0 ? ps / 60 : Route.planPace(km, targetMin()); text += "｜預計 " + clockOf(emin + (Route.LM_KM[nx] * 1000 - curS) / 1000 * pm) + " 到"; }
        } else text = "GPS ±" + (int) acc + " m";
        getSystemService(NotificationManager.class).notify(1, buildNotif(title, text));
    }

    static String mmss(int sec) { return sec / 60 + "分" + (sec % 60 > 0 ? sec % 60 + "秒" : ""); }
    static String hm(double min) { int m = (int) Math.round(min); return m / 60 + ":" + String.format(Locale.ROOT, "%02d", m % 60); }
    static String clockOf(double min) { long t = GUN + (long) (min * 60000); java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Taipei")); c.setTimeInMillis(t); return c.get(java.util.Calendar.HOUR_OF_DAY) + "點" + String.format(Locale.ROOT, "%02d", c.get(java.util.Calendar.MINUTE)) + "分"; }
    static String dist(double m) { return m >= 1000 ? String.format(Locale.ROOT, "%.1fK", m / 1000) : (int) (Math.round(m / 10) * 10) + " m"; }

    static void start(Context c) { Intent i = new Intent(c, GpsService.class).setAction(ACT_START); c.startForegroundService(i); }
    static void stop(Context c) { c.startService(new Intent(c, GpsService.class).setAction(ACT_STOP)); }
}
