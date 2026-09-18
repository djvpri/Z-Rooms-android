# Rilis Android Z-Rooms

Dua jalur: **CI GitHub** (disarankan) dan **lokal** (perlu keystore).

---

## Jalur 1 — CI GitHub (tanpa keystore di mesin)

Repo: https://github.com/djvpri/Z-Rooms-android

Alur: `Actions` → `rilis` → `Run workflow` → isi versi (mis. `1.0.3`) → Run.

Workflow `.github/workflows/rilis.yml` melakukan sendiri:

1. naikkan `versiNama` + `versiKode` di `alamat.json` (`versiKode` = sebelumnya + 1)
2. tulis keystore + `app/keystore.properties` dari **Secrets**
3. `./gradlew clean assembleDebug assembleRelease`
4. jalankan pemeriksa: `check-android-apk.mjs` (18 blok) + `check-berkas-webview.mjs` (15 blok)
5. salin APK ke `rilis/Z-Rooms-<versi>.apk`
6. commit `alamat.json` + APK, dorong ke `main`
7. buat tag `v<versi>` + GitHub Release dengan APK terlampir

### Secrets yang harus dipasang sekali

`Settings` → `Secrets and variables` → `Actions` → `New repository secret`

| Nama | Isi |
| --- | --- |
| `KEYSTORE_B64` | isi `zrooms-release.keystore` dalam base64 (satu baris) |
| `KEYSTORE_PASSWORD` | `storePassword` dari `keystore.properties` |
| `KEYSTORE_ALIAS` | `keyAlias` — `zrooms` |
| `KEYSTORE_KEY_PASSWORD` | `keyPassword` dari `keystore.properties` |

Buat `KEYSTORE_B64`:

```bash
base64 -w0 zrooms-release.keystore
```

(macOS: `base64 -i zrooms-release.keystore`)

Nilai keempat secret itu **tidak** ditulis di repo ini. Simpan di tempat
rahasia milikmu (pengelola sandi).

### Kalau `KEYSTORE_B64` kosong

Workflow **berhenti** dengan pesan yang jelas. Itu disengaja: build tanpa
keystore produksi jatuh ke keystore **debug**, APK-nya jadi, tapi Android
menolak memasangnya menimpa versi resmi di perangkat kasir — dan itu baru
ketahuan saat kasir memasangnya.

---

## Jalur 2 — Lokal (perlu keystore)

Dua berkas ini **gitignored** (`*.keystore`, `keystore.properties`), jadi
`git clone` tidak memberikannya. Salin manual ke:

```
zrooms-android/zrooms-release.keystore
zrooms-android/app/keystore.properties
```

`keystore.properties`:

```properties
storeFile=../zrooms-release.keystore
storePassword=<password>
keyAlias=zrooms
keyPassword=<password>
```

Jalankan:

```bash
cd /c/Users/KBK065/zrooms-android
bash scripts/verify.sh        # build bersih + 18 blok APK + 15 blok WebView + 4 uji gigit
cp app/build/outputs/apk/release/app-release.apk rilis/Z-Rooms-<versi>.apk
git add rilis/Z-Rooms-<versi>.apk && git commit -m 'rilis: ...'
git push && git tag -a v<versi> -m '...' && git push origin v<versi>
```

Naikkan `versiNama` + `versiKode` di `alamat.json` **sebelum** build.
Android menolak memasang `versionCode` yang sama atau lebih kecil.

---

## Sertifikat produksi — patokan

**Setiap APK rilis wajib memakai sertifikat ini.** Kalau berbeda, Android
menolak memasang menimpa dan kasir harus uninstall dulu (data WebView hilang).

| Item | Nilai |
| --- | --- |
| DN | `CN=Z-Rooms, OU=Dev, O=Z-Rooms, L=Jakarta, ST=DKI, C=ID` |
| SHA-256 | `a9426745a4529a9fbb3fa2f86cadcca3db2208c31707d8d9abcd5d1e89f9a48e` |
| SHA-1 | `21e8e814418a4b149435b50e5f1b23e90ff66486` |

Sidik jari `zrooms-release.keystore` sendiri:

```
SHA-256 a00dcabbe4289b1b2c79fcd4be22ff187c5402d8fbd5c85b2f58ab971675cf58
2 712 byte
```

Periksa APK mana pun:

```bash
/c/Android/Sdk/build-tools/35.0.0/apksigner.bat verify --print-certs <apk>
```

`check-android-apk.mjs` sudah menegakkan ini: blok *"versi rilis memakai
sertifikat yang sama dengan APK rilis resmi"* membandingkan SHA-256 APK baru
dengan APK terakhir di `rilis/`.

---

## Alamat situs di dalam APK

Satu sumber: `alamat.json`. `beranda` ditanam sebagai `BuildConfig.BERANDA`;
`MainActivity.kt` membacanya dari sana, tidak menyalin. `check-android-apk.mjs`
menggigit kalau nilainya disalin ulang.

```json
{
  "beranda": "https://zxroom.zomet.my.id",
  "idPaket": "com.zrooms.app",
  "versiNama": "1.0.2",
  "versiKode": 3
}
```
