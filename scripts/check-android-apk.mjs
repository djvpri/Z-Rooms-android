#!/usr/bin/env node
// Mengunci syarat yang mudah dilewatkan proyek WebView tipis.
//
// Kenapa ada: `assembleDebug` PERNAH lulus sempurna sementara MainActivity
// sama sekali tidak ada di dalam APK — plugin Kotlin belum dipasang, jadi
// seluruh berkas .kt diam-diam diabaikan. Build hijau, aplikasi kosong.
// Baca DEX yang sebenarnya, bukan "BUILD SUCCESSFUL".
//
// Jalankan: node scripts/check-android-apk.mjs

import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { inflateRawSync } from 'node:zlib'

const AKAR = 'C:/Users/KBK065/zrooms-android'
const APK = join(AKAR, 'app/build/outputs/apk/debug/app-debug.apk')
const SDK = 'C:/Android/Sdk'
const AAPT = join(SDK, 'build-tools/35.0.0/aapt2.exe')
const ANALYZER = join(SDK, 'cmdline-tools/latest/bin/apkanalyzer.bat')

let lulus = 0
function blok(nama, fn) {
  fn()
  lulus++
  console.log(`  ok  ${nama}`)
}

/**
 * Baca daftar kelas di dalam DEX.
 *
 * `execFileSync` di Node TIDAK bisa meluncurkan `.bat` di Windows (EINVAL),
 * jadi dipanggil lewat cmd.exe. Keluarannya besar, karena itu di-buffer
 * sendiri, bukan lewat maxBuffer execFileSync.
 */
function bacaDex(apk) {
  return execFileSync('cmd.exe', ['/c', ANALYZER, 'dex', 'packages', apk], {
    encoding: 'utf8',
    maxBuffer: 128 * 1024 * 1024,
  })
}

/**
 * Cari semua untai teks di dalam seluruh .dex, apa adanya.
 *
 * Kenapa tidak pakai `apkanalyzer dex packages`: perintah itu hanya
 * mencetak nama kelas/metode, BUKAN tabel string. Alamat situs disimpan
 * sebagai untai, jadi lewat sana ia tak akan pernah terlihat walau ada.
 *
 * Juga: D8 memecah untai panjang, jadi `https://zxroom.zomet.my.id` bisa
 * tersimpan terpisah. Karena itu yang dicari potongan tanpa skema.
 */
function cariUntai(apk, potongan) {
  const buf = readFileSync(apk)
  const isi = []
  let p = 0
  // Telusuri entri zip secara kasar: cari signature lokal "PK\x03\x04",
  // lalu baca nama + data. Cukup untuk mengambil seluruh .dex.
  while (p < buf.length - 30) {
    if (buf.readUInt32LE(p) !== 0x04034b50) { p++; continue }
    const metode = buf.readUInt16LE(p + 8)
    const ukuranPadat = buf.readUInt32LE(p + 18)
    const namaPanjang = buf.readUInt16LE(p + 26)
    const tambahanPanjang = buf.readUInt16LE(p + 28)
    const nama = buf.toString('utf8', p + 30, p + 30 + namaPanjang)
    const mulai = p + 30 + namaPanjang + tambahanPanjang
    const padat = buf.subarray(mulai, mulai + ukuranPadat)
    if (nama.endsWith('.dex')) {
      isi.push(metode === 8 ? inflateRawSync(padat) : padat)
    }
    p = mulai + ukuranPadat
  }
  const gabung = Buffer.concat(isi).toString('latin1')
  return gabung.includes(potongan)
}

console.log('check-android-apk: proyek WebView Z-Rooms\n')

blok('proyek ada di disk', () => {
  assert.ok(existsSync(join(AKAR, 'settings.gradle.kts')), 'settings.gradle.kts hilang')
  assert.ok(existsSync(join(AKAR, 'app/src/main/java/com/zrooms/app/MainActivity.kt')),
    'MainActivity.kt hilang')
})

