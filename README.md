# 日月潭29K 賽道定位器（Android APK）

WebView 外殼 + 前景定位服務：**鎖屏也持續 GPS、震動 + 語音提醒、通知列顯示里程與快慢**。
支援 Android 10 以上（Pixel 7 / 8 Pro 皆可）。

## 取得 APK（GitHub 自動編譯，不用裝 Android Studio）
1. 把這個資料夾的全部內容推上 GitHub `main` 分支（含 `.github/workflows/build.yml`）。簽章金鑰由 Actions 編譯時自動產生並快取，不放在 repo 裡。
2. 推上去後到 repo 的 **Actions** 分頁，等 `Build APK` 跑完（約 3–5 分鐘）。
3. 到 **Releases** → `日月潭29K 最新版` → 下載 `日月潭29K.apk`
   （或在 Actions 該次執行頁的 Artifacts 下載）。
4. 把 APK 傳到手機（雲端、LINE 都可）→ 點開 → 允許「安裝未知的應用程式」→ 安裝。

之後改程式再推一次，Release 會自動換成新版 APK；同一把金鑰簽章，可直接覆蓋安裝。

## 手機上第一次開啟
1. 按「開始定位」→ 位置權限選 **「使用應用程式時」**，之後到系統設定改成 **「一律允許」**（鎖屏定位需要）。
2. 允許「通知」（通知列會顯示里程與下一個地標）。
3. 跳出「忽略電池最佳化」就按允許——否則 Pixel 可能在背景把定位停掉。
4. 設定裡按「測試語音」：如果沒聲音，到 系統設定 → 文字轉語音 → 下載中文（台灣）語音。
5. 到戶外走 2 分鐘，確認里程會動、狀態變綠。

## 比賽當天
- 鳴槍前按「開始定位」，手機放口袋、螢幕關掉。提醒靠震動與語音。
- 想看畫面：點亮螢幕 → 通知列點進去。
- 跑完按通知列的「停止定位」。「分段紀錄」可複製。

## 專案結構
```
app/src/main/java/tw/sml29k/
  RouteData.java   官方路線每 100 m 的經緯度與海拔（腳本產生）
  Route.java       路線對位、坡度、配速計畫、地標
  GpsService.java  前景服務：GPS → 里程、提醒、分段、通知列
  MainActivity.java WebView + JS 橋接 + 權限流程
app/src/main/assets/index.html  畫面（與 GitHub Pages 網頁版同一份）
app/src/main/assets/map.png     路線地圖
```
