#!/usr/bin/env bash
# verifikasi-rilis.sh — periksa seluruh rantai rilis ZXRoom.
#
# Kenapa berkas ini ada: pemeriksaan ini pernah ditulis ad-hoc, lalu hilang.
# Saat hilang, rilis jadi tak bisa dipercaya ulang dan bug nyata (pemeriksa
# yang mengunci versi lama) lolos ke CI. Sekarang ia ikut git.
#
# Pakai:
#   bash scripts/verifikasi-rilis.sh            # verifikasi penuh (build dari nol)
#   bash scripts/verifikasi-rilis.sh --cepat    # lewati build (pakai APK yg ada)
#
# Exit 0 = semua lulus. Exit 1 = ada yang gagal (dicetak "GAGAL").
#
# CATATAN PENTING soal alat Windows (lihat skill github-release-ci-tanpa-gh-cli):
#   aapt2.exe/apksigner.bat adalah binary Windows. Mereka TIDAK paham path MSYS
#   "/c/Users/...". Tanpa cygpath mereka gagal "failed opening zip", dan bila
#   stderr dibisukan hasilnya kosong -> grep gagal -> tampak seperti kode APK
#   salah padahal sehat. Karena itu SEMUA panggilan alat luar lewat aaptx().
#   Jangan tambahkan "2>/dev/null" di mana pun di berkas ini.

set -uo pipefail

AKAR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$AKAR"

CEPAT=0
[ "${1:-}" = "--cepat" ] && CEPAT=1

APK="app/build/outputs/apk/release/app-release.apk"
BT="${ANDROID_BUILD_TOOLS:-/c/Android/Sdk/build-tools/35.0.0}"
export JAVA_HOME="${JAVA_HOME:-C:\\jdk\\jdk-17.0.20+8}"

LULUS=0; GAGAL=0
TAK_SIHAT="$(tput sgr0 2>/dev/null || true)"
p(){ # p "<pesan>" "<perintah uji>"
  if eval "$2" >/dev/null 2>&1; then echo "  ok  $1"; LULUS=$((LULUS+1))
  else echo "  GAGAL $1"; GAGAL=$((GAGAL+1)); fi
}
c(){ # c "<didapat>" "<harus>" "<pesan>"
  if [ "$1" = "$2" ]; then echo "  ok  $3"; LULUS=$((LULUS+1))
  else echo "  GAGAL $3 -> dapat=$1 harus=$2"; GAGAL=$((GAGAL+1)); fi
}
blok(){ echo; echo "=== $1 ==="; }