blok('local.properties memakai garis miring depan (jebakan Windows)', () => {
  const lp = readFileSync(join(AKAR, 'local.properties'), 'utf8')
  assert.match(lp, /sdk\.dir=[A-Z]:\/[^\s\\]+/,
    'sdk.dir harus C:/Android/Sdk — garis miring terbalik bikin build gagal senyap')
})

blok('plugin Kotlin dipasang (kalau tidak, .kt diabaikan tanpa peringatan)', () => {
  const app = readFileSync(join(AKAR, 'app/build.gradle.kts'), 'utf8')
  // Baris komentar sengaja diabaikan: `includes('org.jetbrains.kotlin.android')`
  // tetap cocok kalau pluginnya dimatikan dengan `//` — checker yang tak gigit.
  assert.ok(/^\s*id\("org\.jetbrains\.kotlin\.android"\)/m.test(app),
    'app/build.gradle.kts tak punya plugin kotlin.android aktif — MainActivity akan hilang dari APK')
  const akar = readFileSync(join(AKAR, 'build.gradle.kts'), 'utf8')
  assert.match(akar, /^\s*id\("org\.jetbrains\.kotlin\.android"\)\s+version\s+"[\d.]+"/m,
    'versi plugin Kotlin belum dikunci di build.gradle.kts akar')
})

blok('useAndroidX menyala (appcompat/webkit semuanya AndroidX)', () => {
  const gp = readFileSync(join(AKAR, 'gradle.properties'), 'utf8')
  assert.match(gp, /android\.useAndroidX=true/)
})

blok('APK sudah dibangun', () => {
  assert.ok(existsSync(APK), `APK belum ada di ${APK} — jalankan: ./gradlew.bat assembleDebug`)
  assert.ok(statSync(APK).size > 1_000_000, 'APK mencurigakan kecil')
})

blok('izin INTERNET ada di manifest terkompilasi', () => {
  const out = execFileSync(AAPT, ['dump', 'badging', APK], { encoding: 'utf8' })
  assert.match(out, /uses-permission: name='android\.permission\.INTERNET'/,
    'tanpa INTERNET WebView tak bisa memuat apa pun')
})

blok('identitas paket & versi benar', () => {
  const out = execFileSync(AAPT, ['dump', 'badging', APK], { encoding: 'utf8' })
  assert.match(out, /package: name='com\.zrooms\.app'/)
  assert.match(out, /minSdkVersion:'24'/)
  assert.match(out, /targetSdkVersion:'35'/)
})

blok('MainActivity benar-benar ada DI DALAM DEX', () => {
  const out = bacaDex(APK)
  assert.ok(out.includes('com.zrooms.app.MainActivity'),
    'MainActivity tak ada di DEX — build hijau tapi aplikasi kosong. Cek plugin Kotlin.')
  assert.ok(out.includes('void onCreate(android.os.Bundle)'), 'onCreate tak terkompilasi')
})

blok('satu-satunya Activity yang bisa diluncurkan', () => {
  const out = execFileSync(AAPT, ['dump', 'badging', APK], { encoding: 'utf8' })
  assert.match(out, /launchable-activity: name='com\.zrooms\.app\.MainActivity'/,
    'MainActivity bukan activity yang bisa diluncurkan')
})

blok('cookie pihak-ketiga dinyalakan (syarat login NextAuth)', () => {
  const src = readFileSync(
    join(AKAR, 'app/src/main/java/com/zrooms/app/MainActivity.kt'), 'utf8')
  assert.match(src, /setAcceptThirdPartyCookies\(web, true\)/,
    'tanpa ini cookie __Host-authjs ditolak dan login tak nyangkut')
})

blok('alamat situs menunjuk host produksi Z-Rooms', () => {
  const src = readFileSync(
    join(AKAR, 'app/src/main/java/com/zrooms/app/MainActivity.kt'), 'utf8')
  assert.match(src, /BERANDA = "https:\/\/zxroom\.zomet\.my\.id"/,
    'alamat beranda salah atau bukan HTTPS')
})

