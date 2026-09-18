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

blok('jembatan JS sempit: metode terbatas & hanya menerima string', () => {
  // Dua metode, keduanya sekadar MEMBERI TAHU. Batas jumlahnya yang dijaga,
  // bukan angka duanya: jembatan ini dipanggil skrip mana pun yang termuat di
  // WebView, jadi tiap metode baru memperbesar permukaan yang bisa dipanggil
  // dari halaman. Menambah metode = sengaja, bukan tak sengaja.
  const jml = (jembatan.match(/@JavascriptInterface/g) ?? []).length
  assert.equal(jml, 2, `ada ${jml} metode jembatan, harus 2`)
  // Argumen hanya boleh String — tipe lain berarti halaman bisa menyuruh APK
  // melakukan sesuatu (buka URL, jalan perintah), bukan cuma melaporkan.
  for (const m of jembatan.matchAll(/fun (\w+)\(([^)]*)\)/g)) {
    assert.ok(/^\s*laporan: String\s*$/.test(m[2]) || m[2].trim() === '',
      `metode jembatan "${m[1]}" menerima argumen selain String: (${m[2]})`)
  }
  // Tak ada nilai balik: halaman tak boleh bisa MEMBACA apa pun dari APK.
  assert.ok(!/fun \w+\([^)]*\)\s*:\s*\w/.test(jembatan),
    'metode jembatan mengembalikan nilai — halaman bisa membacanya')
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

blok('halaman awal /dashboard, bukan akar situs', () => {
  // Akar situs cuma halaman depan yang mengalihkan; kasir melihat halaman
  // perantara lebih dulu, dan kalau halaman itu tak selesai memuat, aplikasi
  // mentok di sana. Kontrak: pemuatan PERTAMA memakai BERANDA + '/dashboard'.
  assert.match(main, /web\.loadUrl\(BERANDA \+ "\/dashboard"\)/,
    'pemuatan pertama harus ke /dashboard')
  // Putar layar tetap memulihkan posisi, bukan melompat ke dashboard.
  assert.match(main, /web\.restoreState\(savedInstanceState\)/,
    'posisi halaman hilang tiap putar layar')
})

blok('splash versi: diisi APK, disembunyikan saat halaman selesai', () => {
  assert.match(main, /findViewById\(R\.id\.tvVersi\)/)
  // Diisi dari BuildConfig — satu sumber versi, bukan ditulis ulang di kode.
  assert.match(main, /tvVersi\.text\s*=\s*getString\(R\.string\.label_versi,\s*BuildConfig\.VERSI_NAMA\)/,
    'versi harus dari BuildConfig.VERSI_NAMA')
  // Dinyalakan SEBELUM halaman dimuat, dimatikan di onPageFinished: saat
  // halaman gagal dimuat, penandanya justru harus tetap terlihat.
  const isi = main.indexOf('tvVersi.text = getString')
  const muat = main.indexOf('web.loadUrl(BERANDA + "/dashboard")')
  assert.ok(isi > 0 && muat > 0 && isi < muat,
    'versi harus ditulis sebelum halaman dimuat')
  assert.match(main, /override fun onPageFinished[\s\S]{0,600}?tvVersi\.visibility\s*=\s*View\.GONE/,
    'penanda versi tak pernah disembunyikan → menutupi halaman selamanya')
})

blok('penanda versi ada di layout dengan id yang dicari kode', () => {
  const layout = readFileSync(join(AKAR, 'app/src/main/res/layout/activity_main.xml'), 'utf8')
  assert.match(layout, /android:id="@\+id\/tvVersi"/,
    'findViewById(R.id.tvVersi) akan melempar NPE tanpa ini')
})