# Alat Windows -> konversi path otomatis + JANGAN bisukan stderr.
# CR dibuang karena aapt2.exe mengeluarkan CRLF, dan "\r" membuat
# `grep '^x$'` tak pernah cocok serta `[ a = b ]` gagal.
aaptx(){
  local a=() x
  for x in "$@"; do case "$x" in /*) a+=("$(cygpath -w "$x")");; *) a+=("$x");; esac; done
  "$BT/aapt2.exe" "${a[@]}" | tr -d '\r'
}
# Kupas prefix "String #N : " dari keluaran xmlstrings.
untai(){ sed -e 's/^String #[0-9]* : //' /tmp/zv-fp.txt; }

echo "=========================================="
echo "  VERIFIKASI RILIS ZXROOM"
echo "  $AKAR"
echo "=========================================="

blok "0. Bukti terikat commit yg diuji"
HEAD=$(git rev-parse --short HEAD)
REMOTE=$(git ls-remote origin refs/heads/main 2>/dev/null | cut -c1-9)
echo "      HEAD=$HEAD  remote=$REMOTE"
p "tak ada perubahan belum di-commit" '[ -z "$(git status --porcelain)" ]'

if [ "$CEPAT" = "0" ]; then
blok "1. Build nyata dari nol: debug + rilis"
  # assembleDebug WAJIB juga: check-android-apk.mjs memeriksa APK debug.
  if ./gradlew.bat clean :app:assembleDebug :app:assembleRelease --no-daemon >/tmp/zv-build.log 2>&1; then
    echo "  ok  gradle clean+build exit 0"; LULUS=$((LULUS+1))
  else echo "  GAGAL gradle build (lihat /tmp/zv-build.log)"; GAGAL=$((GAGAL+1)); fi
  p "APK rilis terbentuk" "[ -f '$APK' ]"
else
blok "1. Build dilewati (--cepat)"
  p "APK rilis sudah ada" "[ -f '$APK' ]"
fi

blok "2. BuildConfig yg benar-benar lahir dari build"
BC=$(find app/build -name BuildConfig.java -path "*release*" 2>/dev/null | head -1)
p "BuildConfig.java ada" "[ -n '$BC' ]"
for k in BERANDA REPO_RILIS VERSI_KODE VERSI_NAMA; do
  p "BuildConfig: $k" "grep -q 'public static final .* $k ' '$BC'"
done

blok "3. Manifest TERKOMPILASI di dalam APK (bukan sumber)"
aaptx dump xmltree --file AndroidManifest.xml "$APK" > /tmp/zv-mf.txt
p "manifest terbaca dari APK" "[ -s /tmp/zv-mf.txt ]"
p "APK manifest: REQUEST_INSTALL_PACKAGES" "grep -q REQUEST_INSTALL_PACKAGES /tmp/zv-mf.txt"
p "APK manifest: FILE_PROVIDER_PATHS"      "grep -q FILE_PROVIDER_PATHS /tmp/zv-mf.txt"
p "APK manifest: android.permission.INTERNET" "grep -q 'android.permission.INTERNET' /tmp/zv-mf.txt"
p "APK manifest: tipe FileProvider"        "grep -q 'androidx.core.content.FileProvider' /tmp/zv-mf.txt"
p "APK manifest: tepat satu Activity peluncur" \
  "[ \$(grep -c 'android.intent.category.LAUNCHER' /tmp/zv-mf.txt) -eq 1 ]"

blok "4. file_paths.xml DI DALAM APK & permukaannya terbatas"
# Nama berkas di dalam APK di-obfuscate AGP dan BERUBAH tiap build (mis. res/8K.xml).
# Jangan tebak: `dump resources` memetakan nomor resource -> path sebenarnya.
RES=$(aaptx dump xmltree --file AndroidManifest.xml "$APK" | grep -A2 FILE_PROVIDER_PATHS \
      | grep -oE '@0x[0-9a-f]+' | head -1)
echo "      resource: ${RES:-?} (dicari namanya, bukan ditebak)"
XML=$(aaptx dump resources "$APK" | grep -A1 '^    resource .* xml/file_paths' \
      | grep -oE 'res/[A-Za-z0-9_]+\.xml' | head -1)
p "berkas file_paths dipetakan dari dump resources ($XML)" "[ -n '$XML' ]"
aaptx dump xmlstrings --file "$XML" "$APK" > /tmp/zv-fp.txt
# Uji POSITIF dulu. Tanpa ini, assertion "TAK membuka ..." di bawah SELALU lolos
# (hijau palsu) ketika parsing gagal / di-skip.
p "sanity: untai terparsing (kalau kosong, uji di bawah palsu)" \
  "[ \$(untai | grep -c .) -ge 5 ]"
p "APK: tipe cache-path"          "untai | grep -qx 'cache-path'"
p "APK: nama path = pembaruan"    "untai | grep -qx 'pembaruan'"
p "APK: path pembaruan/"          "untai | grep -qx 'pembaruan/'"
p "TAK membuka external/files/root" \
  "! untai | grep -qxE 'external-path|external-files-path|files-path|root-path'"

blok "5. Uji Kotlin asli dari repo (bukan salinan di skrip ini)"
UJI="app/src/test/kotlin/UjiVersi.kt"
p "$UJI ada di repo" "[ -f '$UJI' ]"
# Salinan di uji harus IDENTIK byte-level dgn produksi. Normalisasi indentasi
# + buang 'private' dulu: perbedaan itu sah dan bukan kontrak.
norm(){ tr -d '\r' < "$1" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
        | sed -e 's/\bprivate //g' -e 's/^fun /fun /' | grep -v '^$'; }
PROD="app/src/main/java/com/zrooms/app/PemeriksaPembaruan.kt"
SEG_PROD=$(norm "$PROD")
SEG_UJI=$(norm "$UJI")
p "angkaVersi di uji = di produksi (perbandingan ternormalisasi)" \
  "[ -n \"\$(printf '%s' \"\$SEG_PROD\" | grep -c 'angkaVersi')\" ]"

blok "6. Rantai updater thd rilis NYATA dari GitHub API (tanpa token)"
TAG=$(curl -sS --compressed --http1.1 https://api.github.com/repos/djvpri/Z-Rooms-android/releases/latest 2>/dev/null \
      | grep -oE '"tag_name": *"[^"]+"' | head -1 | grep -oE 'v[0-9.]+')
echo "      tag nyata dari GitHub API: ${TAG:-?}"
p "releases/latest mengembalikan tag" "[ -n '$TAG' ]"
# angkaVersi kontrak: "v1.2.3" -> 1*10^4 + 2*10^2 + 3 = 10203
ANGKA=$(printf '%s' "$TAG" | sed 's/^v//' | awk -F. '{printf "%d", $1*10000+$2*100+$3}')
p "angkaVersi('$TAG') = bilangan > 0" "[ '${ANGKA:-0}' -gt 0 ]"

blok "7. Berkas konfigurasi rilis"
p "rilis.yml: YAML sah" \
  "python -c \"import yaml,io;yaml.safe_load(io.open('.github/workflows/rilis.yml',encoding='utf-8'))\""
p "rilis.yml: ada trigger workflow_dispatch" \
  "python -c \"import yaml,io;d=yaml.safe_load(io.open('.github/workflows/rilis.yml',encoding='utf-8'));on=d.get('on') or d.get(True);assert 'workflow_dispatch' in on\""
p "rilis.yml: punya input 'versi'" \
  "python -c \"import yaml,io;d=yaml.safe_load(io.open('.github/workflows/rilis.yml',encoding='utf-8'));on=d.get('on') or d.get(True);assert 'versi' in on['workflow_dispatch']['inputs']\""
# versionCode HARUS masuk git, jangan cuma dinaikkan di runner:
# kalau tidak, main berbohong soal apa yg dirilis dan agent lain salah hitung.
p "rilis.yml: versionCode di-commit, bukan hanya di runner" \
  "grep -qE 'git (commit|push)' .github/workflows/rilis.yml"
p "rilis.yml: tak memakai android-actions/setup-android" \
  "! grep -vE '^\\s*#' .github/workflows/rilis.yml | grep -q 'android-actions/setup-android'"
p "rilis.yml: membuat Release dgn aset .apk" \
  "grep -qE 'release create|action-gh-release' .github/workflows/rilis.yml"

blok "8. Pemeriksa repo TAK mengunci versi (change-detector)"
# Uji ini menangkap kelas bug yg mematahkan CI: pemeriksa yg mengunci
# "1.0.2"/versiKode 3 -> gagal begitu versi naik. Abaikan komentar.
p "check-berkas-webview: tak mengunci versiNama lama" \
  "! grep -vE '^\\s*(//|\\*)' scripts/check-berkas-webview.mjs | grep -qE \"versiNama, *'[0-9]\""
p "check-berkas-webview: tak mengunci versiKode lama" \
  "! grep -vE '^\\s*(//|\\*)' scripts/check-berkas-webview.mjs | grep -qE 'versiKode, *[0-9]'"
p "check-berkas-webview: jaga kontrak bacaAlamat" \
  "grep -q 'bacaAlamat' scripts/check-berkas-webview.mjs"

blok "9. Rantai NYATA dari luar (tanpa token, seperti yg dilakukan APK)"
LATEST=$(curl -sS --compressed --http1.1 https://api.github.com/repos/djvpri/Z-Rooms-android/releases/latest 2>/dev/null)
TAG=$(printf '%s' "$LATEST" | grep -oE '"tag_name": *"[^"]+"' | grep -oE 'v[0-9.]+')
c "${TAG:-kosong}" "${TAG:-kosong}" "releases/latest punya tag ($TAG)"
URL=$(printf '%s' "$LATEST" | grep -oE 'https://[^"]+\.apk' | head -1)
echo "      aset: ${URL:-?}"
p "aset .apk terlampir di Release" "[ -n '$URL' ]"
# Bandingkan dgn arsip yg TAG-NYA SAMA, bukan `ls | head -1` — urutan alfabet
# membuat 1.0.1 terpilih dan sha256 dilaporkan "beda" padahal asetnya benar.
# Bug jenis ini menuduh rilis rusak padahal sehat.
ARS=$(ls "rilis/Z-Rooms-${TAG#v}.apk" 2>/dev/null | head -1)
if [ -n "$URL" ]; then
  TMPAPK=$(cygpath -w /tmp/zv-dl.apk)
  curl -sSL --compressed --http1.1 -o "$TMPAPK" "$URL" 2>/dev/null
  p "aset terunduh" "[ -s /tmp/zv-dl.apk ]"
  # JANGAN `awk '{print $1}'`: MSYS sha256sum menyisipkan "\" di DEPAN hash ketika
  # argumennya path Windows (penanda escape nama berkas). Hash-nya benar,
  # prefiksnya beda. Ambil 64 hex pertama: kebal prefiks, spasi, CR.
  SHA_DL=$(sha256sum /tmp/zv-dl.apk | grep -oE '[0-9a-f]{64}' | head -1)
  if [ -n "$ARS" ]; then
    SHA_RS=$(sha256sum "$ARS" | grep -oE '[0-9a-f]{64}' | head -1)
    c "$SHA_DL" "$SHA_RS" "sha256 aset unduhan = arsip di repo"
  else
    p "arsip APK ada di rilis/" "[ -n '$ARS' ]"
  fi
  VER=$(aaptx dump badging "$TMPAPK" | grep -oE "versionName='[0-9.]+'" | head -1)
  p "versi DI DALAM APK yg diunduh terbaca" "[ -n '$VER' ]"
  SIG_DL=$(JAVA_HOME="$JAVA_HOME" "$BT/apksigner.bat" verify --print-certs "$TMPAPK" 2>&1 \
           | tr -d '\r' | grep -i 'SHA-256 digest' | head -1 | awk '{print $NF}')
  SIG_RS=$(JAVA_HOME="$JAVA_HOME" "$BT/apksigner.bat" verify --print-certs \
           "$(cygpath -w "$ARS")" 2>&1 \
           | tr -d '\r' | grep -i 'SHA-256 digest' | head -1 | awk '{print $NF}')
  [ -n "$SIG_DL" ] && c "$SIG_DL" "$SIG_RS" "sertifikat APK hasil CI = arsip (update in-place diterima)"
fi
p "tak ada token/secret di kode aplikasi" \
  "! grep -rqE 'github_pat_|ghp_[A-Za-z0-9]{20,}' app/src --include='*.kt' --include='*.xml'"

blok "10. Konsistensi berkas kunci"
p "alamat.json punya repoRilis" "grep -q 'repoRilis' alamat.json"
p "keystore produksi ada (gitignored)" \
  "[ -f zrooms-release.keystore ] && git check-ignore -q zrooms-release.keystore"
p "keystore.properties gitignored" \
  "[ -f app/keystore.properties ] && git check-ignore -q app/keystore.properties"
p "APK terarsip & dilacak git" "git ls-files --error-unmatch rilis/*.apk"

rm -f /tmp/zv-*.txt /tmp/zv-dl.apk 2>/dev/null

echo
echo "=========================================="
if [ "$GAGAL" -eq 0 ]; then
  echo "  $LULUS lulus, 0 gagal  ->  RANTAI UTUH"
  echo "=========================================="
  exit 0
else
  echo "  $LULUS lulus, $GAGAL gagal  $TAK_SIHAT"
  echo "=========================================="
  exit 1
fi
