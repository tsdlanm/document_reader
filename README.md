# Document Reader

Aplikasi Android yang sangat ringan untuk **membaca** dokumen. Ukurannya sekitar 150 KB dan bisa dipakai tanpa internet. Aplikasi ini dibuat supaya tetap lancar di HP spesifikasi rendah.

## Cara mengunduh dan memasang

1. Buka halaman rilis: **[github.com/tsdlanm/document_reader/releases/latest](https://github.com/tsdlanm/document_reader/releases/latest)**
2. Di bagian **Assets**, ketuk file **PembacaDokumen-v1.0.apk** (nama versinya mengikuti rilis terbaru) untuk mengunduhnya.
3. Buka file yang sudah diunduh, lalu ketuk **Pasang** (*Install*).
4. Kalau HP menolak dengan pesan *instal dari sumber tidak dikenal*, ketuk **Pengaturan** pada pesan itu dan izinkan untuk browser atau pengelola file yang Anda pakai. Izin ini bisa dicabut lagi setelah selesai.

**Memperbarui:** unduh APK versi terbaru dengan cara yang sama lalu pasang di atas aplikasi yang lama. Daftar file terbaru tidak hilang.

**Catatan:** APK ini ditandatangani dengan kunci pengembangan. Kalau suatu saat rilis berikutnya memakai kunci resmi, aplikasi lama perlu dihapus dulu sebelum memasang versi baru.

## File yang bisa dibuka

| Jenis | Format |
|---|---|
| PDF | `.pdf` |
| Word | `.docx` (ditampilkan per halaman seperti di Word) |
| Excel | `.xlsx` |
| PowerPoint | `.pptx` (slide ditampilkan berurutan ke bawah) |

Format lama `.doc`, `.xls`, dan `.ppt` belum bisa dibuka. Simpan ulang dulu dari Office sebagai `.docx`, `.xlsx`, atau `.pptx`.

## Kebutuhan

- Android 6.0 atau lebih baru.

## Cara membuka file

- **Dari aplikasi:** ketuk **Buka file**, lalu pilih dokumennya. File yang pernah dibuka akan muncul di daftar terbaru.
- **Dari aplikasi lain** (WhatsApp, Gmail, pengelola file): ketuk file tersebut, lalu pilih **Pembaca Dokumen** (di HP berbahasa Inggris: *Document Reader*) dari daftar aplikasi.

## Cara memakai

**Semua dokumen**
- Geser ke atas atau ke bawah untuk pindah halaman.
- Cubit dengan dua jari untuk memperbesar atau memperkecil.
- Ketuk dua kali untuk zoom cepat.
- Ketuk **Ke halaman** untuk langsung lompat ke nomor halaman tertentu.

**Word dan PowerPoint**
- Tekan lama pada tulisan untuk memblok teks, lalu pilih **Salin**.
- Menu ⋮ → **Mode baca**: teks ditampilkan mengalir mengikuti lebar layar, lebih nyaman dibaca di HP. Pilih **Tampilan halaman** untuk kembali.

**Excel**
- Ketuk sebuah sel: isinya muncul di bagian bawah layar.
- Ketuk bagian bawah itu untuk melihat isi lengkapnya. Teksnya bisa diblok, atau ketuk **Salin**.
- Tekan lama pada sel untuk langsung menyalin isinya.
- Pindah sheet lewat tab di bagian paling bawah.
- Tombol **Kembali** menutup tampilan isi sel terlebih dahulu sebelum keluar dari file.

## Catatan

- Saat pertama kali dipakai dengan koneksi internet, aplikasi mengunduh sekali font yang mirip Calibri, Times New Roman, dan Arial lewat layanan font Google. Setelah itu tampilan dokumen lebih mirip aslinya. Tanpa internet, aplikasi tetap bisa dipakai dengan font bawaan HP.
- Aplikasi ini hanya untuk membaca. Dokumen tidak bisa diedit.
- Yang belum ditampilkan: grafik (chart) Excel dan PowerPoint, animasi slide, serta gambar berformat EMF/WMF.
