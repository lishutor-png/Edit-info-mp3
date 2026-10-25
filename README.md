# LishEdInfo 🎵

Aplikasi Android untuk mengedit metadata dan cover art lagu MP3 secara aman, intuitif, dan ringan.

## Fitur Utama
- **Zero Audio Corruption**: Stream audio MPEG dijaga 100% utuh tanpa re-encoding atau distorsi.
- **Edit Info Lengkap**: Judul, Artis, Album, Cover Art, Tahun, Genre, dan Nomor Track.
- **In-App Music Player**: Putar dan verifikasi lagu secara langsung di dalam aplikasi.
- **Simpan & Sinkronisasi Cepat**: Otomatis terindeks ke pemutar musik bawaan perangkat via MediaStore.
- **GitHub Actions CI/CD**: Otomatis mem-build file APK setiap kali kode di-push ke repository.

## Cara Export / Push ke GitHub dari AI Studio
1. Klik menu **Project Settings / Export** (ikon gear atau titik tiga di kanan atas layar AI Studio).
2. Pilih **Push to GitHub** atau hubungkan akun GitHub Anda.
3. Setelah repository terbentuk di GitHub, workflow di `.github/workflows/build.yml` akan otomatis berjalan untuk mem-build APK.
4. Anda dapat mengunduh file APK siap pakai di tab **Actions** $\rightarrow$ pilih build terbaru $\rightarrow$ download bagian **Artifacts (LishEdInfo-APK)**.
