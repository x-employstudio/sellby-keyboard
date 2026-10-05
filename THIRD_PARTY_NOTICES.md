# Pemberitahuan pihak ketiga / Third-party notices

Sellby Keyboard (c) pemilik proyek Sellby, dilisensikan di bawah **GNU GPL v3.0** ([LICENSE](LICENSE)). Sellby adalah turunan HeliBoard; dokumen ini mencatat karya pihak ketiga yang ikut dalam kode atau aplikasi dan lisensinya. Pemberitahuan hak cipta di header tiap file sumber (baris `SPDX-License-Identifier` dan `Copyright`) tetap berlaku dan tidak dihapus.

## Basis kode

| Karya | Lisensi | Keterangan |
|---|---|---|
| [HeliBoard](https://github.com/HeliBorg/HeliBoard) (c) Helium314 dan kontributor | GPL-3.0-only | Basis seluruh keyboard. Kode Sellby (`app/src/main/java/helium314/keyboard/sellby/` dan perubahan pada file HeliBoard) mengikuti lisensi yang sama. |
| [OpenBoard](https://github.com/openboard-team/openboard) | GPL-3.0 | Dasar HeliBoard. |
| AOSP Keyboard / LatinIME (c) The Android Open Source Project | Apache-2.0 ([LICENSE-Apache-2.0](LICENSE-Apache-2.0)) | File bertanda `Apache-2.0` atau `Apache-2.0 AND GPL-3.0-only` (diubah). Termasuk kode native di `app/src/main/jni/`. |
| [FlorisBoard](https://github.com/florisboard/florisboard) (c) Patrick Goldinger | Apache-2.0 | Parser layout di `keyboard/internal/keyboard_parser/floris/` (diubah). |
| LineageOS LatinIME, [Simple Keyboard](https://github.com/rkkr/simple-keyboard), [Indic Keyboard](https://gitlab.com/indicproject/indic-keyboard) | Apache-2.0 / GPL (lihat upstream) | Perubahan yang diteruskan lewat HeliBoard/OpenBoard. |
| Pebble (potongan di `latin/common/StringUtils.kt`) | Apache-2.0 | Sumber dicatat di komentar file. |
| Adaptasi Kotlin stdlib (`latin/utils/Ktx.kt`) | Apache-2.0 | |
| Regex emoji dari [mathiasbynens/emoji-test-regex-pattern](https://github.com/mathiasbynens/emoji-test-regex-pattern) (`latin/common/Emoji.kt`) | MIT | |
| `assets/layouts/.../bn-khipro.mim` (c) 2024 rank_coder | MIT | Pemberitahuan lisensi ada di dalam file. |
| Daftar emoji di `assets/emoji/` | Unicode License V3 | Teks daftar emoji; app memakai font emoji sistem. |

## Pustaka yang ikut dikemas

AndroidX (core, recyclerview, viewpager2, fragment, autofill), Jetpack Compose (BOM), Navigation, Room, kotlinx.serialization, `sh.calvin.reorderable`: **Apache-2.0**. `desugar_jdk_libs`: GPL-2.0 dengan Classpath Exception. Google Play Billing (beserta Google Data Transport yang ikut di dalamnya), Google Play services Block Store, dan Play In-App Review: SDK Google di bawah ketentuan layanan Google Play dan Android SDK. (Play Integrity baru akan ikut bila fitur server anti-reset trial dirilis.) Daftar versi persisnya ada di `app/build.gradle.kts`.

## Kamus kata (word list)

Berkas `assets/dicts/main_*.dict` berasal dari proyek HeliBoard / [aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries) dan memiliki lisensi yang berbeda per bahasa (mis. Apache-2.0 untuk turunan AOSP, CC BY 4.0, CC BY-SA 4.0, LGPL, GPL, dan sebagian CC BY-NC). Karena beragam, dan tidak satu pun relevan untuk bahasa Indonesia, **build rilis Sellby tidak mengemas kamus tersebut** (saran kata dimatikan secara bawaan). Kode sumber repo ini masih memuat berkas-berkasnya; lisensi dan atribusi tiap kamus ada di repositori upstream di atas.

## Ikon, gambar, suara

- Ikon vektor bawaan HeliBoard di `res/drawable` (sebagian dari [Pictogrammers / Material Design Icons](https://pictogrammers.com), Apache-2.0, dan sumber lain yang dicatat di komentar file masing-masing).
- Ikon, ilustrasi, dan maskot Sellby (`ic_*_sellby*`, `sellby_*`): karya pemilik proyek; **nama, logo, dan maskot tidak dilisensikan untuk dipakai ulang** oleh versi turunan.
- Logo dan nama bank, e-wallet, kurir, serta aplikasi chat (WhatsApp, WhatsApp Business, Telegram, Instagram) yang tampil di app dipakai hanya untuk mengidentifikasi layanan terkait. Semua merek milik pemiliknya masing-masing; Sellby tidak berafiliasi dengan mereka.
- `res/raw/notification_1.mp3` (bunyi notifikasi tutorial): dikonfirmasi pemilik proyek (Oktober 2026) berlisensi bebas (free license); URL sumber asli belum tercatat, tambahkan bila ditemukan.
- Font: tidak ada font yang dikemas (huruf memakai font sistem).

## Cara mendapatkan kode sumber

Kode sumber lengkap dari setiap rilis tersedia di repositori publik proyek (tautan juga ada di layar "Tentang & Lisensi" di aplikasi): <https://github.com/x-employstudio/sellby-keyboard>.
