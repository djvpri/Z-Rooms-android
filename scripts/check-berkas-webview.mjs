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
const berkas = readFileSync(
  join(AKAR, 'app/src/main/java/com/zrooms/app/PemilihBerkas.kt'), 'utf8')
const manifest = readFileSync(join(AKAR, 'app/src/main/AndroidManifest.xml'), 'utf8')

let n = 0
const blok = (nama, fn) => { fn(); n++; console.log('  ok -', nama) }

console.log('check-berkas-webview: pemilih berkas & jembatan APK\n')

blok('onShowFileChooser dipasang', () =>
  assert.match(main, /override fun onShowFileChooser\(/,
    'tanpa ini <input type="file"> diam total — bug yang dilaporkan kasir'))

blok('hasil pemilih diteruskan balik ke WebView', () => {
  // Seluruh urusan pemilih berkas pindah ke PemilihBerkas: registrasi hasil
  // HARUS tanpa syarat di onCreate, dan kamera menerima berkas lewat URI.
  // Diperiksa di berkas itu, bukan di MainActivity.
  assert.match(berkas, /FileChooserParams\.parseResult\(/)
  assert.match(berkas, /cb\?\.onReceiveValue\(uris\)/)
  // Setiap jalur gagal WAJIB membalas null. Callback yang tak pernah dibalas
  // membuat halaman web menggantung selamanya — tombol yang diam, tanpa
  // pesan. Ini yang dulu terjadi.
  // Dihitung, bukan sekadar dicari: `onReceiveValue(null)` muncul di DUA jalur
  // (pemilih dibatalkan, dan pemilih gagal). Saat satu jalur kehilangan
  // pembalasannya, pencarian biasa tetap hijau — dan tombolnya diam lagi.
  const batal = (berkas.match(/cb\?\.onReceiveValue\(null\)/g) ?? []).length
  assert.ok(batal >= 2,
    `hanya ${batal} jalur yang membalas null — jalur batal ATAU gagal tak dibalas → halaman web menggantung`)
})

blok('hasil kamera didaftarkan TANPA syarat di onCreate', () => {
  // Android boleh membunuh proses saat aplikasi kamera terbuka. Callback yang
  // didaftarkan bersyarat tak ada saat pemulihan, dan yang kasir lihat:
  // aplikasi keluar sendiri. Karena itu registrasinya harus di properti
  // kelas — dijalankan saat objek dibangun — bukan di dalam if/else.
  // Registrasi harus di PROPERI KELAS, bukan di dalam fungsi. Diperiksa lewat
  // jarak: baris `private val pilih ... =` harus langsung diikuti baris
  // `activity.registerForActivityResult(`. Pencocokan pola `...() )` saja tak
  // menangkapnya — pola itu tetap cocok walau bloknya dipindah ke dalam cabang.
  const iVal = berkas.indexOf('private val pilih: ActivityResultLauncher')
  assert.ok(iVal > -1, 'launcher pemilih tak ada sebagai properti kelas')
  const ekor = berkas.slice(iVal).split(/\r?\n/)
  assert.ok(/^\s*activity\.registerForActivityResult\($/.test(ekor[1] ?? ''),
    'registrasi hasil pemilih tak lagi langsung di properti kelas → tak ada saat proses dipulihkan → aplikasi keluar sendiri')
  // Dua launcher: satu untuk pemilih/kamera, satu untuk izin. Yang kedua
  // wajib, karena ACTION_IMAGE_CAPTURE tanpa izin CAMERA melempar
  // SecurityException yang menjatuhkan proses.
  assert.match(berkas, /private val mintaIzin = activity\.registerForActivityResult\(/,
    'launcher izin tak ada sebagai properti kelas')
  assert.match(berkas, /mintaIzin\.launch\(android\.Manifest\.permission\.CAMERA\)/,
    'izin kamera tak pernah diminta → SecurityException saat kamera dibuka')
})

blok('foto kamera ditulis ke content:// milik sendiri', () => {
  // Akar "keluar sendiri saat klik kamera": tanpa EXTRA_OUTPUT aplikasi
  // kamera mengembalikan foto lewat extras, dan sejak Android 11 extras dari
  // aplikasi lain tak bisa dibaca. Pemanggilnya melempar SecurityException.
  assert.match(berkas, /MediaStore\.EXTRA_OUTPUT/,
    'EXTRA_OUTPUT hilang → foto dikirim lewat extras yang tak terbaca di Android 11+')
  assert.match(berkas, /FileProvider\.getUriForFile\(/,
    'URI file:// ditolak sejak Android 7 (FileUriExposedException) — wajib FileProvider')
  assert.match(berkas, /FLAG_GRANT_WRITE_URI_PERMISSION/,
    'tanpa FLAG_GRANT_WRITE_URI_PERMISSION aplikasi kamera tak boleh menulis fotonya')
  assert.match(berkas, /resolveActivity\(/,
    'tanpa resolveActivity, ketiadaan kamera muncul sebagai crash, bukan pesan')
})
blok('isCaptureEnabled dijaga versi (minSdk 24, API-nya 30)', () => {
  // Pencocokan literal, bukan regex: `Build.VERSION.CODES.R` ditulis apa
  // adanya, dan regex pada rangkaian titik-titik seperti ini gampang salah
  // tanpa terlihat (sudah kejadian: pola yang tampak benar menolak teks yang
  // persis sama).
  assert.ok(
    berkas.includes('VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&'),
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
  const i = main.indexOf('.addJavascriptInterface(')
  assert.ok(i > -1, 'jembatan ZXR_APK tidak dipasang')

  // Batas host bisa dua bentuk, dan keduanya sah:
  //   (a) penjaga `if (url.startsWith(BERANDA))` di sekitar pemasangan
  //       (laku lama: dipasang di onPageFinished)
  //   (b) shouldOverrideUrlLoading menolak host selain zomet.my.id —
  //       pemasangan di onCreate SEBELUM loadUrl, yang justru satu-satunya
  //       cara jembatan benar-benar terlihat halaman (Chromium modern hanya
  //       menerapkan addJavascriptInterface pada pemuatan berikutnya).
  const dijagaUrl = main.lastIndexOf('if (url.startsWith(BERANDA))', i) > -1
  const tolakHostLuar = /host\.endsWith\("zomet\.my\.id"\)/.test(main) &&
    /startActivity\(Intent\(Intent\.ACTION_VIEW/.test(main)
  assert.ok(dijagaUrl || tolakHostLuar,
    'dipasang tanpa batasan host — situs pihak ketiga bisa memanggilnya')
})

blok('jembatan JS sempit: tanpa jalur perintah & hanya menerima string', () => {
  // SEBELUMNYA batas JUMLAH metode (2) yang dijaga. Angka itu tak lagi berarti
  // begitu cetak ditambahkan, dan menjaga angka apa pun cepat usang: yang
  // penting bukan berapa banyak, tapi APA yang boleh dilakukan.
  //
  // Yang dijaga sekarang:
  //   1. tak ada metode yang MENGEMBALIKAN nilai yang bisa dibaca halaman
  //      (kecuali daftar printer & versi, yang memang bukan data pribadi);
  //   2. argumen hanya String — tipe lain berarti halaman bisa menyuruh APK
  //      melakukan sesuatu, bukan cuma melaporkan;
  //   3. setiap metode punya blok komentar tepat di atasnya, sehingga metode
  //      baru tak bisa diselundupkan tanpa alasan tertulis.
  const diizinkanKembalikan = ['daftarPrinter', 'printerTersimpan', 'namaPrinterTersimpan', 'statusPrinter', 'versi']

  const nama = [...jembatan.matchAll(/fun (\w+)\(/g)].map((m) => m[1])
  assert.ok(nama.length >= 2, 'metode jembatan hilang')

  // Diperiksa hanya metode yang BENAR-BENAR dipasang ke JavaScript. Metode
  // internal kelas ini (`laporHasil`) tak bisa dipanggil halaman, jadi
  // argumennya tak perlu dibatasi String.
  const metodeJs = [...jembatan.matchAll(/@JavascriptInterface\s+fun (\w+)\(([^)]*)\)/g)]
  assert.ok(metodeJs.length >= 2, 'metode @JavascriptInterface hilang')

  for (const [, fungsi, argumen] of metodeJs) {
    assert.ok(
      argumen.trim() === '' || /^\s*[a-z]+: String\s*$/.test(argumen),
      `metode jembatan "${fungsi}" menerima argumen selain String: (${argumen})`)
  }

  // Pengembalian nilai: hanya yang ada di daftar putih, dan hanya untuk metode
  // yang benar-benar terpasang ke JavaScript. Satu regex per metode supaya
  // metode baru langsung ketahuan.
  const kembalian = [...jembatan.matchAll(/@JavascriptInterface\s+fun (\w+)\([^)]*\)\s*:\s*([\w<>]+)/g)]
  for (const [, fungsi, tipe] of kembalian) {
    assert.ok(
      diizinkanKembalikan.includes(fungsi),
      `metode jembatan "${fungsi}" mengembalikan ${tipe} — halaman bisa membacanya`)
  }

  // Setiap @JavascriptInterface harus didahului komentar (/** ... */ dalam
  // jarak dekat). Metode tanpa penjelasan = permukaan baru tanpa alasan.
  for (const m of jembatan.matchAll(/@JavascriptInterface/g)) {
    const sebelum = jembatan.slice(Math.max(0, m.index - 700), m.index)
    assert.ok(sebelum.includes('/**'), 'ada metode jembatan tanpa komentar penjelasan di atasnya')
  }

  // Jalur cetak WAJIB membatasi panjang naskah: halaman yang salah (atau situs
  // yang menyusup) tak boleh bisa mengirim teks raksasa yang menghabiskan memori.
  // Dicek PEMAKAIANNYA, bukan sekadar namanya: `includes('MAKS_NASKAH')` tetap
  // benar walau pemeriksaannya dilumpuhkan jadi `if (false)` — itu bug yang
  // pernah lolos di sini.
  assert.match(jembatan, /if \(naskah\.length > MAKS_NASKAH\)/,
    'batas panjang naskah tidak benar-benar diperiksa')
})

blok('setiap langkah pemilih dicatat ke log', () => {
  // Penanda ini yang membuat laporan kasir bisa dibaca: tanpa "pemilih dibuka"
  // tak kelihatan apakah pemilihnya sempat terbuka, dan tanpa "DIBATALKAN"
  // penutupan kamera terlihat sama dengan tombol yang tak merespon.
  for (const tanda of ['pilih berkas diminta', 'pemilih dibuka', 'LAUNCH GAGAL', 'DIBATALKAN']) {
    assert.ok(berkas.includes(tanda), `log kehilangan penanda "${tanda}"`)
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

blok('manifes: izin CAMERA ADA, dan kamera tetap opsional di perangkat', () => {
  assert.match(manifest, /android\.hardware\.camera"[^>]*\n?[^>]*required="false"/,
    'tanpa uses-feature required=false, perangkat tanpa kamera tak bisa memasang')
  assert.match(manifest, /hardware\.camera\.autofocus/)
  // DIBALIK dari pemeriksaan sebelumnya. Dulu di sini tertulis "tak perlu izin
  // CAMERA" — dan itu yang membuat aplikasi keluar sendiri saat tombol Kamera
  // ditekan: ACTION_IMAGE_CAPTURE tanpa izin CAMERA melempar SecurityException
  // yang menjatuhkan proses. Dinyatakan sebagai pernyataan POSITIF: pemeriksa
  // yang cuma melarang sesuatu akan hijau begitu barisnya dihapus.
  assert.match(manifest, /<uses-permission[^>]*android\.permission\.CAMERA/,
    'izin CAMERA harus ada di manifes — tanpa itu ACTION_IMAGE_CAPTURE melempar SecurityException')
  // Sejak Android 11 aplikasi lain tak terlihat tanpa <queries>; resolveActivity
  // lalu selalu bilang "tak ada kamera" walau kameranya ada.
  assert.match(manifest, /<queries>[\s\S]*android\.media\.action\.IMAGE_CAPTURE[\s\S]*<\/queries>/,
    'tanpa <queries> IMAGE_CAPTURE, resolveActivity tak melihat aplikasi kamera')
  // Fotonya diserahkan lewat content:// dari FileProvider sendiri, bukan extras
  // (yang sejak Android 11 tak bisa dibaca) dan bukan file:// (ditolak sejak 7).
  assert.match(manifest, /android:authorities="\$\{applicationId\}\.berkas"/,
    'authority FileProvider untuk foto KTP hilang')
  assert.match(manifest, /@xml\/file_paths_ktp/,
    'daftar berkas yang boleh dibuka FileProvider foto KTP hilang')
  // Izin pembaruan mandiri. Tanpa ini Android 8+ MENOLAK pemasangan APK baru,
  // dan seluruh HP yang sudah beredar kehilangan kemampuan memperbarui diri —
  // harus dipasang manual satu per satu. Pernah hilang saat merapikan manifes,
  // dan tak ada pemeriksa yang menjaganya.
  assert.match(manifest, /<uses-permission[^>]*android\.permission\.REQUEST_INSTALL_PACKAGES/,
    'izin REQUEST_INSTALL_PACKAGES hilang → pembaruan mandiri mati, semua HP harus dipasang manual')
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

blok('versi: ditulis di bilah judul, dan TIDAK lagi memakan layar', () => {
  // Dulu versi menempel sebagai banner `tvVersi` di bawah layar. Itu
  // dikeluhkan: bannernya menghalangi dashboard. Versi pindah ke bilah judul
  // — tempat Android memang menyediakan keterangan aplikasi — sehingga
  // halaman web tak berkurang sedikit pun.
  assert.match(main, /supportActionBar\?\.title\s*=\s*getString\(R\.string\.label_versi,\s*BuildConfig\.VERSI_NAMA\)/,
    'versi harus ditulis di bilah judul dari BuildConfig.VERSI_NAMA')
  const layout = readFileSync(join(AKAR, 'app/src/main/res/layout/activity_main.xml'), 'utf8')
  // Pernyataan POSITIF soal ketiadaan: banner versi tak boleh kembali. Diuji
  // dengan menghitungnya harus NOL kali, bukan sekadar tak menyebut namanya.
  assert.equal((layout.match(/tvVersi/g) || []).length, 0,
    'tvVersi kembali ke layout → banner versi mengahalangi dashboard lagi')
  assert.equal((main.match(/tvVersi/g) || []).length, 0,
    'kode masih mencari tvVersi → sisa banner versi bawah layar')
  // Bilah judul hanya ada kalau temanya menyediakannya. Theme AppCompat
  // NoActionBar membuat supportActionBar null, dan judulnya hilang tanpa
  // pesan apa pun — hijau palsu kalau ini tak diperiksa.
  const tema = readFileSync(join(AKAR, 'app/src/main/res/values/themes.xml'), 'utf8')
  // Yang diperiksa TAG-nya, bukan kata bebas: komentar di berkas itu sendiri
  // menerangkan "BUKAN NoActionBar", dan mencari kata biasa akan menemukan
  // komentar itu — hijau/gagal karena alasan yang salah.
  assert.ok(!/parent="[^"]*NoActionBar"/.test(tema),
    'tema NoActionBar → supportActionBar null, judul/versi tak akan tampil')
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
  // AKAR dari "kirim log error lapor 0 kejadian": `baris` cuma hidup di memori
  // proses, jadi begitu aplikasi keluar isinya hilang — yang tersisa hanya
  // tulisan di penangkap crash. Karena itu catat() WAJIB menyimpan tiap
  // kejadian, bukan hanya saat crash.
  // Dihitung, bukan sekadar dicari: catat() memanggil simpanKeDisk() di DUA
  // jalur — jalur ringkasan pengulangan dan jalur kejadian biasa. Kalau
  // pencarian biasa dipakai, menghapus pemanggilan di jalur kejadian tetap
  // lolos pemeriksaan, dan log kembali lapor 0 kejadian.
  const badanCatat = logweb.slice(
    logweb.indexOf('fun catat('), logweb.indexOf('private fun simpanKeDisk()'))
  const simpan = (badanCatat.match(/simpanKeDisk\(\)/g) ?? []).length
  assert.ok(simpan >= 2,
    `catat() hanya menyimpan ke disk di ${simpan} jalur → kejadian biasa hilang saat aplikasi keluar, tombol kirim lapor 0 kejadian`)
  // Dan disk ikut dikosongkan setelah kiriman sukses, supaya isi lama tak
  // terbaca sebagai kejadian baru pada kiriman berikutnya.
  const badanKosong = logweb.slice(logweb.indexOf('fun kosongkan()'))
  assert.match(badanKosong, /simpanKeDisk\(\)/,
    'kosongkan() tak membersihkan disk → kejadian lama ikut terkirim lagi')
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

blok('cetak: izin Bluetooth & penguraian naskah ESC/POS', () => {
  const printer = readFileSync(join(AKAR, 'app/src/main/java/com/zrooms/app/PrinterBluetooth.kt'), 'utf8')
  const manifes = readFileSync(join(AKAR, 'app/src/main/AndroidManifest.xml'), 'utf8')

  // Android 12 memecah izin Bluetooth jadi RUNTIME. Mendeklarasikan yang lama
  // saja membuat tombol cetak melempar SecurityException di Android 12+ tanpa
  // dialog izin pernah muncul — kasir melihat tombol yang mati.
  assert.ok(manifes.includes('android.permission.BLUETOOTH_CONNECT'),
    'BLUETOOTH_CONNECT tak dideklarasikan — cetak gagal di Android 12+')
  assert.ok(manifes.includes('android.permission.BLUETOOTH_SCAN'),
    'BLUETOOTH_SCAN tak dideklarasikan')
  // Tanpa flag ini Android menuntut izin LOKASI untuk scan Bluetooth. Dicek
  // pada BARIS izinnya (bukan sekadar nama flag di berkas mana pun): menghapus
  // flag dari izinnya sementara kata itu masih ada di komentar tetap harus
  // menggigit.
  assert.match(manifes, /BLUETOOTH_SCAN"[\s\S]{0,200}?neverForLocation/,
    'BLUETOOTH_SCAN tanpa neverForLocation — Android akan menuntut izin lokasi')
  // Versi lama tetap butuh izin lamanya; maxSdkVersion membatasinya.
  assert.match(manifes, /BLUETOOTH"\s*\n?\s*android:maxSdkVersion="30"/,
    'BLUETOOTH lama tak dibatasi maxSdkVersion 30')
  // Izin diminta saat mencetak, bukan saat aplikasi dibuka.
  const main = readFileSync(join(AKAR, 'app/src/main/java/com/zrooms/app/MainActivity.kt'), 'utf8')
  assert.match(main, /RequestMultiplePermissions/,
    'izin Bluetooth tidak diminta lewat RequestMultiplePermissions')
  assert.match(main, /naskahTertunda/,
    'naskah tak disimpan saat izin diminta — kasir harus menekan cetak dua kali')

  // Cetak WAJIB di thread lain: menyambung Bluetooth memblokir, dan di thread
  // utama seluruh halaman web membeku. Bisa `Thread {` atau `Thread(null, {`
  // kalau stackSize ditentukan.
  assert.match(main, /Thread\([^)]*\)?\s*\{[\s\S]{0,400}?PrinterBluetooth\.cetak\(/,
    'cetak dijalankan di thread utama — halaman web akan membeku')

  // UUID SPP adalah standar Bluetooth; salah ketik = tak ada printer yang cocok.
  assert.ok(printer.includes('00001101-0000-1000-8000-00805F9B34FB'),
    'UUID SPP salah atau hilang — tak ada printer yang bisa disambung')

  // Socket disimpan, bukan dibuka-tutup per cetak (pola Z1 Label). Dua sifat
  // yang wajib ada supaya itu aman:
  //  (a) ada fungsi tutup yang benar-benar menutup socket, dan
  //  (b) ada pemantau aliran masuk yang menutup saat printer putus — tanpa itu,
  //      socket mati akan terus dianggap hidup dan cetakan berikutnya hilang.
  assert.match(printer, /fun tutup\(\)[\s\S]{0,400}?socket\?\.close\(\)/,
    'tak ada fungsi tutup() yang menutup socket')
  assert.match(printer, /inputStream[\s\S]{0,300}?read\(/,
    'tak ada pemantau aliran masuk — printer putus tak terdeteksi')

  // Membatalkan penemuan sebelum menyambung: printer tak bisa disambung saat
  // Bluetooth sedang memindai, dan kegagalannya muncul sebagai "tidak bisa
  // tersambung" biasa — pesan yang menyesatkan kasir.
  assert.match(printer, /cancelDiscovery\(\)/,
    'penemuan Bluetooth tidak dibatalkan sebelum menyambung')

  // Jalur SPP standar TIDAK cukup: sebagian printer struk murah tak
  // mendaftarkan UUID SPP, dan tanpa cadangan refleksi alat itu dilaporkan
  // rusak padahal bisa dipakai (pola dari aplikasi Z1 Label).
  //
  // Dicek sebagai NAMA METODE di dalam tanda kutip: pola lebar
  // `createRfcommSocket` juga cocok dengan `createRfcommSocketToServiceRecord`,
  // jadi menghapus cadangannya tak akan menggigit.
  assert.match(printer, /getMethod\(\s*"createRfcommSocket"/,
    'tak ada cadangan createRfcommSocket — printer tanpa UUID SPP akan gagal')

  // Socket hidup selama aplikasi hidup, jadi konteksnya WAJIB dari aplikasi.
  // Memakai Activity di sini membocorkan halaman yang sudah ditutup.
  assert.match(main, /PrinterBluetooth\.pasang\(applicationContext\)/,
    'PrinterBluetooth.pasang(applicationContext) tak dipanggil di onCreate')

  // Printer hanya dicatat SETELAH berhasil.
  const idxCetak = printer.indexOf('keluaranSekarang.flush()')
  const idxSimpan = printer.indexOf('simpanPrinter(perangkat.address)')
  assert.ok(idxCetak > -1 && idxSimpan > idxCetak,
    'printer disimpan sebelum cetak berhasil — kegagalan akan tercatat sebagai berhasil')
})

blok('cetak: penguraian naskah benar (perintah vs teks)', () => {
  const printer = readFileSync(join(AKAR, 'app/src/main/java/com/zrooms/app/PrinterBluetooth.kt'), 'utf8')

  // Perintah dikirim sebagai byte, bukan teks: kalau perintah tercetak sebagai
  // teks, nota penuh simbol aneh alih-alih terpotong.
  assert.match(printer, /fun uraikanNaskah\(/, 'uraikanNaskah hilang')
  assert.match(printer, /fun bacaPerintah\(/, 'bacaPerintah hilang')
  // Teks harus ISO-8859-1: UTF-8 mengubah karakter beraksen jadi dua byte dan
  // menggeser seluruh baris nota.
  assert.ok(printer.includes('ISO_8859_1'),
    'teks nota tak memakai ISO-8859-1 — baris akan bergeser')
  // Kurung siku yang bukan perintah harus tetap jadi teks biasa.
  assert.match(printer, /toIntOrNull\(\) \?: return null/,
    'kurung siku non-angka dipaksa jadi perintah — isi nota bisa rusak')

  // Hasil cetak dikembalikan ke halaman; tanpa itu kasir tak pernah tahu gagal.
  const jembatan = readFileSync(join(AKAR, 'app/src/main/java/com/zrooms/app/JembatanApk.kt'), 'utf8')
  assert.match(jembatan, /fun hasilCetakJs\(/, 'fungsi pelapor hasil cetak hilang')
  // Pesan kesalahan memuat nama printer & tanda kutip — wajib di-escape.
  assert.ok(jembatan.includes('JSONObject.quote'),
    'pesan hasil cetak tak di-escape — skripnya rusak saat pesan memuat tanda kutip')
})

blok('cetak: uji naskah = salinan PERSIS logika produksi', () => {
  // Uji di app/src/test/kotlin/UjiNaskah.kt memakai salinan `bacaPerintah` dan
  // `uraikanNaskah`. Kalau salinan itu basi, ujinya lulus sambil menguji kode
  // yang tak dipakai APK — dan bug lolos. Bandingkan pernyataan-pernyataan
  // kuncinya (bukan seluruh berkas: komentar & tata letak boleh beda).
  const prod = readFileSync(join(AKAR, 'app/src/main/java/com/zrooms/app/PrinterBluetooth.kt'), 'utf8')
  const uji = readFileSync(join(AKAR, 'app/src/test/kotlin/UjiNaskah.kt'), 'utf8')

  // Normalisasi: buang indentasi + `private`, supaya beda kosmetik tak dihitung.
  const norm = (s) => s.split('\n').map((l) => l.trim().replace(/\bprivate /g, '')).join('\n')

  const wajib = [
    'if (t.length < 4 || !t.startsWith("<") || !t.endsWith(">")) return null',
    'val isi = t.substring(1, t.length - 1)',
    'if (isi.isEmpty()) return null',
    'val bagian = isi.split(",")',
    'val n = bagian[i].trim().toIntOrNull() ?: return null',
    'if (n < 0 || n > 255) return null',
    'keluaran.write(baris.toByteArray(Charsets.ISO_8859_1))',
    'keluaran.write(0x0a)',
  ]
  const p = norm(prod)
  const u = norm(uji)
  for (const baris of wajib) {
    assert.ok(p.includes(baris), `logika produksi kehilangan: ${baris}`)
    // setara: buang spasi supaya perbedaan indentasi/kutip kecil tak mengganggu
    const rapat = (s) => s.replace(/\s+/g, '')
    assert.ok(rapat(u).includes(rapat(baris)),
      `salinan di UjiNaskah.kt BASI untuk: ${baris}`)
  }
})

console.log(`\nOK — check-berkas-webview: ${n} blok lulus`)
