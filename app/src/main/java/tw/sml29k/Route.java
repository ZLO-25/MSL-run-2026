package tw.sml29k;

import org.json.JSONArray;
import org.json.JSONObject;

/** 路線幾何、配速計畫、地標。與 assets/index.html 內的 JS 邏輯一致（JS 只負責畫面）。 */
final class Route {
    static final int N = RouteData.ELEV.length;
    static final double TOTAL = (N - 1) * 100.0;           // 28 800 m
    private static final double LAT0 = 23.85, LON0 = 120.92;
    private static final double CX = Math.cos(Math.toRadians(LAT0));
    static final double[] PX = new double[N], PY = new double[N], ES = new double[N];
    static {
        for (int i = 0; i < N; i++) { PX[i] = (RouteData.LON[i] - LON0) * CX * 111320; PY[i] = (RouteData.LAT[i] - LAT0) * 110574; }
        for (int i = 0; i < N; i++) { double s = 0; for (int k = -2; k <= 2; k++) s += RouteData.ELEV[Math.max(0, Math.min(N - 1, i + k))]; ES[i] = s / 5; }
    }
    static double toX(double lon) { return (lon - LON0) * CX * 111320; }
    static double toY(double lat) { return (lat - LAT0) * 110574; }

    static double elev(double s) {
        s = Math.max(0, Math.min(TOTAL, s));
        int i = (int) Math.floor(s / 100); double t = s / 100 - i;
        double a = ES[i], b = ES[Math.min(N - 1, i + 1)];
        return a + (b - a) * t;
    }
    static double grade(double s) {
        double a = Math.max(0, s - 150), b = Math.min(TOTAL, s + 150);
        return (elev(b) - elev(a)) / (b - a) * 100;
    }
    /** 在路線片段 [i0,i1] 中找離 (x,y) 最近的點；回傳 {距離 m, 路線里程 m} */
    static double[] best(double x, double y, int i0, int i1) {
        double bd = 1e9, bs = 0;
        for (int i = Math.max(0, i0); i <= Math.min(N - 2, i1); i++) {
            double ax = PX[i], ay = PY[i], dx = PX[i + 1] - ax, dy = PY[i + 1] - ay, L2 = dx * dx + dy * dy;
            double t = L2 > 0 ? ((x - ax) * dx + (y - ay) * dy) / L2 : 0; t = Math.max(0, Math.min(1, t));
            double d = Math.hypot(x - (ax + t * dx), y - (ay + t * dy));
            if (d < bd) { bd = d; bs = (i + t) * 100; }
        }
        return new double[]{bd, bs};
    }

    // ---- 地標（手冊編號） ----
    static final String[] LM_N = {"起", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "14", "終"};
    static final String[] LM_NAME = {"向山起點", "水社", "文武廟", "水蛙頭", "纜車站", "土亭仔", "玄奘寺", "玄光寺", "三隻魚", "長型地", "哇拉蜜冰店", "頭社派出所", "大舌滿", "向山終點"};
    static final double[] LM_KM = {0, 3.0, 5.8, 9.7, 11.2, 14.3, 15.8, 18.2, 20.4, 21.6, 24.4, 25.8, 27.5, 28.8};
    static final double[] GEL_KM = {4.5, 10, 14, 19, 24.4};
    static final String[] GEL_T = {"第一包", "第二包", "第三包", "第四包", "第五包"};
    // 關門：里程, 鳴槍後分鐘
    static final double[] CUT_KM = {11.2, 25.8, 28.8};
    static final double[] CUT_MIN = {145, 234, 270};
    static final String[] CUT_NAME = {"纜車站", "頭社", "終點"};

    // ---- 配速計畫（4:15 基準，依目標時間等比縮放） ----
    static final double[][] ROWS = {
        {0, 5.0, 8.0}, {5.0, 6.2, 9.75}, {6.2, 11.2, 8.5}, {11.2, 14.3, 8.5}, {14.3, 16.7, 10.25}, {16.7, 18.2, 8.25},
        {18.2, 22.2, 8.5}, {22.2, 25.2, 8.0}, {25.2, 25.8, 8.5}, {25.8, 27.5, 10.75}, {27.5, 28.3, 13.0}, {28.3, 29.0, 8.0}};
    static final double BASE_TOTAL;
    static { double s = 0; for (double[] r : ROWS) s += (r[1] - r[0]) * r[2]; BASE_TOTAL = s; }
    /** 依目標分鐘數回傳：到達 km 的計畫分鐘 */
    static double planMin(double km, double targetMin) {
        double sc = targetMin / BASE_TOTAL, cum = 0;
        for (double[] r : ROWS) {
            if (km <= r[1]) return cum + (Math.max(km, r[0]) - r[0]) * r[2] * sc;
            cum += (r[1] - r[0]) * r[2] * sc;
        }
        return cum;
    }
    static double planPace(double km, double targetMin) {
        double sc = targetMin / BASE_TOTAL;
        for (double[] r : ROWS) if (km < r[1]) return r[2] * sc;
        return ROWS[ROWS.length - 1][2] * sc;
    }
    static int nextLandmark(double s) { for (int i = 0; i < LM_KM.length; i++) if (LM_KM[i] * 1000 > s + 20) return i; return -1; }
}
