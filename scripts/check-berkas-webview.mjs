#!/usr/bin/env node
// scripts/check-berkas-webview.mjs
//
// Verifikasi murni baca-kode untuk bagian APK yang tak butuh aapt2/JDK:
// pemilih berkas, jembatan JS, dan manifes.
//
// Kenapa terpisah: scripts/check-android-apk.mjs butuh Android SDK (aapt2,
// apkanalyzer) dan hanya bisa jalan di mesin Windows dengan path terpasang.
// Bug "tombol kamera tak merespon" adalah bug KODE, dan pemeriksanya tak perlu
// SDK — jadi dipisah supaya bisa dijalankan di mana pun, termasuk WSL/CI.
//
// Jalankan: node scripts/check-berkas-webview.mjs
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const AKAR = join(dirname(fileURLToPath(import.meta.url)), '..')
const main = readFileSync(
  join(AKAR, 'app/src/main/java/com/zrooms/app/MainActivity.kt'), 'utf8')
const jembatan = readFileSync(
  join(AKAR, 'app/src/main/java/com/zrooms/app/JembatanApk.kt'), 'utf8')
const logweb = readFileSync(
  join(AKAR, 'app/src/main/java/com/zrooms/app/LogWeb.kt'), 'utf8')
const manifest = readFileSync(join(AKAR, 'app/src/main/AndroidManifest.xml'), 'utf8')

let n = 0
const blok = (nama, fn) => { fn(); n++; console.log('  ok -', nama) }

console.log('check-berkas-webview: pemilih berkas & jembatan APK\n')

