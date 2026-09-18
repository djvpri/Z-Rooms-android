#!/usr/bin/env bash
# scripts/uji-tangkap-kerusakan.sh
#
# Uji-NEGATIF: buktikan pemeriksa menangkap kerusakan, bukan cuma hijau.
#
# Kenapa perlu: pemeriksa yang selalu hijau tak menjaga apa pun. Tiap skenario
# di bawah ini MENIRU kerusakan yang benar-benar pernah terjadi (atau hampir
# terjadi), lalu memastikan check-berkas-webview.mjs GAGAL. Kalau ada satu saja
# yang lolos tanpa kegagalan, pemeriksanya bolong di titik itu.
#
# Berkas sumber dipulihkan dari salinan di /tmp, BUKAN lewat `git checkout`:
# perubahan yang belum di-commit juga ikut terbuang kalau memakai git.
#
# Jalankan: bash scripts/uji-tangkap-kerusakan.sh
set -u
AKAR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$AKAR" || exit 1

SIMPAN=$(mktemp -d)
MANIFES="app/src/main/AndroidManifest.xml"
MAIN="app/src/main/java/com/zrooms/app/MainActivity.kt"
PEMILIH="app/src/main/java/com/zrooms/app/PemilihBerkas.kt"
LOGWEB="app/src/main/java/com/zrooms/app/LogWeb.kt"
TEMA="app/src/main/res/values/themes.xml"
LAYOUT="app/src/main/res/layout/activity_main.xml"

cp "$MANIFES" "$SIMPAN/" ; cp "$MAIN" "$SIMPAN/" ; cp "$PEMILIH" "$SIMPAN/"
cp "$LOGWEB" "$SIMPAN/" ; cp "$TEMA" "$SIMPAN/" ; cp "$LAYOUT" "$SIMPAN/"

pulihkan() {
  cp "$SIMPAN/AndroidManifest.xml" "$MANIFES"
  cp "$SIMPAN/MainActivity.kt" "$MAIN"
  cp "$SIMPAN/PemilihBerkas.kt" "$PEMILIH"
  cp "$SIMPAN/LogWeb.kt" "$LOGWEB"
  cp "$SIMPAN/themes.xml" "$TEMA"
  cp "$SIMPAN/activity_main.xml" "$LAYOUT"
}
trap 'pulihkan; rm -rf "$SIMPAN"' EXIT

lulus=0
gagal=0

# rusak <nama> <berkas> <cari> <ganti>
# Sengaja memakai python, bukan sed: berkas proyek ini CRLF dan sed mengubah
# ujung baris tanpa terlihat.
# Pemisah `|` dipakai untuk kerusakan yang menjangkau beberapa baris: menulis
# baris baru langsung di dalam kutip tunggal bash akan menghasilkan `\n`
# harfiah, bukan baris baru — dan jangkarnya lalu tak pernah cocok.
rusak() {
  local nama="$1" berkas="$2" cari="$3" ganti="$4"
  # Diteruskan lewat LINGKUNGAN, bukan argumen: jangkar Kotlin memuat `$t` dan
  # `${...}` yang akan dimakan bash kalau ditulis di baris perintah.
  # Lewat BERKAS SEMENTARA, bukan heredoc ke stdin: di MSYS, `python` menunjuk
  # ke python Windows, dan pipa stdin dari bash Cygwin kadang sampai kosong —
  # yang muncul lalu galat yang menyesatkan ("sys.argv" di kode yang tak
  # memakainya). Argumen lewat variabel LINGKUNGAN juga, bukan baris perintah:
  # jangkar Kotlin memuat `$t`/`${...}` yang dimakan bash.
  # Berkas sementara dibuat di AKAR REPO, bukan /tmp: `python` di sini python
  # Windows, dan /tmp versi MSYS bukan path yang dikenalnya.
  # Semua path diubah ke bentuk Windows dulu: `python` di sini python Windows,
  # dan ia membaca `/c/Users/...` sebagai `C:\c\Users\...` — berkas tak ada.
  local tmp="$AKAR/.uji-tangkap-$$.py"
  cat > "$tmp" <<'PY'
import os
p, cari, ganti = os.environ["BERKAS"], os.environ["CARI"], os.environ["GANTI"]
LF, CRLF = chr(10), chr(13) + chr(10)
cari, ganti = cari.replace("|", LF), ganti.replace("|", LF)
# Ujung baris dinormalkan dulu: sebagian berkas proyek ini LF
# (PemilihBerkas.kt), sebagian CRLF (LogWeb.kt). Tanpa ini jangkar yang tampak
# persis sama tetap tak cocok, dan ujinya melapor "bocor" palsu.
raw = open(p, "rb").read().decode("utf-8").replace(CRLF, LF)
assert raw.count(cari) == 1, f"jangkar tak unik ({raw.count(cari)}x): {cari[:70]}"
out = raw.replace(cari, ganti, 1).replace(LF, CRLF)
open(p, "wb").write(out.encode("utf-8"))
PY
  BERKAS="$(cygpath -w "$berkas")" CARI="$cari" GANTI="$ganti" \
    python "$(cygpath -w "$tmp")"
  local rc=$?
  rm -f "$tmp"
  return $rc
}

