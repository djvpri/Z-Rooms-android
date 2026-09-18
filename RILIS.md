# Rilis ZXRoom — cara untuk agent maupun manusia

Dokumen ini menjawab satu pertanyaan: **bagaimana cara menerbitkan rilis APK
baru**, dan apa yang harus dibaca lebih dulu.

> **Agent yang menerima tugas merilis:** baca **`RILIS-AGENT.md`** lebih dulu.
> Isinya prosedur langkah-demi-langkah khusus agent (perintah, kode HTTP,
> verifikasi) dan mencakup Z1 Label juga. Dokumen ini latar belakangnya.

Kalau kamu agent yang diminta merilis versi terbaru, baca sampai bagian
"Jalur A" dan ikuti. Jangan mengarang langkah dari nol — seluruh rantai sudah
diuji (42 pemeriksaan, lihat `scripts/verifikasi-rilis.sh`).

---

## Ringkas: apa yang dibutuhkan untuk merilis

| Bahan | Di mana | Siapa yang perlu |
| --- | --- | --- |
| Kode | repo PUBLIK `djvpri/Z-Rooms-android` | semua |
| 4 rahasia (keystore base64, 2 sandi, alias) | GitHub Actions **Secrets** repo itu | tak seorang pun secara langsung |
| Hak picu workflow | akun `djvpri` | jalur B |
| Tombol "Run workflow" di UI | web GitHub | jalur A |
| Build toolchain | tak ada — dijalankan runner GitHub | — |

**Yang TIDAK dibutuhkan:** keystore lokal, token di kode, atau Android SDK lokal.
Keystore hidup di Secrets sebagai base64; runner menuliskannya sendiri.

**Keystore lokal hanya untuk build rilis di laptop.** Kalau laptop hilang,
keystore bisa dibuat ulang dari berkas base64 di Secrets — jadi ia bukan satu
titik gagal. Jangan pernah menaruhnya di repo publik.

---

## Jalur A — agent lain / siapa pun, lewat web GitHub (TANPA token)

Jalur ini tidak butuh kredensial apa pun, tetapi **butuh hak tulis ke repo**.

1. Buka https://github.com/djvpri/Z-Rooms-android/actions/workflows/rilis.yml
2. Klik **Run workflow** (kanan atas), pilih branch `main`.
3. Isi input `versi` dengan versi baru, mis. `1.0.4`. **Wajib** — workflow
   berhenti kalau kosong.
4. Klik **Run workflow** hijau.
5. Tunggu ~4 menit. Workflow melakukan semuanya:
   naikkan versi → tulis keystore dari Secrets → build → periksa → arsip APK →
   commit versi + arsip → push → tag `v1.0.4` → buat Release + lampirkan APK.

Versi **harus naik** dari yang ada. Cek dulu versi sekarang:

```bash
curl -sS https://api.github.com/repos/djvpri/Z-Rooms-android/releases/latest \
  | grep -oE '"tag_name": *"[^"]+"'
```

> **Kalau kamu agent:** jalur ini tidak bisa kamu kerjakan. Langkah 2–4 adalah
> klik di peramban, bukan perintah. Kalau punya izin, pakai Jalur B. Kalau tidak,
> minta pemilik repo menjalankannya — lampirkan langkah 1–4 di atas sebagai
> instruksi persis, jangan menebak.

---

## Jalur B — agent dengan akses token (tanpa klik)

Butuh PAT `djvpri` dengan scope `repo` + `workflow`. Enam panggilan curl:

