# Instruksi untuk peninjau Google Play (Sign in details / App access)

Jawaban pertanyaan "Is any part of your app restricted?": **Yes**. Alasannya jujur: setelah masa coba 3 hari, fitur panel dikunci oleh pembelian sekali bayar (`sellby_premium`); jawaban "No" hanya untuk app tanpa konten berbayar. Tidak ada login/akun.

Peninjau Google tidak boleh memakai akun pribadi untuk membeli dan tidak boleh mengandalkan "free trial" berbasis pembayaran. Trial Sellby tidak memakai pembayaran dan terbuka penuh saat pertama dipakai, TETAPI waktu mulainya disimpan di Block Store Google (satu kali per perangkat/akun), jadi perangkat peninjau yang pernah memasang Sellby sebelumnya (misalnya untuk peninjauan akses produksi) bisa mendapati trial sudah habis. Karena itu instruksi menyertakan **kode promo** produk `sellby_premium` sebagai jalur akses kedua.

## Teks untuk kolom "Any other information required to access your app" (bahasa Inggris)

```
No sign-in or account is needed.

All features are open during a free 3-day in-app trial that starts the first time you tap a toolbar tab (Invoice, Ongkir, Status, Produk, Auto-Text) inside the keyboard. No payment is required for the trial.

How to review:
1. Install the app and open Sellby. Complete the short onboarding (enter any store name, address and phone number, then continue).
2. Enable the keyboard when prompted (Settings > System > Languages & input > On-screen keyboard) and select "Sellby Keyboard" as the active keyboard in any text field.
3. Use the toolbar above the keys to open the panels (Invoice, Ongkir, Status, Produk, Auto-Text).

After the 3-day trial the panel features are locked and a one-time in-app purchase (product id: sellby_premium) unlocks them. If the trial has already ended on your test device (for example because the same device or Google account reviewed an earlier build), redeem this promo code in Google Play: <KODE_PROMO>
Then open the keyboard's Settings (gear icon) > "Beli Premium" > "Pulihkan pembelian" (the purchase screen).

Support: x.employstudio@gmail.com
```

Ganti `<KODE_PROMO>` dengan kode yang dibuat di Monetize with Play > Promo codes (produk `sellby_premium`, masa berlaku cukup panjang). Perbarui kode ini bila kedaluwarsa.

## Versi singkat (kolom dibatasi 500 karakter)

Sejak build 5004 tombol beli ada di Settings keyboard (ikon gerigi > Beli Premium), bukan di Tentang & Lisensi.

```
No sign-in needed. All features are open during a free 3-day in-app trial (starts when you first tap a keyboard toolbar tab), no payment. Install, open Sellby, finish onboarding (any store info), enable and select Sellby Keyboard (Settings > System > Languages & input), then use the toolbar panels. If the trial already ended on your device, redeem promo code <KODE_PROMO> in Google Play, then keyboard Settings (gear) > Beli Premium > Pulihkan pembelian.
```

Ganti `<KODE_PROMO>` dengan `KODE1 or KODE2`. Nama set instruksi: `No login required`. Kotak "full access" dicentang.
