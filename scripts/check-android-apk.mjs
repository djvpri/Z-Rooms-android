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
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { inflateRawSync } from 'node:zlib'

// Akar proyek & SDK dicari, bukan ditulis mati.
//
// Sebelumnya keduanya dipatok ke path Windows (`C:/Users/KBK065/...`,
// `C:/Android/Sdk`), sehingga pemeriksaan ini hanya bisa jalan di satu mesin —
// tak bisa di WSL, CI, atau mesin lain, dan gagal dengan pesan yang
// membingungkan kalau folder proyeknya dipindah.
//
// Urutan: argumen CLI, lalu variabel lingkungan, lalu lokasi lazim, lalu
// disimpulkan dari letak berkas skrip ini (skrip selalu ada di <akar>/scripts).
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const AKAR = process.argv[2]
  ?? process.env.ZROOMS_AKAR
  ?? join(dirname(fileURLToPath(import.meta.url)), '..')
const SDK = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT ?? ''

/** Nama biner build-tools berbeda per OS (.exe/.bat di Windows). */
const ext = process.platform === 'win32' ? '.exe' : ''
const extBat = process.platform === 'win32' ? '.bat' : ''

const APK = join(AKAR, 'app/build/outputs/apk/debug/app-debug.apk')
const AAPT = SDK ? join(SDK, `build-tools/35.0.0/aapt2${ext}`) : ''
const ANALYZER = SDK ? join(SDK, `cmdline-tools/latest/bin/apkanalyzer${extBat}`) : ''

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
 * jadi di Windows dipanggil lewat cmd.exe. Di Linux/macOS `apkanalyzer` adalah
 * skrip yang bisa dijalankan langsung — dan `cmd.exe` tentu tak ada di sana.
 * Keluarannya besar, karena itu di-buffer sendiri.
 */
