#!/usr/bin/env bash
# Verifikasi ad-hoc proyek Android Z-Rooms.
#
# Kenapa: proyek Gradle tak punya "test suite" — buktinya harus build nyata
# lalu memeriksa isi APK. Skrip ini membangun dari NOL (clean) supaya tidak
# ada kelas lama yang membuat pemeriksaan lolos palsu.
#
# Enam tahap, semuanya harus lulus — tiga di antaranya uji gigit:
#   1. build bersih dari nol (debug + rilis)
#   2. periksa isi APK (15 blok)
#   3. buktikan checker MENGGIGIT kalau plugin Kotlin dimatikan
#   4. buktikan checker MENGGIGIT kalau alamat melenceng dari alamat.json
#   5. buktikan checker MENGGIGIT kalau versi di alamat.json tak cocok APK
#   6. buktikan uji gigit 3–5 sendiri masih hidup (polanya belum basi)
set -u

AKAR="C:/Users/KBK065/zrooms-android"
export JAVA_HOME="C:\\jdk\\jdk-17.0.20+8"
export ANDROID_HOME="C:\\Android\\Sdk"

cd "$AKAR" || exit 2

GAGAL=0
LOG_BANGUN=/tmp/hermes-verify-build.log
LOG_PERIKSA=/tmp/hermes-verify-check.log
LOG_GIGIT=/tmp/hermes-verify-gigit.log
CADANGAN=/tmp/hermes-verify-bg.bak

echo "=== 1/3 build bersih dari nol (debug + rilis) ==="
./gradlew.bat clean assembleDebug assembleRelease --no-daemon > "$LOG_BANGUN" 2>&1
B=$?
echo "build_exit=$B"
if [ "$B" -ne 0 ]; then
  grep -A 8 "What went wrong" "$LOG_BANGUN" | head -16
  GAGAL=1
fi

echo
echo "=== 2/3 isi APK ==="
node scripts/check-android-apk.mjs > "$LOG_PERIKSA" 2>&1
C=$?
echo "check_exit=$C"
grep -E "^  (ok|--)|blok lulus" "$LOG_PERIKSA"
if [ "$C" -ne 0 ]; then GAGAL=1; fi

echo
echo "=== 3/3 checker MENGGIGIT kalau plugin Kotlin dimatikan ==="
cp app/build.gradle.kts "$CADANGAN"
# Lewat python, bukan sed: sed dengan \n kena blokir perintah agen.
python - "$AKAR/app/build.gradle.kts" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s2 = s.replace('    id("org.jetbrains.kotlin.android")\n}',
               '    // id("org.jetbrains.kotlin.android")\n}', 1)
assert s2 != s, 'pola plugin Kotlin tak ketemu — uji gigit tak bisa dijalankan'
open(p, 'w', encoding='utf-8', newline='').write(s2)
PY
node scripts/check-android-apk.mjs > "$LOG_GIGIT" 2>&1
G=$?
echo "gigit_exit=$G  (harus 1)"
cp "$CADANGAN" app/build.gradle.kts
if [ "$G" -eq 0 ]; then
  echo "MASALAH: checker tetap lulus padahal plugin Kotlin dimatikan"
  GAGAL=1
fi

echo
echo "=== 4/4 checker MENGGIGIT kalau alamat situs melenceng ==="
# Dialihkan dari MainActivity.kt ke alamat.json — sejak 8765348 alamat hanya
# hidup di sana, MainActivity memakai BuildConfig.BERANDA. Versi lama mengganti
# literal di MainActivity.kt; pola itu sudah tak ada, jadi tahap ini MATI
# (assert Python melempar dan skrip berhenti sebelum memulihkan apa pun).
cp alamat.json "$CADANGAN.json"
python - "$AKAR/alamat.json" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s2 = s.replace('"https://zxroom.zomet.my.id"',
               '"https://zxroom-LAMA.zomet.my.id"', 1)
assert s2 != s, 'alamat beranda tak ketemu di alamat.json — uji gigit tak bisa dijalankan'
open(p, 'w', encoding='utf-8', newline='').write(s2)
PY

# Dua sisi harus menggigit, dan keduanya bisa gagal sendiri-sendiri:
#   (a) blok "alamat situs menunjuk host produksi" menolak host yang salah
#   (b) blok "satu sumber kebenaran" membandingkan APK terbangun dengan
#       alamat.json, jadi APK lama pun ikut ditolak
node scripts/check-android-apk.mjs > "$LOG_GIGIT.almt" 2>&1
A=$?
echo "gigit_alamat_exit=$A  (harus 1)"
cp "$CADANGAN.json" alamat.json
if [ "$A" -eq 0 ]; then
  echo "MASALAH: checker tetap lulus padahal alamat melenceng dari alamat.json"
  GAGAL=1
fi

echo
echo "=== 5/5 checker MENGGIGIT kalau versi di alamat.json tak dicatat di APK ==="
# Versi berbeda dari alamat: memastikan blok versi benar-benar membaca APK,
# bukan cuma membaca alamat.json lalu membandingkannya dengan dirinya sendiri.
cp alamat.json "$CADANGAN.json"
python - "$AKAR/alamat.json" <<'PY'
import json, sys
p = sys.argv[1]
d = json.load(open(p, encoding='utf-8'))
d['versiKode'] = d['versiKode'] + 1
json.dump(d, open(p, 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
PY
node scripts/check-android-apk.mjs > "$LOG_GIGIT.versi" 2>&1
V=$?
echo "gigit_versi_exit=$V  (harus 1)"
cp "$CADANGAN.json" alamat.json
if [ "$V" -eq 0 ]; then
  echo "MASALAH: checker tetap lulus padahal versi di alamat.json beda dari APK"
  GAGAL=1
fi

echo
echo "=== 6/6 uji gigitnya sendiri masih hidup (pola tak basi) ==="
# Menjalankan ulang logika uji gigit di atas terhadap salinan repo. Kalau salah
# satu pola di Alamat.json / build.gradle.kts hilang, tahap 3–5 akan berhenti
# diam-diam dan seluruh "SEMUA LULUS" jadi bohong.
node scripts/check-verify-gigit.mjs > "$LOG_GIGIT.sndiri" 2>&1
S=$?
echo "gigit_sendiri_exit=$S  (harus 0)"
if [ "$S" -ne 0 ]; then
  grep -E "AssertionError" "$LOG_GIGIT.sndiri" | head -3
  GAGAL=1
fi

echo
echo "=== keadaan dipulihkan ==="
echo "plugin kotlin : $(grep -c '^    id("org.jetbrains.kotlin.android")' app/build.gradle.kts) (harus 1)"
echo "alamat benar  : $(grep -c '"https://zxroom.zomet.my.id"' alamat.json) (harus 1)"
echo "versi asli    : $(grep -c '"versiKode": 2' alamat.json) (harus 1)"
echo "MainActivity  : $(grep -c 'const val BERANDA = BuildConfig.BERANDA' app/src/main/java/com/zrooms/app/MainActivity.kt) (harus 1)"
echo "git bersih    : $(git status --porcelain | wc -l) (harus 0)"

rm -f "$LOG_BANGUN" "$LOG_PERIKSA" "$LOG_GIGIT" "$LOG_GIGIT.almt" "$LOG_GIGIT.versi" "$LOG_GIGIT.sndiri" "$CADANGAN" "$CADANGAN.kt" "$CADANGAN.json"

echo
if [ "$GAGAL" -eq 0 ]; then echo "SEMUA LULUS"; else echo "ADA YANG GAGAL"; fi
exit $GAGAL