blok('satu sumber kebenaran: alamat.json vs MainActivity vs build.gradle.kts', () => {
  // Tiga tempat menyebut alamat/versi. Kalau salah satu diubah dan yang lain
  // tidak, aplikasi memuat situs lama tanpa gejala apa pun. Blok ini yang
  // memperingatkan. Ubah alamat.json lebih dulu.
  const janji = JSON.parse(readFileSync(join(AKAR, 'alamat.json'), 'utf8'))
  assert.ok(janji.beranda, 'alamat.json: kolom "beranda" hilang')

  const kotlin = readFileSync(
    join(AKAR, 'app/src/main/java/com/zrooms/app/MainActivity.kt'), 'utf8')
  assert.ok(kotlin.includes(`BERANDA = "${janji.beranda}"`),
    `MainActivity.BERANDA tidak sama dengan alamat.json ("${janji.beranda}")`)

  const app = readFileSync(join(AKAR, 'app/build.gradle.kts'), 'utf8')
  assert.ok(app.includes(`applicationId = "${janji.idPaket}"`),
    `applicationId tidak sama dengan alamat.json ("${janji.idPaket}")`)
  assert.ok(app.includes(`versionName = "${janji.versiNama}"`),
    `versionName tidak sama dengan alamat.json ("${janji.versiNama}")`)

  // APK yang sudah dibangun harus memakai alamat yang sama juga.
  if (existsSync(APK)) {
    const badging = execFileSync(AAPT, ['dump', 'badging', APK], { encoding: 'utf8' })
    assert.ok(badging.includes(`package: name='${janji.idPaket}'`),
      'APK memakai applicationId lama — bangun ulang')
    assert.ok(badging.includes(`versionName='${janji.versiNama}'`),
      'APK memakai versionName lama — bangun ulang')
    // Alamat dicek lewat tabel untai DEX, tanpa skema: D8 memecah untai
    // panjang, jadi "https://" dan "zxroom.zomet.my.id" bisa terpisah.
    const tanpaSkema = janji.beranda.replace(/^https?:\/\//, '')
    assert.ok(cariUntai(APK, tanpaSkema),
      'alamat di dalam APK berbeda dari alamat.json — bangun ulang')
  }
})

// --- APK rilis: hanya diperiksa kalau sudah dibangun -----------------------

const APK_RILIS = join(AKAR, 'app/build/outputs/apk/release/app-release.apk')

if (existsSync(APK_RILIS)) {
  const AKSIGNER = join(SDK, 'build-tools/35.0.0/apksigner.bat')

  blok('APK rilis ada', () => {
    assert.ok(statSync(APK_RILIS).size > 1_000_000, 'APK rilis mencurigakan kecil')
  })

  blok('APK rilis ditandatangani sertifikat Z-Rooms, bukan debug', () => {
    // apksigner itu .bat — Node tak bisa execFileSync langsung (EINVAL),
    // jadi lewat cmd.exe seperti apkanalyzer di atas.
    const out = execFileSync('cmd.exe', ['/c', AKSIGNER, 'verify', '--print-certs', APK_RILIS], {
      encoding: 'utf8', maxBuffer: 16 * 1024 * 1024,
    })
    assert.match(out, /certificate DN: CN=Z-Rooms/,
      'APK rilis masih memakai keystore debug — Android akan menolak menimpa versi resmi')
    assert.ok(!/CN=Android Debug/.test(out), 'APK rilis bertanda tangan debug')
  })

  blok('versi rilis bertanda tangan sama dengan debug (satu basis kode)', () => {
    const out = bacaDex(APK_RILIS)
    assert.ok(out.includes('com.zrooms.app.MainActivity'), 'MainActivity tak ada di DEX rilis')
  })
} else {
  console.log('  --  APK rilis belum dibangun; blok rilis dilewati')
}

console.log(`\n${lulus} blok lulus`)
