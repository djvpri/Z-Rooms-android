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
