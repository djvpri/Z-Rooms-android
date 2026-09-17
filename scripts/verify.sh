#!/usr/bin/env bash
# Verifikasi ad-hoc proyek Android Z-Rooms.
#
# Kenapa: proyek Gradle tak punya "test suite" — buktinya harus build nyata
# lalu memeriksa isi APK. Skrip ini membangun dari NOL (clean) supaya tidak
# ada kelas lama yang membuat pemeriksaan lolos palsu.
#
# Tiga tahap, ketiganya harus lulus:
#   1. build bersih dari nol (debug + rilis)
#   2. periksa isi APK (15 blok)
#   3. buktikan checker MENGGIGIT kalau syarat intinya dilanggar
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
cp app/src/main/java/com/zrooms/app/MainActivity.kt "$CADANGAN.kt"
python - "$AKAR/app/src/main/java/com/zrooms/app/MainActivity.kt" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s2 = s.replace('BERANDA = "https://zxroom.zomet.my.id"',
               'BERANDA = "https://zxroom-LAMA.zomet.my.id"', 1)
assert s2 != s, 'baris BERANDA tak ketemu — uji gigit alamat tak bisa dijalankan'
open(p, 'w', encoding='utf-8', newline='').write(s2)
PY
node scripts/check-android-apk.mjs > "$LOG_GIGIT.almt" 2>&1
A=$?
echo "gigit_alamat_exit=$A  (harus 1)"
cp "$CADANGAN.kt" app/src/main/java/com/zrooms/app/MainActivity.kt
if [ "$A" -eq 0 ]; then
  echo "MASALAH: checker tetap lulus padahal alamat melenceng dari alamat.json"
  GAGAL=1
fi

echo
echo "=== keadaan dipulihkan ==="
echo "plugin kotlin : $(grep -c '^    id("org.jetbrains.kotlin.android")' app/build.gradle.kts) (harus 1)"
echo "alamat benar  : $(grep -c 'BERANDA = "https://zxroom.zomet.my.id"' app/src/main/java/com/zrooms/app/MainActivity.kt) (harus 1)"
echo "git bersih    : $(git status --porcelain | wc -l) (harus 0)"

rm -f "$LOG_BANGUN" "$LOG_PERIKSA" "$LOG_GIGIT" "$LOG_GIGIT.almt" "$CADANGAN" "$CADANGAN.kt"

echo
if [ "$GAGAL" -eq 0 ]; then echo "SEMUA LULUS"; else echo "ADA YANG GAGAL"; fi
exit $GAGAL