blok('onShowFileChooser dipasang', () =>
  assert.match(main, /override fun onShowFileChooser\(/,
    'tanpa ini <input type="file"> diam total — bug yang dilaporkan kasir'))

blok('hasil pemilih diteruskan balik ke WebView', () => {
  assert.match(main, /FileChooserParams\.parseResult\(/)
  // Harus ada jalur yang memanggil callback, termasuk saat dibatalkan.
  assert.match(main, /cb\.onReceiveValue\(uris\)/)
  assert.match(main, /mintaBerkas\?\.onReceiveValue\(null\)/,
    'callback lama tak dibalas → halaman web menggantung')
})

blok('Activity Result API dipakai', () =>
  assert.match(main, /registerForActivityResult\(/))

blok('tombol Kamera pakai ACTION_IMAGE_CAPTURE', () =>
  assert.match(main, /ACTION_IMAGE_CAPTURE/))

blok('tombol Pilih file pakai createIntent() (accept-types ikut)', () =>
  assert.match(main, /params\.createIntent\(\)/))

blok('isCaptureEnabled dijaga versi (minSdk 24, API-nya 30)', () => {
  // Pencocokan literal, bukan regex: `Build.VERSION.CODES.R` ditulis apa
  // adanya, dan regex pada rangkaian titik-titik seperti ini gampang salah
  // tanpa terlihat (sudah kejadian: pola yang tampak benar menolak teks yang
  // persis sama).
  assert.ok(
    main.includes('Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && params.isCaptureEnabled'),
    'dipanggil tanpa penjaga versi → crash di Android 7-10')
})

blok('hanya SATU web.webViewClient ditugaskan', () => {
  const jml = (main.match(/^\s*web\.webViewClient\s*=/gm) ?? []).length
  assert.equal(jml, 1,
    `ditugaskan ${jml} kali — yang terakhir menimpa yang pertama, tautan luar mati`)
})

blok('jembatan JS dibatasi host ZXRoom', () => {
  // Dicari PEMANGGILANnya, bukan kemunculan kata: namanya juga muncul di
  // komentar di atasnya, dan mencari kata biasa akan menemukan komentar itu
  // lebih dulu — pemeriksaan jadi lulus/gagal karena alasan yang salah.
  const i = main.indexOf('view.addJavascriptInterface(')
  assert.ok(i > -1, 'jembatan ZXR_APK tidak dipasang')
  assert.ok(
    main.lastIndexOf('if (url.startsWith(BERANDA))', i) > -1,
    'dipasang tanpa batasan host — situs pihak ketiga bisa memanggilnya')
})

blok('jembatan JS hanya satu metode, tanpa argumen', () => {
  const jml = (jembatan.match(/@JavascriptInterface/g) ?? []).length
  assert.equal(jml, 1, `ada ${jml} metode jembatan, harus 1`)
  assert.ok(!/fun kosongkan\([^)]/.test(jembatan), 'metode jembatan tak boleh terima argumen')
})

blok('setiap langkah pemilih dicatat ke log', () => {
  for (const tanda of ['pilih berkas diminta', 'pemilih dibuka', 'LAUNCH GAGAL', 'DIBATALKAN']) {
    assert.ok(main.includes(tanda), `log kehilangan penanda "${tanda}"`)
  }
})

blok('error console web ikut tertangkap', () =>
  assert.match(main, /onConsoleMessage\(.*ConsoleMessage/s))

blok('log APK dikirim ke penangkap web yang sama', () =>
  assert.match(logweb, /console\.error\('\[APK\] '/,
    'log APK harus lewat console.error supaya tertangkap lib/logError.ts'))

blok('halaman bisa memberi tahu APK saat log terkirim', () => {
  assert.match(logweb, /zxr-log-terkirim/)
  assert.match(logweb, /ZXR_APK/)
})

blok('manifes: kamera opsional, bukan izin wajib', () => {
  assert.match(manifest, /android\.hardware\.camera"[^>]*\n?[^>]*required="false"/,
    'tanpa uses-feature required=false, perangkat tanpa kamera tak bisa memasang')
  assert.match(manifest, /hardware\.camera\.autofocus/)
  // Aplikasi memanggil aplikasi KAMERA lain; ia sendiri tak perlu izin CAMERA,
  // dan memintanya justru memunculkan dialog izin yang tak perlu.
  assert.ok(!/android\.permission\.CAMERA/.test(manifest),
    'Z-Rooms tak perlu izin CAMERA — aplikasi kamera meminta izinnya sendiri')
})

blok('versi di alamat.json berbentuk benar', () => {
  const janji = JSON.parse(readFileSync(join(AKAR, 'alamat.json'), 'utf8'))
  // Yang dijaga HUBUNGAN antar data, bukan angkanya. Mengunci "1.0.2"/3 bikin
  // setiap rilis gagal di CI (workflow menaikkan versi SEBELUM memeriksa) dan
  // tak menjaga apa pun — nilainya memang harus berubah tiap rilis.
  assert.match(janji.versiNama, /^\d+\.\d+\.\d+$/,
    'versiNama harus angka bertitik, mis. 1.0.3')
  assert.ok(Number.isInteger(janji.versiKode) && janji.versiKode > 0,
    'versiKode harus bilangan bulat positif — Android menuntutnya untuk update in-place')

  // Kontrak yang benar-benar penting: versiKode harus DIBACA dari alamat.json,
  // bukan ditulis ulang di Gradle. Kalau ditulis ulang, APK melaporkan versi
  // lain daripada yang tertulis di berkas rilis dan update in-place ditolak.
  const gradle = readFileSync(join(AKAR, 'app/build.gradle.kts'), 'utf8')
  assert.match(gradle, /"VERSI_KODE",\s*bacaAlamat\("versiKode"\)/,
    'VERSI_KODE harus dibaca dari alamat.json lewat bacaAlamat("versiKode")')
  assert.match(gradle, /"VERSI_NAMA",[\s\S]{0,60}?bacaAlamat\("versiNama"\)/,
    'VERSI_NAMA harus dibaca dari alamat.json, bukan ditulis ulang')
})

console.log(`\nOK — check-berkas-webview: ${n} blok lulus`)