```bash
# 0. Ambil token dari credential store (JANGAN cetak, JANGAN commit)
printf 'protocol=https\nhost=github.com\n\n' | git credential fill \
  | awk -F= '/^password=/{print substr($0,10)}'

# 1. Lihat versi terakhir yang sudah terbit
curl -sS --compressed --http1.1 \
  https://api.github.com/repos/djvpri/Z-Rooms-android/releases/latest \
  | grep -oE '"tag_name": *"[^"]+"'

# 2. Picu workflow  (versi HARUS lebih tinggi dari langkah 1)
curl -sS --compressed --http1.1 -X POST \
  -H "Authorization: Bearer $TOKEN" \
  https://api.github.com/repos/djvpri/Z-Rooms-android/actions/workflows/rilis.yml/dispatches \
  -d '{"ref":"main","inputs":{"versi":"1.0.4"}}'
# HTTP 204 = diterima. 422 = input salah. 401/403 = token kurang scope.

# 3. Ambil id run terbaru.  CATATAN: respons memuat banyak field "id"
#    (owner, repo, actor). `grep '"id"' | head -1` mengambil yang SALAH.
#    Field yg benar ada di dalam workflow_runs[0].id.
curl -sS --compressed --http1.1 -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/djvpri/Z-Rooms-android/actions/runs?per_page=1" \
  | python -c "import json,sys; w=json.load(sys.stdin)['workflow_runs']; print(w[0]['id'] if w else '')"

# 3b. Atau ambil langsung per-workflow (lebih pasti):
curl -sS --compressed --http1.1 -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/djvpri/Z-Rooms-android/actions/workflows/rilis.yml/runs?per_page=1" \
  | python -c "import json,sys; w=json.load(sys.stdin)['workflow_runs']; print(w[0]['id'] if w else '')"

# 4. Pantau status  (queued -> in_progress -> completed)
curl -sS --compressed --http1.1 -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/djvpri/Z-Rooms-android/actions/runs/$RUN_ID" \
  | grep -oE '"status": *"[^"]+"|"conclusion": *"[^"]+"'

# 5. Langkah mana yang gagal (kalau ada)
curl -sS --compressed --http1.1 -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/djvpri/Z-Rooms-android/actions/runs/$RUN_ID/jobs" \
  | grep -oE '"name": *"[^"]+"|"conclusion": *"[^"]+"'

# 6. Log mentah langkah yg gagal
curl -sSL --compressed --http1.1 -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/djvpri/Z-Rooms-android/actions/jobs/$JOB_ID/logs"
```

Catatan curl di Windows: selalu `--compressed --http1.1`. Tanpa itu sebagian
proxy menolak. Jangan lewatkan token sebagai argumen yang terlihat di riwayat
perintah — simpan di variabel.

**Kalau token tak ada di mesinmu:** jangan mencoba jalur lain diam-diam. Beri
tahu pemilik repo bahwa kamu butuh PAT dengan scope `repo`+`workflow`, atau
minta ia memakai Jalur A.

---

## Jalur C — rilis dari laptop sendiri

Hanya kalau GitHub Actions tidak bisa dipakai.

Prasyarat: `zrooms-release.keystore` + `app/keystore.properties` ada (keduanya
gitignored), JDK 17, Android SDK build-tools 35.0.0.

```bash
./gradlew.bat :app:assembleRelease
bash scripts/verifikasi-rilis.sh          # verifikasi penuh
```

Rilis lokal **tidak** membuat tag/Release GitHub. Buat manual setelahnya, dan
lampirkan APK — updater membaca aset `.apk` dari `/releases/latest`, jadi
Release tanpa aset = update tak akan pernah terdeteksi pengguna.

> Peringatan: APK yang ditandatangani keystore berbeda **tidak bisa** dipasang
> menimpa yang terpasang. Android menolaknya dengan "app not installed". Pakai
> keystore produksi yang sama, selalu.

### Keystore TIDAK bisa dipulihkan dari Secrets — baca ini

Kesalahpahaman yang mudah terjadi: "kan keystore ada di Secrets, jadi aman."
**Tidak.** Sudah diuji langsung di repo ini — `GET /actions/secrets/KEYSTORE_B64`
membalas **HTTP 200**, tapi isinya **hanya metadata**:

```json
{ "name": "KEYSTORE_B64",
  "created_at": "2026-09-18T02:09:46Z",
  "updated_at": "2026-09-18T02:09:46Z" }
```

Nol field nilai — bukan `value`, bukan `encrypted_value`. Jadi bukan "dilarang
404", melainkan **nilainya memang tak pernah dikirim**. Kunci publik repo
(`/actions/secrets/public-key`) hanya untuk **menulis** secret baru.

> Kalau `zrooms-release.keystore` di laptop hilang, ia **hilang permanen**.
> Secrets tidak bisa mengembalikannya.

Akibatnya, dan ini berat:

- semua HP yang sudah terpasang **tidak akan pernah bisa** update lagi
- Android menolak APK bertanda-tangan berbeda saat memasang menimpa
- pengguna harus menghapus + pasang ulang manual, dan kehilangan data lokal

**Karena itu: simpan salinan base64 keystore + `keystore.properties` di tempat
yang kamu kendalikan** — password manager, atau repo privat. Itu satu-satunya
cadangan yang benar. Jangan pernah andalkan Secrets sebagai salinan tunggal.

Cara memastikan keystore lokal masih yang benar sebelum rilis — bandingkan
sertifikatnya dengan rilis terakhir:

```bash
export JAVA_HOME='C:\jdk\jdk-17.0.20+8'
keytool -list -v -keystore zrooms-release.keystore -storepass <PASSWORD> \
  | grep -i 'SHA256:'
# harus sama dengan sertifikat rilis terakhir:
#   a9426745a4529a9fbb3fa2f86cadcca3db2208c31707d8d9abcd5d1e89f9a48e
```

