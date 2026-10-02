# Motif

在 Pixel 上錄歌曲靈感，停止錄音後自動上傳到 Google Drive 的「Motif」資料夾。電腦上的 Google Drive 同步下來，就能直接拖進 Ableton Live。

- **開錄要快**：App 裡一個大按鈕；也可以用快速設定圖塊「Motif 錄音」，或長按 App 圖示選「開始錄音」。錄音時關螢幕、切 App 都會繼續錄。
- **不在背景耗電**：沒有同步服務，也不會輪詢。每段錄音停止時排一個一次性的上傳工作（WorkManager），等到有網路就傳，傳完就結束。兩次錄音之間 App 完全沒在跑。
- **權限最小**：Drive 只用 `drive.file` scope，App 只看得到自己建立的檔案和資料夾，看不到你 Drive 裡的其他東西。
- **音質**：48 kHz、256 kbps 立體聲 AAC（`.m4a`），Ableton 可以直接讀。預設開「原始收音」（`AudioSource.UNPROCESSED`），不做降噪和自動增益，錄樂器、哼唱比較自然；音量會比較小，需要的話可以在設定裡關掉。
- 檔名是錄音時間，例如 `2026-10-02 23-41-05.m4a`。

## 一次性設定：Google Cloud OAuth 用戶端

App 裡沒有 client secret。Google 是用「套件名稱 + 簽章 SHA-1」來認出這個 App，所以要先在 Google Cloud 建一個 Android 類型的 OAuth 用戶端。這個步驟只要做一次。

1. 到 [Google Cloud Console](https://console.cloud.google.com/) 建立一個專案，名稱隨意。
2. **APIs & Services → Library**，搜尋 **Google Drive API**，按 **Enable**。
3. **Google Auth Platform → Branding**：填 App 名稱和你的 email。
   **Audience**：User type 選 **External**，然後按 **Publish app**，切換成 *In production*。
   > 如果停在 *Testing*，授權每 7 天就會過期。`drive.file` 是非敏感 scope，發布到 production 不需要經過 Google 審查；授權時可能會看到「未經驗證」的提示，按繼續即可。
4. **Clients → Create client → Android**：
   - Package name：`app.hensuu.motif`
   - SHA-1：`E7:96:1E:53:AB:16:FE:0D:8F:D7:20:4C:0A:D3:CA:7B:CB:8D:32:D4`
     （這是 repo 內附的 `app/debug.keystore` 的指紋。debug 和 release 預設都用它簽章。）
5. 回到 App，按「Google Drive → 連結」，選帳號並同意授權。

如果之後改用自己的 release keystore（`keystore.properties`），要把那把金鑰的 SHA-1 再加成一個 Android client：

```sh
keytool -list -v -keystore your.keystore -alias your-alias | grep SHA1
```

## Build

```sh
nix develop        # JDK + Android SDK + Gradle
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

推到 GitHub 後，`.github/workflows/release.yml` 會在 push 到 `main` 時自動 build，APK 在該次 Actions run 的 artifact 裡。推 `v*` tag 則會發一個 Release。

## 電腦端（Ableton）

1. 安裝 Google Drive 桌面版，讓 `Motif` 資料夾同步到本機（*Mirror* 模式最穩；*Stream* 模式的話，對資料夾右鍵選「Available offline」）。
2. Ableton Browser → **Places → Add Folder…**，選那個資料夾。之後新的靈感就會出現在側邊欄，直接拖進 session。

## 架構

| 檔案 | 作用 |
| --- | --- |
| `RecorderService` | `microphone` 類型的前景服務，持有 `MediaRecorder`；停止錄音時排上傳工作，然後結束自己 |
| `UploadWorker` | 每段錄音一個一次性工作，有網路才執行（可設定只用 Wi-Fi），失敗會用指數退避重試 |
| `Drive` | 用 `HttpURLConnection` 呼叫 Drive v3：resumable upload（沒有 5 MB 上限），自動找到或建立 `Motif` 資料夾，token 過期時重拿一次 |
| `RecordTileService` | 快速設定圖塊。開始錄音會開啟 App，因為 Android 只允許從可見畫面啟動麥克風服務；停止可以直接在圖塊上按 |
| `MainActivity` | 錄音鍵、Drive 連結狀態、設定、錄音清單（點一下播放；長按可以分享、重新上傳、刪除） |

UI 的配色、字體和卡片樣式沿用 Pause Point。字體是 Google Sans Flex（OFL，見 `licenses/`）。
