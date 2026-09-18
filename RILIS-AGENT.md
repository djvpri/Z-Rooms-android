# RILIS-AGENT.md — instruksi rilis untuk agent (Android Z)

Kamu agent yang diberi tugas **menerbitkan rilis APK baru**. Ikuti berkas ini
persis. Ia berdiri sendiri: kamu tidak perlu membaca `RILIS.md` lebih dulu.

Repo yang dicakup:

| App | Repo | Berkas workflow | Input versi | Panduan rinci |
| --- | --- | --- | --- | --- |
| ZXRoom | `djvpri/Z-Rooms-android` | `rilis.yml` | `versi` (mis. `1.0.4`) | `RILIS.md` |
| Z1 Label | `djvpri/z1label-android` | `release.yml` | `version` (mis. `1.6.33`) | `README.md` → `## Rilis` |

**Perhatikan: nama berkas DAN nama input-nya BERBEDA.** ZXRoom `rilis.yml` /
`versi`; Z1 Label `release.yml` / `version`. Salah salah satunya = HTTP 404
(berkas) atau 422 (input).

Input opsional: ZXRoom `catatan`, Z1 Label `changelog`. Isi kalau mau catatan
rilis tampil di Release. Keduanya boleh dikosongkan.

---

## Aturan yang tidak boleh dilanggar

1. **Jangan cetak token.** Simpan di variabel shell. Jangan pernah masuk commit,
   log, atau keluaran.
2. **Versi harus NAIK.** WF `versionCode` di ZXRoom & perbandingan `tag_name` di
   keduanya akan menolak/menyia-nyiakan versi yang tak lebih tinggi.
3. **Aset `.apk` WAJIB terlampir** di Release. Tag saja tidak cukup — updater
   mencari aset bernama `.apk`. Release tanpa aset = update tak pernah muncul di
   pengguna.
4. **Jangan ganti keystore.** APK bertanda-tangan berbeda **tidak bisa** dipasang
   menimpa ("app not installed"). Sekali salah, semua HP terpasang harus pasang
   ulang manual.
5. **Jangan ubah berkas kunci aplikasi** (`check-berkas-webview.mjs`,
   `check-android-apk.mjs`) untuk meloloskan rilis. Kalau pemeriksa menolak,
   pemeriksa sedang benar — perbaiki penyebabnya.
6. **Jangan menjalankan rilis atas inisiatif sendiri.** Rilis = produksi.
   Konfirmasi ke pemilik repo dulu kecuali ia sudah memerintahkan versi tertentu.

---

## Prosedur (jalur token)

### Langkah 0 — ambil token

```bash
TOKEN=$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill \
  | awk -F= '/^password=/{print substr($0,10)}')
[ -n "$TOKEN" ] || { echo "TAK ADA TOKEN — berhenti, jangan mengarang jalur lain"; exit 1; }
```

Kalau kosong: kamu **tidak bisa** merilis. Katakan begitu, jangan mencari akal.

### Langkah 1 — baca versi terakhir yang sudah terbit

```bash
# ZXRoom
curl -sS --compressed --http1.1 \
  https://api.github.com/repos/djvpri/Z-Rooms-android/releases/latest \
  | grep -oE '"tag_name": *"[^"]+"'

# Z1 Label
curl -sS --compressed --http1.1 \
  https://api.github.com/repos/djvpri/z1label-android/releases/latest \
  | grep -oE '"tag_name": *"[^"]+"'
```

Baca **`releases/latest`**, bukan `main`. Di Z1 Label `main` sengaja tetap
`1.0.0` — memakai `main` membuatmu menghitung versi salah.

### Langkah 2 — picu workflow