Atau lebih pasti, bandingkan APK-nya langsung:

```bash
"$BT/apksigner.bat" verify --print-certs "$(cygpath -w rilis/Z-Rooms-1.0.3.apk)" \
  | grep -i 'SHA-256 digest'
```

Kalau berbeda, **jangan rilis**. Build-nya sia-sia: tak ada pengguna yang bisa
memasangnya menimpa.

**Mengganti secret di GitHub:** kalau punya salinan base64 baru, tulis ulang
lewat API (nilai dienkripsi sealed-box dengan kunci publik repo dulu, lalu
`PUT /actions/secrets/{name}`). Setelah itu workflow otomatis memakai yang baru.

---

## Verifikasi setelah rilis

Ini bagian yang paling sering dilewatkan, dan yang membedakan rilis yang
"tampak berhasil" dari yang benar-benar berhasil.

```bash
bash scripts/verifikasi-rilis.sh          # penuh, build dari nol (~3 menit)
bash scripts/verifikasi-rilis.sh --cepat  # pakai APK yg sudah ada (~40 detik)
```

Exit 0 = rantai utuh. Ia memeriksa hal-hal yang tak terlihat dari hijau/merah
workflow: manifest **terkompilasi di dalam APK**, isi `file_paths.xml` yang
sebenarnya, sha256 aset unduhan vs arsip, dan sertifikat penandatangan yang
harus cocok agar update in-place diterima.

**Setelah CI hijau, tetap periksa tiga ini secara manual** — skrip tidak bisa
melihatnya:

1. `releases/latest` = tag baru, dan **aset `.apk` benar-benar terlampir**.
2. `versionCode` di APK naik dari rilis sebelumnya.
3. Sertifikat APK baru = sertifikat rilis sebelumnya.

Nomor 1 adalah penyebab paling umum update "tak muncul": Release terbit tapi
aset APK lupa dilampirkan.

---

## Jebakan yang sudah memakan waktu (jangan diulang)

Semua ini nyata dan sudah diuji. Baca sebelum menuduh kode aplikasi rusak.

**Alat Windows menelan bukti kalau stderr dibisukan.** `aapt2.exe` dan
`apksigner.bat` adalah binary Windows: mereka tidak paham path MSYS
`/c/Users/...`. Tanpa `cygpath` mereka gagal, dan bila `2>/dev/null` dipasang,
gejalanya jadi "keluaran kosong" yang tampak persis seperti bug kode. Skrip ini
punya pembungkus `aaptx()` untuk itu. Jangan tambahkan `2>/dev/null`.

**CRLF dari alat Windows membuat `grep` dan `[ = ]` berbohong.** `grep '^x$'`
tak akan pernah cocok karena `\r` mendahului newline.

**`dump xmlstrings` memberi prefix.** Keluarannya `String #0 : cache-path`, jadi
pola `^cache-path$` salah. Dan assertion negatif seperti
`! grep -qE '^(external-path|...)$'` akan **selalu lolos** tanpa pengupasan
prefix — hijau palsu yang tidak menguji apa pun. Skrip ini memasangkannya dengan
uji positif ("hitung untai terparsing ≥ 5") supaya parsing kosong langsung
ketahuan.

**MSYS `sha256sum` menyisipkan `\` di depan hash** ketika argumennya path
Windows. Itu penanda escape, bukan CR. Ambil 64 hex pertama, jangan
`awk '{print $1}'`.

**Jangan bandingkan APK dengan `ls | head -1`.** Urutan alfabet memilih versi
terlama; sha256 lalu dilaporkan "beda" padahal asetnya benar. Pilih arsip yang
tag-nya sama.

**Jangan tulis pembanding versi di pemeriksa.** Mengunci `versiNama`/`versiKode`
membuat pemeriksa gagal begitu versi naik — persis yang mematahkan CI ZXRoom.
Jaga **hubungan** antar data, bukan angkanya.

**`versionCode` wajib masuk git.** Kalau hanya dinaikkan di runner, `main`
berbohong soal apa yang dirilis dan agent berikutnya salah hitung.

**`android-actions/setup-android@v3` jangan dipakai di runner ini.** Aksinya
menganggap SDK praterpasang "salah versi" lalu menjalankan `sdkmanager tools`,
paket yang sudah dihapus. SDK sudah ada; pakai langsung.

---

## Rujukan

- Skill agent: `github-release-ci-tanpa-gh-cli` (pitfall #9–#12 berisi detail
  teknis di atas, termasuk perintah diagnosis).
- Pemeriksa repo: `scripts/check-berkas-webview.mjs`, `scripts/check-android-apk.mjs`.
- Workflow: `.github/workflows/rilis.yml`.
