# Inventaris fitur/file bawaan HeliBoard yang tidak dipakai Sellby

Dibuat dari inspeksi statis (struktur proyek + rujukan kode), BUKAN dari analisis pemakaian saat build.
Setiap kelompok harus diverifikasi dengan build + uji sebelum/ sesudah dihapus.

**Aturan proyek yang membatasi penghapusan:** `LatinIME.java` tidak boleh diedit. Kolom "Terkunci"
di bawah = komponen yang dirujuk langsung oleh `LatinIME.java`; menghapusnya butuh melonggarkan aturan
itu atau menyisakan stub.

## Ringkasan

| # | Kelompok | Ukuran kira-kira | Terkunci `LatinIME`? | Status |
|---|---|---|---|---|
| H | File non-kode milik HeliBoard | ±8 MB | Tidak | **SELESAI (gelombang 1, build OK)** |
| E | Terjemahan stok (`res/values-<bahasa>`) | ±3,8 MB | Tidak | **SELESAI (gelombang 1, build OK)** |
| E | Subtype bahasa (`method.xml`), layout bahasa lain, combiner khusus bahasa | ±1 MB | Tidak (tapi `method.xml` harus dirapikan bersamaan) | Belum |
| F | Tema & gaya bawaan | kecil | Tidak (tapi perlu edit `KeyboardTheme.kt`) | Belum |
| G | Fitur tanpa akses UI di Sellby | kecil | Sebagian | Belum |
| A | UI Pengaturan bawaan (`settings/`) | 341 KB | Sebagian | Belum |
| B | Strip saran kata & toolbar bawaan | 89 KB + layout | Ya | Belum |
| C | Mesin prediksi, koreksi, kamus | ±32 MB aset + kode + native 2 MB | Ya | Belum |
| D | Ketik geser (glide) & pengumpul data gestur | kecil | Ya | Belum |

## Gelombang 1 — SUDAH DIHAPUS, `assembleDebug` BUILD SUCCESSFUL (paling aman: tidak ada rujukan dari build/kode)

Penghapusan ini sudah masuk commit `81f10dd`. Untuk mengembalikan salah satu file/folder:
`git checkout 81f10dd~1 -- <path>`.

Semua file di bawah tercatat di git dan belum pernah dimodifikasi, jadi bisa dikembalikan:
`git checkout -- <path>` (atau `git restore`).

- `CONTRIBUTING.md`, `AI_USAGE.md`, `layouts.md` — dokumentasi HeliBoard. (`README.md` SENGAJA dipertahankan:
  ganti dengan README Sellby sendiri nanti.)
- `.github/` — template issue/PR/funding & workflow CI HeliBoard.
- `fastlane/` — metadata toko F-Droid HeliBoard (±3,6 MB, 618 file).
- `art/` — sumber ikon launcher HeliBoard (ikon Sellby sudah dibangun terpisah ke `mipmap-*`).
- `tools/` — skrip pemeliharaan HeliBoard (`diacritics.py`, `make-emoji-keys`, `release.py`). Modul
  `:tools:make-emoji-keys` sudah dinonaktifkan di `settings.gradle`; komentar yang merujuknya ikut dibersihkan.
- `app/src/main/jni/`: `Android.bp`, `HostUnitTests.mk`, `TargetUnitTests.mk`, folder `tests/` —
  build AOSP & unit test native. `Android.mk` sudah meng-comment-out include `HostUnitTests.mk` dan
  `TargetUnitTests.mk`, jadi tidak ikut build.
  (`run-tests.sh` TIDAK dihapus: ter-ignore git (`*.sh`) sehingga tidak bisa dipulihkan dari git.)
- `app/src/main/res/values-<bahasa>` — terjemahan stok HeliBoard. DIPERTAHANKAN: `values-in` (Indonesia),
  `values-en`, dan semua folder bukan-bahasa (`land`, `night`, `night-v31`, `sw430dp`, `sw600dp`,
  `sw600dp-land`, `sw768dp`, `sw768dp-land`, `v28`, `v31`). `res/xml/locales_config.xml` dipangkas jadi `en` + `in`.