```bash
# ZXRoom  (input wajib: versi; opsional: catatan)
curl -sS --compressed --http1.1 -X POST \
  -H "Authorization: Bearer $TOKEN" \
  https://api.github.com/repos/djvpri/Z-Rooms-android/actions/workflows/rilis.yml/dispatches \
  -d '{"ref":"main","inputs":{"versi":"1.0.4","catatan":"perbaikan X"}}'

# Z1 Label  (input wajib: version; opsional: changelog)
curl -sS --compressed --http1.1 -X POST \
  -H "Authorization: Bearer $TOKEN" \
  https://api.github.com/repos/djvpri/z1label-android/actions/workflows/release.yml/dispatches \
  -d '{"ref":"main","inputs":{"version":"1.6.33","changelog":"perbaikan Y"}}'
```

**HTTP 204 = diterima.** Arti kode lain:

| Kode | Arti |
| --- | --- |
| 401 | token salah/kadaluarsa |
| 403 | token kurang scope |
| 404 | nama berkas workflow salah, atau token tak punya hak baca |
| 422 | nama input salah / ref salah — **penyebab paling umum** |

`422` hampir selalu berarti kamu memakai `versi` di repo yang menunggu `version`
(atau sebaliknya).

### Langkah 3 — ambil id run

```bash
RUN_ID=$(curl -sS --compressed --http1.1 -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/djvpri/Z-Rooms-android/actions/workflows/rilis.yml/runs?per_page=1" \
  | python -c "import json,sys; w=json.load(sys.stdin)['workflow_runs']; print(w[0]['id'] if w else '')")
echo "RUN_ID=$RUN_ID"
```

**Jangan** pakai `grep '"id"' | head -1`. Respons memuat banyak field `id`
(owner, repo, actor); grep mengambil yang salah. Field run ada di
`workflow_runs[0].id`.

Catatan: run bisa muncul 2–5 detik setelah dispatch. Kalau `RUN_ID` kosong,
tunggu lalu ulangi.

### Langkah 4 — tunggu sampai selesai

```bash
while :; do
  S=$(curl -sS --compressed --http1.1 -H "Authorization: Bearer $TOKEN" \
    "https://api.github.com/repos/djvpri/Z-Rooms-android/actions/runs/$RUN_ID" \
    | python -c "import json,sys; d=json.load(sys.stdin); print(d['status'], d.get('conclusion') or '')")
  echo "  $S"
  case "$S" in completed*) break;; esac
  sleep 20
done
```

Harus berakhir `completed success`. Kalau `completed failure`, lanjut langkah 4b.

### Langkah 4b — cari langkah yang gagal (hanya kalau failure)

```bash
curl -sS --compressed --http1.1 -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/djvpri/Z-Rooms-android/actions/runs/$RUN_ID/jobs" \
  | python -c "
import json,sys
for j in json.load(sys.stdin)['jobs']:
    print('JOB', j['name'], j['conclusion'])
    for s in j['steps']:
        if s['conclusion'] == 'failure':
            print('   GAGAL:', s['name'])
            print('   job_id =', j['id'])
"

# Log mentah job yang gagal
curl -sSL --compressed --http1.1 -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/djvpri/Z-Rooms-android/actions/jobs/$JOB_ID/logs" \
  | tail -40
```

### Langkah 5 — verifikasi (JANGAN dilewatkan)

HTTP 204 di langkah 2 **bukan** bukti rilis terbit. Periksa ketiganya:

```bash
# 5a. tag + aset  (ini yang dibaca APK pengguna, tanpa token)
curl -sS --compressed --http1.1 \
  https://api.github.com/repos/djvpri/Z-Rooms-android/releases/latest \
  | python -c "
import json,sys
d=json.load(sys.stdin)
print('tag :', d['tag_name'])
apk=[a['name'] for a in d['assets'] if a['name'].endswith('.apk')]
print('apk :', apk or '*** TAK ADA ASET APK — RILIS RUSAK ***')
assert apk, 'Release tanpa aset .apk: updater tak akan pernah melihatnya'
"

# 5b. versionCode di dalam APK naik (ZXRoom)
#     unduh aset, lalu:  aapt2 dump badging <file.apk> | grep versionCode

# 5c. sertifikat SAMA dengan rilis sebelumnya — kalau beda, update in-place ditolak
export JAVA_HOME='C:\jdk\jdk-17.0.20+8'
"$BT/apksigner.bat" verify --print-certs "$(cygpath -w Z-Rooms-1.0.4.apk)" \
  | grep -i 'SHA-256 digest'
# ZXRoom wajib: a9426745a4529a9fbb3fa2f86cadcca3db2208c31707d8d9abcd5d1e89f9a48e
```