blok('versi ikut terkirim di laporan error', () => {
  // Halaman web hanya tahu versi webnya; laporan yang sampai ke DB harus
  // membawa versi APLIKASI yang menjalankannya.
  assert.match(logweb, /catatVersi\(/,
    'versi APK tak pernah dicatat → laporan tak bisa dipasangkan dengan versinya')
  assert.match(main, /LogWeb\.catatVersi\(/,
    'catatVersi tak pernah dipanggil — fungsinya ada tapi tak jalan')
})

blok('onDestroy selamat walau onCreate gagal separuh', () => {
  // Tanpa penjaga, UninitializedPropertyAccessException di sini membuat
  // aplikasi tak bisa ditutup rapi — yang terlihat kasir: "keluar sendiri".
  assert.match(main, /if \(::web\.isInitialized\) web\.destroy\(\)/,
    'web.destroy() tanpa penjaga isInitialized')
})

blok('penangkap crash dipasang sebelum halaman dimuat', () => {
  assert.match(main, /LogWeb\.pasangPenangkapCrash\(applicationContext\)/)
  const pasang = main.indexOf('LogWeb.pasangPenangkapCrash(applicationContext)')
  const muat = main.indexOf('web.loadUrl(BERANDA + "/dashboard")')
  assert.ok(pasang > 0 && muat > 0 && pasang < muat,
    'kegagalan saat pemuatan awal tak akan terbaca kalau penangkap dipasang belakangan')
  // Handler lama tetap dipanggil balik: perilaku force close Android tak berubah.
  assert.match(logweb, /bawaan\?\.uncaughtException\(utas, galat\)/,
    'handler bawaan tak dipanggil balik → aplikasi diam, tanpa pesan force close')
})

blok('console yang dicatat HANYA error', () => {
  assert.match(logweb, /MessageLevel\.ERROR/,
    'penangkap console kehilangan saringan level')
  assert.ok(!/MessageLevel\.(WARNING|LOG|TIP|DEBUG)/.test(logweb),
    'menyalin warn/log ke laporan membuat yang penting tenggelam')
})

blok('log dibatasi jumlahnya, DAN pengulangan dihitung', () => {
  // Inilah penyebab laporan 110.013 karakter: 200 kejadian berisi satu masalah
  // yang sama, tiap kejadian menyalin ratusan baris. Dua penjaga harus ada:
  // batas jumlah baris (memori) dan batas pengulangan identik (laporan).
  assert.match(logweb, /private const val MAKS = \d+/,
    'tanpa batas jumlah baris, WebView menahan log terus sampai memori habis')
  assert.match(logweb, /private const val MAKS_SAMA = \d+/,
    'tanpa batas pengulangan, satu masalah yang sama membanjiri laporan')
  // Yang dihitung harus DITULIS sebagai hitungan — kalau tidak, pembatasan
  // hanya membuang informasi tanpa memberi tahu polanya berulang.
  assert.match(logweb, /pesan yang sama diulang/,
    'pengulangan dibuang tanpa diringkas → polanya hilang dari laporan')
  assert.match(logweb, /while \(baris\.size > MAKS\) baris\.removeFirst\(\)/,
    'batas baris tak pernah ditegakkan')
})

blok('laporan yang terkirim disimpan di perangkat', () => {
  // Kalau kasir menekan kirim lagi setelah kiriman pertama gagal, isinya masih
  // ada. Laporan tak bisa dibuat ulang setelah kejadiannya lewat.
  assert.match(logweb, /fun simpanLaporan\(konteks: Context, laporan: String\)/)
  assert.match(jembatan, /fun simpanLaporan\(laporan: String\)/,
    'halaman web tak punya cara menitipkan laporannya')
  // Disimpan ke disk SEBELUM proses mati, bukan sesudah.
  assert.match(logweb, /setDefaultUncaughtExceptionHandler[\s\S]{0,900}?\.putString\(KUNCI, isi\(\)\)\.commit\(\)/,
    'jejak crash harus ditulis ke disk sebelum aplikasi keluar')
})

blok('pemeriksa updater tak lagi memegang WebView', () => {
  // Pemeriksaan berjalan di thread lain dan bisa selesai SETELAH jendela
  // ditutup — saat itu WebView sudah dihancurkan, dan menyentuhnya dari thread
  // lain menjatuhkan aplikasi. Ini bug yang ikut menyebabkan "keluar sendiri".
  const updater = readFileSync(
    join(AKAR, 'app/src/main/java/com/zrooms/app/PemeriksaPembaruan.kt'), 'utf8')
  assert.match(updater, /fun periksa\(konteks: Context\)/,
    'periksa() masih menerima WebView')
  assert.match(updater, /fun pasang\(konteks: Context, apk: File\)/,
    'pasang() masih menerima WebView')
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