## Detail kelompok yang belum dihapus

### A. UI Pengaturan bawaan (`settings/`)
- Isi: `SettingsActivity`, `SettingsActivity2`, `SettingsNavHost`, 10 layar (Main, Appearance, Preferences,
  TextCorrection, Toolbar, SecondaryLayout, PersonalDictionaries/Dictionary, Advanced, About), 9 preferences,
  13 dialog, `SearchScreen`, `WelcomeWizard.kt` (wizard bawaan; Sellby punya sendiri), `Preview.kt`, `Misc.kt`.
- Masih terjangkau lewat: tombol "Settings" di Pengaturan Android → Keyboard → Sellby (`method.xml`
  `settingsActivity`), ikon gear di popup tombol koma (`TextKeyData.kt`), membuka file `.dict`.
- JANGAN dihapus: `FilePicker.kt` (dipakai export Excel).
- Keterkaitan: `LatinIME` mengimpor `SettingsActivity2`; `KeyboardTheme.kt` memakai
  `SettingsActivity.forceNight/forceTheme`; `SystemBroadcastReceiver` memakai `SettingsActivity`;
  `DictionaryUtils.kt` & `GestureDataGatheringSettings.kt` memakai dialog dari paket ini.

### B. Strip saran kata & toolbar bawaan
- `latin/suggestions/` (5 file), layout `suggestions_strip.xml`, `suggestion_divider.xml`, `more_suggestions.xml`,
  `kbd_suggestions_pane_template.xml`, toolbar bawaan (`ToolbarUtils`, `ToolbarMode`, tombol toolbar,
  toolbar clipboard lama). Sudah digantikan toolbar 6 tab Sellby.
- JANGAN hapus `strip_container.xml` utuh: `calculator_strip` di dalamnya dipakai Kalkulator.
- `LatinIME` mengimpor `SuggestionStripView`.

### C. Mesin prediksi, koreksi, kamus (dimatikan lewat preferensi, kode+aset masih ada)
- Kode: `latin/dictionary/` (10), `latin/makedict/` (7), `DictionaryFacilitator*`, `SingleDictionaryFacilitator`,
  `Suggest`, `SuggestedWords`, `personalization/`, `AutoCorrectionUtils`, `SuggestionResults`; kamus kontak/aplikasi/
  pengguna (`ContactsBinaryDictionary`, `ContactsManager`, `ContactsContentObserver`, `AppsBinaryDictionary`,
  `AppsManager`, `UserBinaryDictionary`, `UserHistoryDictionary`); `dictionarypack`,
  `DictionaryPackInstallBroadcastReceiver`, `DictionaryDumpBroadcastReceiver`, `PermissionsUtil`.
- Manifest: izin `READ_CONTACTS`, `READ_USER_DICTIONARY`, `WRITE_USER_DICTIONARY`, blok `<queries>`.
  **Sudah dibereskan di Batch 2 rilis (Okt 2026):** `READ_USER_DICTIONARY` dan `WRITE_USER_DICTIONARY` dibuang, `<queries>` kini hanya `android.view.InputMethod`; `READ_CONTACTS` sengaja DIPERTAHANKAN (dipakai tombol kontak di panel Invoice, diminta sekali agar picker konsisten di semua merek HP);
  kamus pengguna sistem tidak lagi dimuat (`DictionaryFacilitatorImpl`).
- Aset: `assets/dicts` (18 kamus, ±32 MB, TANPA kamus Indonesia), `known_dict_hashes.txt`,
  `dictionaries_in_dict_repo.csv`.
- Native: `jni/` (±2 MB). Hati-hati: `ProximityInfo` juga dipakai koreksi sentuhan & ketik geser.
- Terkunci: `LatinIME` memakai `DictionaryFacilitator`, `Suggest`, `PersonalizationHelper`, dll.

