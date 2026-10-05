# Sellby Keyboard

**Keyboard bisnis untuk seller online.** Sellby adalah keyboard Android yang membantu penjual online membuat invoice, mengecek ongkir, mengelola status pesanan, produk, dan balasan cepat langsung dari keyboard, tanpa berpindah-pindah aplikasi saat melayani pembeli di WhatsApp, Telegram, atau Instagram.

*Sellby is an Android keyboard for online sellers: build invoices, check shipping, track order status, manage products and quick replies without leaving the chat app.*

> Sellby adalah **turunan (fork) dari [HeliBoard](https://github.com/HeliBorg/HeliBoard)**, yang sendiri berbasis [OpenBoard](https://github.com/openboard-team/openboard) dan AOSP LatinIME. Seluruh kode di repo ini, termasuk bagian khusus Sellby, dirilis di bawah **GNU GPL v3.0** (lihat [Lisensi](#lisensi)).

## Fitur

Panel di toolbar keyboard:

- **Invoice** - susun teks tagihan dari data pelanggan, produk (katalog atau input manual), ekspedisi, ongkir, dan metode pembayaran (termasuk foto QRIS), lalu masukkan ke kolom chat. Form Order yang disalin dari chat pembeli bisa langsung ditempel ke invoice.
- **Ongkir** - daftar kurir dengan jalan pintas ke aplikasi/situs cek ongkir.
- **Status** - pesanan Pending / Lunas / Proses / Selesai, dengan pesan status siap kirim per channel (WhatsApp, WhatsApp Business, Telegram, Instagram).
- **Produk** - katalog produk, stok, diskon, variasi; kirim info produk ke chat.
- **Auto-Text** - pesan cepat dengan shortcut dan saran saat mengetik.
- Kalkulator, pengaturan ukuran keyboard, mode gelap, dan **aplikasi pendamping** (dashboard ringkasan penjualan, ekspor Excel, tutorial interaktif).

Keyboard memproses teks yang diketik **hanya di perangkat**. Data pelanggan, pesanan, produk, dan rekening disimpan lokal di perangkat.

## Harga

Kode sumbernya gratis dan terbuka. Aplikasi siap pakai di **Google Play** gratis dipakai 3 hari dengan semua fitur, setelah itu fitur panel dibuka dengan sekali bayar lewat Google Play (tidak berlangganan). Siapa pun boleh membangun sendiri dari source ini sesuai lisensi GPL.

## Membangun sendiri

```powershell
git clone https://github.com/x-employstudio/sellby-keyboard.git
.\gradlew.bat assembleDebug        # APK debug (applicationId com.sellby.keyboard.debug)
.\gradlew.bat testDebugUnitTest    # tes unit
```

Build rilis dan penandatanganan: lihat [docs/RELEASE.md](docs/RELEASE.md). Kunci tanda tangan resmi dipegang pemilik proyek; build buatan sendiri harus memakai kunci sendiri.

## Lisensi

Sellby (sebagai turunan HeliBoard / OpenBoard) dilisensikan di bawah **GNU General Public License v3.0** - lihat [LICENSE](LICENSE).

> Izin dalam lisensi copyleft kuat ini bersyarat pada penyediaan kode sumber lengkap dari karya berlisensi dan modifikasinya, termasuk karya yang lebih besar yang memakai karya berlisensi, dengan lisensi yang sama. Pemberitahuan hak cipta dan lisensi harus dipertahankan.

- Sebagian kode berasal dari AOSP Keyboard (Apache 2.0): lihat [LICENSE-Apache-2.0](LICENSE-Apache-2.0). Kode turunan tetap dilisensikan GPL-3.0 sebagai keseluruhan.
- Daftar pihak ketiga, atribusi kamus, dan pemberitahuan lainnya: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- [LICENSE-CC-BY-SA-4.0](LICENSE-CC-BY-SA-4.0) adalah bawaan HeliBoard untuk ikon HeliBoard, yang **tidak lagi dipakai** oleh Sellby.
- **Nama, logo, dan maskot "Sellby" tidak dilisensikan untuk dipakai ulang.** Versi turunan harus memakai nama dan ikon lain.
- Logo dan nama bank, e-wallet, kurir, dan aplikasi chat yang tampil di app adalah milik masing-masing pemiliknya; Sellby tidak berafiliasi dengan mereka.

## Kredit

Sellby berdiri di atas pekerjaan banyak orang:

- [HeliBoard](https://github.com/HeliBorg/HeliBoard) dan [kontributornya](https://github.com/HeliBorg/HeliBoard/graphs/contributors)
- [OpenBoard](https://github.com/openboard-team/openboard)
- [AOSP Keyboard (LatinIME)](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/)
- [LineageOS](https://review.lineageos.org/admin/repos/LineageOS/android_packages_inputmethods_LatinIME), [Simple Keyboard](https://github.com/rkkr/simple-keyboard), [Indic Keyboard](https://gitlab.com/indicproject/indic-keyboard)
- [FlorisBoard](https://github.com/florisboard/florisboard/) (parser layout)

## Kontak

Dukungan: x.employstudio@gmail.com