function bacaDex(apk) {
  const args = ['dex', 'packages', apk]
  return execFileSync(
    process.platform === 'win32' ? 'cmd.exe' : ANALYZER,
    process.platform === 'win32' ? ['/c', ANALYZER, ...args] : args,
    { encoding: 'utf8', maxBuffer: 128 * 1024 * 1024 },
  )
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
  // Yang dijaga: di Windows, `sdk.dir=C:\Android\Sdk` (garis miring terbalik)
  // membuat Gradle gagal tanpa pesan yang jelas. Garis miring depan (C:/...)
  // dan path gaya Unix dua-duanya diterima — yang ditolak hanya garis miring
  // terbalik, supaya pemeriksaan ini tetap berguna di WSL/CI.
  const lp = readFileSync(join(AKAR, 'local.properties'), 'utf8')
  const baris = lp.split('\n').find((b) => b.startsWith('sdk.dir='))
  assert.ok(baris, 'local.properties tak memuat sdk.dir')
  assert.ok(!baris.includes('\\'),
    'sdk.dir memakai garis miring terbalik — bikin build gagal senyap di Windows')
  assert.match(baris, /sdk\.dir=(\/|[A-Z]:\/).+/, 'sdk.dir harus path absolut')
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

blok('pemilih berkas & kamera dipasang (tombol booking tak boleh diam)', () => {
  // Bug nyata: kasir menekan "Kamera" / "Pilih file" di tab booking baru dan
  // tak ada yang terjadi, tanpa pesan apa pun. Sebabnya WebView TIDAK punya UI
  // pemilih berkas sendiri — tanpa onShowFileChooser, <input type="file">
  // diabaikan diam-diam.
  //
  // Diperiksa dari KODE SUMBER, bukan DEX: nama metode bisa dipangkas R8, dan
  // yang penting perilakunya ada di sumber yang dibangun.
  const src = readFileSync(
    join(AKAR, 'app/src/main/java/com/zrooms/app/MainActivity.kt'), 'utf8')

  assert.match(src, /override fun onShowFileChooser\(/,
    'onShowFileChooser tidak ada — <input type="file"> akan diam total')
  assert.match(src, /FileChooserParams\.parseResult\(/,
    'hasil pemilih tak diteruskan balik ke WebView')
  // Callback WAJIB dipanggil, termasuk saat dibatalkan. Kalau tidak, halaman
  // web menunggu selamanya dan tombolnya tampak rusak padahal cuma dibatalkan.
  assert.match(src, /registerForActivityResult\(/,
    'Activity Result API tidak dipakai untuk menerima hasil pemilih')
  // ACTION_IMAGE_CAPTURE untuk capture="environment" (tombol "Kamera"),
  // ACTION_GET_CONTENT dari createIntent() untuk "Pilih file".
  assert.match(src, /ACTION_IMAGE_CAPTURE/,
    'tombol "Kamera" tidak memakai ACTION_IMAGE_CAPTURE')
  assert.match(src, /params\.createIntent\(\)/,
    'tombol "Pilih file" tidak memakai createIntent() (accept-types hilang)')

  // isCaptureEnabled baru ada di API 30 sementara minSdk 24 — tanpa penjaga
  // versi, aplikasi crash di Android 7-10.
  //
  // Pencocokan literal, bukan regex: nama konstanta ini panjang dan bertitik,
  // dan pola regex untuknya gampang salah tanpa terlihat.
  assert.ok(
    src.includes('Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && params.isCaptureEnabled'),
    'isCaptureEnabled dipanggil tanpa penjaga versi → crash di Android 7-10')
})

blok('hanya satu WebViewClient (yang kedua menimpa yang pertama)', () => {
  // Dua penugasan `web.webViewClient` membuat yang terakhir menang, dan
  // shouldOverrideUrlLoading (tautan luar ke browser) mati tanpa gejala.
  const src = readFileSync(
    join(AKAR, 'app/src/main/java/com/zrooms/app/MainActivity.kt'), 'utf8')
  const n = (src.match(/^\s*web\.webViewClient\s*=/gm) ?? []).length
  assert.equal(n, 1, `web.webViewClient ditugaskan ${n} kali, harus 1`)
})

blok('jembatan JS hanya untuk host ZXRoom', () => {
  // addJavascriptInterface bisa dipanggil skrip mana pun yang termuat di
  // WebView — tanpa syarat host, situs pihak ketiga bisa memanggilnya.
  //
  // Dicari PEMANGGILANnya, bukan kemunculan kata: namanya juga muncul di
  // komentar di atasnya, dan mencari kata biasa akan menemukan komentar itu
  // lebih dulu — pemeriksaan jadi lulus/gagal karena alasan yang salah.
  const src = readFileSync(
    join(AKAR, 'app/src/main/java/com/zrooms/app/MainActivity.kt'), 'utf8')
  const i = src.indexOf('view.addJavascriptInterface(')
  assert.ok(i > -1, 'jembatan ZXR_APK tidak dipasang')
  assert.ok(src.lastIndexOf('if (url.startsWith(BERANDA))', i) > -1,
    'addJavascriptInterface dipasang tanpa batasan host')
})

blok('alamat situs menunjuk host produksi Z-Rooms, dengan HTTPS', () => {
  const janji = JSON.parse(readFileSync(join(AKAR, 'alamat.json'), 'utf8'))
  assert.match(janji.beranda, /^https:\/\/zxroom\.zomet\.my\.id$/,
    'alamat.json: "beranda" salah atau bukan HTTPS')
})

blok('satu sumber kebenaran: nilai tak disalin ulang di berkas lain', () => {
  // Alamat, id paket, dan versi hidup di alamat.json saja. build.gradle.kts
  // membacanya, dan MainActivity mengambil alamat dari BuildConfig.
  //
  // Yang dijaga blok ini: kalau seseorang menyalin nilai itu kembali ke
  // Kotlin atau menuliskannya langsung di Gradle, salinannya akan menyimpang
  // diam-diam saat alamat.json berubah — aplikasi memuat situs lama tanpa
  // gejala apa pun.
  const janji = JSON.parse(readFileSync(join(AKAR, 'alamat.json'), 'utf8'))
  for (const k of ['beranda', 'idPaket', 'versiNama']) {
    assert.ok(janji[k], `alamat.json: kolom "${k}" hilang`)
  }
  assert.equal(Number.isInteger(janji.versiKode), true,
    'alamat.json: "versiKode" harus bilangan bulat')

  const kotlin = readFileSync(
    join(AKAR, 'app/src/main/java/com/zrooms/app/MainActivity.kt'), 'utf8')
  // Kotlin harus MEMBACA dari BuildConfig, bukan menyalin nilainya.
  assert.ok(kotlin.includes('const val BERANDA = BuildConfig.BERANDA'),
    'MainActivity.BERANDA harus memakai BuildConfig.BERANDA, bukan alamat yang ditulis ulang')
  assert.ok(!kotlin.includes('zxroom.zomet.my.id'),
    'MainActivity menyalin alamat dari alamat.json — hapus, pakai BuildConfig.BERANDA')

  const app = readFileSync(join(AKAR, 'app/build.gradle.kts'), 'utf8')
  // `namespace` memang literal (itu identitas ruang nama, bukan nilai yang
  // bisa berubah) — jadi yang diperiksa hanya blok defaultConfig.
  const awal = app.indexOf('defaultConfig {')
  const akhir = app.indexOf('buildFeatures {', awal)
  const blokDefault = app.slice(awal, akhir > 0 ? akhir : undefined)
  assert.ok(awal > 0, 'build.gradle.kts: blok defaultConfig tak ditemukan')

  // Gradle harus MEMBACA dari alamat.json, bukan menuliskan nilainya.
  for (const [kunci, nilai] of Object.entries({
    idPaket: janji.idPaket, versiNama: janji.versiNama, versiKode: janji.versiKode,
  })) {
    assert.ok(!blokDefault.includes(`= "${nilai}"`) && !blokDefault.includes(`= ${nilai}\n`),
      `build.gradle.kts menuliskan ${kunci} langsung — pakai bacaAlamat("${kunci}")`)
    assert.ok(app.includes(`bacaAlamat("${kunci}")`),
      `build.gradle.kts tidak membaca "${kunci}" dari alamat.json`)
  }
  assert.ok(app.includes('bacaAlamat("beranda")'),
    'build.gradle.kts tidak menanam alamat dari alamat.json')

  // APK yang sudah dibangun harus memakai alamat yang sama juga.
  if (existsSync(APK)) {
    const badging = execFileSync(AAPT, ['dump', 'badging', APK], { encoding: 'utf8' })
    assert.ok(badging.includes(`package: name='${janji.idPaket}'`),
      'APK memakai applicationId lama — bangun ulang')
    assert.ok(badging.includes(`versionName='${janji.versiNama}'`),
      'APK memakai versionName lama — bangun ulang')
    assert.ok(badging.includes(`versionCode='${janji.versiKode}'`),
      'APK memakai versionCode lama — bangun ulang')
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
  const AKSIGNER = SDK ? join(SDK, `build-tools/35.0.0/apksigner${extBat}`) : ''

  /**
   * Jalankan apksigner. Di Windows berkasnya .bat, yang tak bisa dipanggil
   * execFileSync langsung (EINVAL) — jadi lewat cmd.exe. Di Linux/macOS
   * berkasnya skrip yang bisa dijalankan langsung.
   */
  const jalankanApksigner = (target) => execFileSync(
    process.platform === 'win32' ? 'cmd.exe' : AKSIGNER,
    process.platform === 'win32'
      ? ['/c', AKSIGNER, 'verify', '--print-certs', target]
      : ['verify', '--print-certs', target],
    { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 },
  )

  blok('APK rilis ada', () => {
    assert.ok(statSync(APK_RILIS).size > 1_000_000, 'APK rilis mencurigakan kecil')
  })

  blok('APK rilis ditandatangani sertifikat Z-Rooms, bukan debug', () => {
    const out = jalankanApksigner(APK_RILIS)
    assert.match(out, /certificate DN: CN=Z-Rooms/,
      'APK rilis masih memakai keystore debug — Android akan menolak menimpa versi resmi')
    assert.ok(!/CN=Android Debug/.test(out), 'APK rilis bertanda tangan debug')
  })

  blok('versi rilis memakai sertifikat yang sama dengan APK rilis resmi', () => {
    // Sebelumnya blok ini bernama "tanda tangan sama dengan debug" tapi isinya
    // hanya memeriksa MainActivity ada di DEX — hal yang sudah diperiksa blok
    // di atasnya. Nama menjanjikan pemeriksaan tanda tangan, isinya tidak.
    //
    // Yang benar-benar perlu dijaga: sidik jari sertifikat rilis harus SAMA
    // dengan APK rilis yang sudah beredar (rilis/). Android menolak memasang
    // APK dengan tanda tangan berbeda, jadi kalau keystore produksi hilang dan
    // build jatuh ke keystore debug, rilis baru TIDAK bisa menimpa yang lama —
    // dan itu baru ketahuan saat kasir memasangnya.
    const keluaran = jalankanApksigner(APK_RILIS)
    const sha = keluaran.match(/SHA-256 digest: ([0-9a-f]+)/i)
    assert.ok(sha, 'apksigner tak melaporkan SHA-256 sertifikat')

    // APK rilis yang diarsipkan di rilis/ — patokan tanda tangan resmi.
    const arsip = readdirSync(join(AKAR, 'rilis')).filter((f) => f.endsWith('.apk'))
    assert.ok(arsip.length > 0, 'tak ada APK arsip di rilis/ — tak ada patokan tanda tangan')
    const acuan = join(AKAR, 'rilis', arsip.sort().pop())
    const keluaranAcuan = jalankanApksigner(acuan)
    const shaAcuan = keluaranAcuan.match(/SHA-256 digest: ([0-9a-f]+)/i)
    assert.ok(shaAcuan, 'apksigner tak melaporkan SHA-256 APK arsip')

    assert.equal(sha[1].toLowerCase(), shaAcuan[1].toLowerCase(),
      `sertifikat rilis berbeda dari ${arsip.sort().pop()} — rilis ini tak bisa `
      + 'menimpa versi resmi di perangkat kasir')
  })
} else {
  console.log('  --  APK rilis belum dibangun; blok rilis dilewati')
}

console.log(`\n${lulus} blok lulus`)