### D. Ketik geser (glide) & pengumpul data gestur
- `GestureConsumer`, `GestureDataGathering*`, `GestureDataDao`, `debug/…/gesture_data.xml`.
- Default masih nyala (`PREF_GESTURE_INPUT = true`) tapi bergantung kamus+native; tanpa kamus Indonesia
  kemungkinan besar tidak berfungsi untuk bahasa Indonesia. Perlu keputusan: pertahankan atau tidak.

### E (sisa). Bahasa & layout non-Indonesia
- `method.xml`: 130 subtype (relevan hanya `in` dan `en`); `locales_config.xml` (dipangkas di gelombang 1).
- `assets/layouts/main`: 82 layout bahasa (dipakai: `qwerty.txt`, plus simbol/numpad/phone); `locale_key_texts`
  (103 KB); `bn-khipro.mim`.
- Kode khusus bahasa: `HangulCombiner`, `HangulEventDecoder`, `BnKhiproCombiner`, `DeadKeyCombiner`,
  `KoreanDictionary`, `LayoutUtilsCustom`.
- PERINGATAN: hapus layout hanya bersamaan dengan merapikan `method.xml`, kalau tidak berisiko crash saat memilih subtype.

### F. Tema & gaya bawaan
- 15 tema warna selain light/dark (`HOLO_WHITE`, `DARKER`, `BLACK`, `DYNAMIC`, `BLUE_GRAY`, `BROWN`, `CHOCOLATE`,
  `CLOUDY`, `FOREST`, `INDIGO`, `OCEAN`, `PINK`, `SAND`, `VIOLETTE`); gaya Holo & Rounded
  (`themes-holo_base.xml`, `themes-rounded-base*.xml`, ikon `_holo`/`_rounded`). Sellby hanya Material.

### G. Fitur tanpa akses UI di Sellby
- Mode satu tangan (`btn_*_one_handed_mode` di `main_keyboard_frame.xml`), keyboard melayang
  (`float_handle_container`, `FloatingKeyboardUtils`, `FoldableUtils`), autofill inline (`InlineAutofillUtils`,
  `supportsInlineSuggestions` di `method.xml`), `PrivateCommandPerformer`, `AppUpgrade.kt`, `StatsUtils*`,
  `DebugFlags`/`DebugSettings`/`ProductionFlags`/`DebugLogUtils`.

### Lain-lain yang belum dipilah
- `app/src/test` (14 unit test HeliBoard; banyak mengacu fitur di atas, mis. `SuggestTest`, `SpellCheckerTest`;
  sebagian mungkin tetap berguna sbg regresi mesin keyboard: `KeyboardParserTest`, `InputLogicTest`).
- `app/src/debug*` (`gesture_data.xml`, terkait D).
- `README.md` (HeliBoard) — ganti dengan README Sellby.

## Yang kelihatan bawaan tapi DIPAKAI Sellby (jangan dihapus)
- `emoji/` dan `EmojiSearchActivity`; `clipboard/`, `ClipboardHistoryManager`, `ClipboardDao`, provider clipboard.
- Mesin keyboard: `KeyboardView`, `Key`, `PointerTracker`, `InputLogic`, `RichInputConnection`, inti `event/`.
- Layout `qwerty`, `symbols`, `numpad`, `phone`, `number`, `functional`, `emoji_bottom`, `clipboard_bottom`.
- `Settings`/`SettingsValues`/`Defaults` (toggle di panel Sellby), `Colors.kt`, `AudioAndHapticFeedbackManager`.
- `accessibility/` (TalkBack) — disarankan dipertahankan.
- `LICENSE*` — WAJIB dipertahankan (turunan GPL-3.0, Apache-2.0 untuk bagian AOSP, CC-BY-SA untuk sebagian aset).
