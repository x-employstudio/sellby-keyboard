# Cara membuat build rilis Sellby Keyboard

Dokumen kerja untuk rilis ke Google Play (rencana lengkap ada di plan rilis). Kunci tanda tangan **tidak pernah** masuk repo.

## 1. Sekali saja: buat upload keystore (dilakukan pemilik app)

Jalankan di PowerShell (jawab pertanyaan nama/organisasi, lalu isi **password** sendiri, jangan dibagikan ke siapa pun, termasuk ke Claude):

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -storetype PKCS12 `
  -keystore "D:\secure\sellby-upload.jks" -alias sellby-upload -keyalg RSA -keysize 4096 -validity 9125
```

- Simpan `sellby-upload.jks` di folder **di luar repo** dan **cadangkan** (flashdisk offline + password manager). Password disimpan di password manager.
- Dengan Play App Signing, kunci ini hanya "kunci upload": kalau hilang, Google bisa meresetnya lewat dukungan Play Console, tapi prosesnya lambat. Kunci penanda tangan sebenarnya dipegang Google.
- Salin `keystore.properties.example` menjadi `keystore.properties` (sudah di-gitignore) dan isi 4 barisnya.

## 2. Build

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest          # gerbang tes
.\gradlew.bat lintRelease                # gerbang lint
.\gradlew.bat assembleRelease            # APK rilis  -> app\build\outputs\apk\release\
.\gradlew.bat bundleRelease              # AAB untuk Play -> app\build\outputs\bundle\release\
powershell -ExecutionPolicy Bypass -File scripts\check-release.ps1   # gerbang artefak (izin, tanda tangan, 16 KB, R8)
```

- Tanpa `keystore.properties`, hasilnya **tidak bertanda tangan**. Untuk menguji build rilis di HP sendiri tanpa kunci upload:
  `.\gradlew.bat assembleRelease -PlocalReleaseSign` (ditandatangani kunci debug; jalankan skrip dengan `-AllowDebugSigned`; **jangan pernah diunggah**).
- Versi: `versionName` / `versionCode` ada di `app/build.gradle.kts`. `versionCode` harus selalu naik (dimulai 5001; angka turunan HeliBoard sampai 4101).
- Build rilis tidak pernah memuat library native buatan pengguna (hanya build debug yang boleh).
- Simbol native ikut di AAB (`ndk.debugSymbolLevel = FULL`) agar crash native terbaca di Play Console.

## 3. Sebelum unggah

Checklist ada di bagian "Verifikasi akhir" plan rilis. Minimal: tes hijau, lint bersih, `check-release.ps1` lulus pada AAB yang akan diunggah, repo publik sudah memuat source yang sama (tag `v<versionName>`).
