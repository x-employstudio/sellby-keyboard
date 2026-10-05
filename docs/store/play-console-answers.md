# Draf jawaban formulir Play Console (konten aplikasi)

Untuk dipakai saat Batch 5 (kamu mengisi di Console, saya memandu langkah demi langkah). Jawaban mengikuti kode versi **1.0.x** (build dengan Play Billing, tanpa server). Butir bertanda **[KONFIRMASI]** harus dicocokkan dengan pertanyaan sebenarnya di formulir, karena Google dapat mengubah susunannya; jangan ragu mengirim screenshot pertanyaannya.

## Izin yang dideklarasikan di manifest (harus konsisten dengan semua jawaban di bawah)

| Izin | Asal | Alasan |
|---|---|---|
| `READ_CONTACTS` | Sellby | Tombol kontak di panel Invoice; diminta sekali; hanya kontak yang diketuk dibaca |
| `VIBRATE` | HeliBoard | Getaran tombol |
| `RECEIVE_BOOT_COMPLETED` | HeliBoard | Housekeeping keyboard setelah boot/pembaruan |
| `com.android.vending.BILLING` | Play Billing | Pembelian dalam aplikasi |
| `INTERNET`, `ACCESS_NETWORK_STATE` | library Play Billing (data transport Google) | Logging bawaan library; keyboard tidak memakainya untuk mengirim ketikan |

## Kebijakan privasi
URL: `https://sellby-keyboard.web.app/privacy.html` (sudah online sejak 5 Okt 2026; sumbernya `docs/privacy.html`, diperbarui dengan `firebase deploy --only hosting`). Versi Inggris: `/privacy-en.html`; dukungan: `/support.html`.

## Keamanan data (Data safety)

- **Apakah aplikasimu mengumpulkan atau membagikan jenis data pengguna yang wajib?** Rancangan: **Tidak ada data yang dikumpulkan atau dibagikan ke developer/pihak ketiga oleh aplikasi** - ketikan, data toko, kontak yang dipilih diproses di perangkat saja; tidak ada server milik kami. **[KONFIRMASI]** apakah Block Store (satu angka waktu mulai masa coba, disimpan oleh Google Play services) dan logging library Play Billing perlu dideklarasikan; panduan Google untuk SDK/layanan Google berubah-ubah. Bila Console meminta, jawabannya: data tidak dibagikan ke pihak lain, dienkripsi dalam pengiriman, tidak bisa diminta dihapus lewat kami karena disimpan oleh Google.
- **Praktik keamanan:** data dienkripsi dalam pengiriman = ya untuk layanan Google; cara meminta penghapusan data = hapus lewat fitur Hapus Semua Data / copot pemasangan (tidak ada data di server kami).
- Jangan menyatakan "tidak ada pengumpulan data" jika setelah konfirmasi ternyata Block Store dihitung sebagai pengumpulan; konsistensi dengan kebijakan privasi lebih penting daripada jawaban terpendek.

## Deklarasi lain

| Pertanyaan | Jawaban |
|---|---|
| Aplikasi atau game | Aplikasi |
| Gratis atau berbayar | **Gratis** (dengan pembelian dalam aplikasi; tidak bisa diubah ke berbayar nanti) |
| Kategori | Bisnis |
| Iklan | Tidak ada iklan |
| Akses aplikasi | Semua fungsi tersedia tanpa login atau akun |
| Rating konten (IARC) | Isi kuesioner jujur: tanpa kekerasan, seksual, judi, dll. (perkiraan: Semua umur/3+); kategori "utilitas/produktivitas" |
| Target audiens | **16 tahun ke atas** (pilihan user: 16-17 dan 18+; bukan anak-anak); bukan aplikasi untuk keluarga |
| Fitur keuangan | Aplikasi tidak memproses pembayaran, tidak menyimpan dana, tidak memberi pinjaman; hanya menyusun teks tagihan. Jawab "tidak" untuk pinjaman pribadi/dompet/perdagangan kripto, dst. |
| Aplikasi berita/kesehatan/pemerintah/COVID | Tidak |
| ID iklan (Advertising ID) | Tidak dipakai |
| Izin latar depan / lokasi / SMS / log panggilan | Tidak dipakai |
| Keyboard (IME) | Aplikasi adalah keyboard (layanan IME); tidak mengirim teks yang diketik keluar perangkat |

## Pengecekan sebelum mengirim ke produksi
- Daftar izin di tabel atas = hasil `scripts/check-release.ps1` pada AAB yang diunggah.
- Kebijakan privasi menyebut: ketikan di perangkat saja, data lokal, Auto Backup Google, izin Kontak, Billing/Block Store/In-App Review. (Sudah dalam `docs/privacy.html`.)
- Saat Batch 6 (server anti-reset trial) dirilis: perbarui kebijakan privasi, Data safety (kemungkinan "Device or other IDs" untuk pencegahan penipuan) dan manifest SEBELUM versi itu diunggah.