Di ZXRoom, jalankan pemeriksa repo — ia memeriksa lebih dalam daripada matamu:

```bash
cd zrooms-android && bash scripts/verifikasi-rilis.sh --cepat
# exit 0 = rantai utuh. Jalankan TANPA --cepat hanya bila ingin build dari nol.
```

---

## Jebakan yang sudah memakan waktu (jangan diulang)

Semua nyata. Baca sebelum menuduh kode aplikasi rusak.

- **`422` di langkah 2** → nama input salah. ZXRoom `versi`, Z1 Label `version`.
- **`grep '"id"' | head -1`** → id salah. Pakai `workflow_runs[0].id`.
- **Aset `.apk` lupa terlampir** → penyebab paling umum "update tak muncul".
  Tag terbit, workflow hijau, tapi pengguna tak pernah ditawari update.
- **`android-actions/setup-android` jangan dipasang kembali** — aksinya
  menganggap SDK praterpasang salah versi lalu menjalankan `sdkmanager tools`,
  paket yang sudah dihapus. SDK sudah ada di runner.
- **Alat Windows + path MSYS.** `aapt2.exe`/`apksigner.bat` tak paham
  `/c/Users/...`; wajib `cygpath -w`. Jangan `2>/dev/null` — itu menelan galat
  dan mengubahnya jadi "keluaran kosong" yang tampak seperti bug kode.
- **CRLF & prefix.** `aapt2.exe` mengeluarkan `\r`; `dump xmlstrings` memberi
  prefix `String #N : `. Jadi `grep '^cache-path$'` mustahil cocok.
- **MSYS `sha256sum` menyisipkan `\` di depan hash** untuk path Windows. Ambil
  64 hex pertama, jangan `awk '{print $1}'`.
- **Jangan bandingkan APK dengan `ls | head -1`** — urutan alfabet memilih versi
  terlama, lalu sha256 dilaporkan "beda" padahal asetnya benar.
- **Selalu `--compressed --http1.1`** pada curl di Windows.

---

## Keystore — baca sebelum menyentuh apa pun

**Secret tidak bisa dibaca kembali.** Sudah diuji: `GET
/actions/secrets/KEYSTORE_B64` membalas HTTP 200 dengan **hanya** `name`,
`created_at`, `updated_at`. Nol field nilai. Kunci publik repo hanya untuk
**menulis**.

Artinya, kalau keystore di laptop hilang, ia hilang **permanen**. Konsekuensinya
berat: semua HP terpasang tak bisa update lagi, dan pengguna harus pasang ulang
manual sambil kehilangan data lokal.

Kamu **tidak perlu** keystore lokal untuk merilis lewat CI — runner menulisnya
sendiri dari Secrets. Jangan mengunduh, menyalin, atau mencetak keystore.

Kalau kamu menemukan keystore tak cocok dengan sertifikat rilis terakhir:
**berhenti dan lapor.** Jangan merilis.

---

## Kalau kamu TIDAK punya token

Kamu tak bisa merilis. Katakan terus terang dan serahkan langkah ini ke manusia:

> Buka `https://github.com/djvpri/<repo>/actions/workflows/<berkas>.yml`
> → **Run workflow** → pilih `main` → isi `<versi|version>` → **Run workflow**.

Jangan mencari jalan pintas, jangan mengubah workflow agar bisa dipicu tanpa
token, dan jangan mengaku sudah merilis tanpa bukti dari langkah 5.