periksa() {
  local nama="$1"
  if node scripts/check-berkas-webview.mjs >/dev/null 2>&1; then
    echo "  BOCOR - $nama  (pemeriksa tetap hijau)"
    gagal=$((gagal + 1))
  else
    echo "  ok    - $nama  (tertangkap)"
    lulus=$((lulus + 1))
  fi
  pulihkan
}

echo "uji tangkap kerusakan: check-berkas-webview"
echo

# 1. Kerusakan yang MENYEBABKAN "aplikasi keluar sendiri saat klik kamera":
#    izin CAMERA dibuang dari manifes.
rusak izin-CAMERA-dibuang "$MANIFES" \
  '    <uses-permission android:name="android.permission.CAMERA" />' ''
periksa "izin CAMERA dibuang dari manifes"

# 2. Fotonya kembali dikirim lewat extras — tak terbaca sejak Android 11.
rusak EXTRA_OUTPUT-dibuang "$PEMILIH" '.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, tujuan)' ''
periksa "EXTRA_OUTPUT dibuang"

# 3. URI file:// telanjang → FileUriExposedException sejak Android 7.
rusak FileProvider-ditinggalkan "$PEMILIH" 'FileProvider.getUriForFile(activity, "${activity.packageName}.berkas", berkas)' 'android.net.Uri.fromFile(berkas)'
periksa "FileProvider diganti file://"

# 4. Launcher hasil pemilih dibiarkan sebagai properti KELAS. Kalau
#    registrasinya dipindah ke dalam cabang (hanya dibuat saat kamera dipakai),
#    Android tak menemukannya lagi ketika proses dipulihkan setelah aplikasi
#    kamera menutup paksa — kasir melihat aplikasi keluar sendiri.
rusak registrasi-dipindah-ke-dalam "$PEMILIH" \
  '    private val pilih: ActivityResultLauncher<android.content.Intent> =|        activity.registerForActivityResult(' \
  '    private val pilih: ActivityResultLauncher<android.content.Intent> =|        mintaBerkas({}).registerForActivityResult('
periksa "registrasi hasil pemilih tak lagi tanpa syarat"

# 5. Izin runtime tak pernah diminta → SecurityException saat kamera dibuka.
rusak izin-tak-diminta "$PEMILIH" \
  '        mintaIzin.launch(android.Manifest.permission.CAMERA)' \
  '        // izin tak lagi diminta'
periksa "permintaan izin kamera dihapus"

# 6. Banner versi kembali → menghalangi dashboard lagi.
rusak banner-kembali "$LAYOUT" \
  '    <ProgressBar' \
  '    <TextView android:id="@+id/tvVersi" android:layout_width="wrap_content"|        android:layout_height="wrap_content" android:layout_gravity="bottom" />|    <ProgressBar'
periksa "banner tvVersi kembali ke layout"

# 7. Tema kembali NoActionBar → bilah judul null, versi tak tampil.
rusak tema-NoActionBar "$TEMA" 'Theme.AppCompat.DayNight.DarkActionBar' 'Theme.AppCompat.DayNight.NoActionBar'
periksa "tema kembali NoActionBar"

# 8. Judul bilah tak pernah diisi.
rusak judul-tak-diisi "$MAIN" \
  'supportActionBar?.title = getString(R.string.label_versi, BuildConfig.VERSI_NAMA)' \
  '// versi tak lagi diisi'
periksa "judul bilah versi dihapus"

# 9. Callback gagal tak dibalas → halaman web menggantung (tombol diam).
rusak callback-tak-dibalas "$PEMILIH" \
  'cb?.onReceiveValue(null)|                return@registerForActivityResult' \
  'return@registerForActivityResult'
periksa "callback batal tak dibalas"

# 10. Log kembali cuma di memori → tombol "Kirim log error" lapor 0 kejadian.
rusak log-tak-disimpan "$LOGWEB" \
  '        while (baris.size > MAKS) baris.removeFirst()|        simpanKeDisk()|' \
  '        while (baris.size > MAKS) baris.removeFirst()|'
periksa "log tak lagi ditulis ke disk"

# 11. Izin pembaruan mandiri hilang → Android 8+ menolak memasang APK baru, dan
#     seluruh HP yang sudah beredar harus dipasang manual satu per satu.
rusak izin-pasang-apk-hilang "$MANIFES" \
  '    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />|' ''
periksa "izin REQUEST_INSTALL_PACKAGES dibuang"

echo
echo "hasil: $lulus tertangkap, $gagal bocor"
[ "$gagal" -eq 0 ]
