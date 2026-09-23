package com.zrooms.app

import android.app.AlertDialog
import android.app.DownloadManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Z-Rooms versi Android: WebView tipis di atas situs produksi Z-Rooms.
 * Alamatnya datang dari [BuildConfig.BERANDA], yang dibaca build.gradle.kts
 * dari alamat.json — lihat catatan di `companion object` bawah.
 *
 * Sengaja TANPA Compose dan tanpa UI sendiri — seluruh tampilan datang dari
 * web. Yang ditambahkan di sini cuma yang browser tak bisa lakukan:
 * tombol kembali Android, unduhan bon, dan tempat menaruh printer Bluetooth
 * nanti (lihat [cetakDiUtas]).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var progres: ProgressBar

    /**
     * Tombol "Kamera" & "Pilih file". Dipisah ke [PemilihBerkas]:
     * `registerForActivityResult` untuk hasil kamera wajib dipanggil tanpa
     * syarat di onCreate, dan aturan itu lebih mudah dijaga kalau seluruh
     * urusannya ada di satu kelas.
     */
    private lateinit var pemilih: PemilihBerkas

    /**
     * Naskah yang menunggu izin Bluetooth. Diisi saat kasir menekan cetak
     * sebelum izin diberikan; dikosongkan setelah dipakai.
     */
    private var naskahTertunda: String? = null

    /**
     * Permintaan izin Bluetooth. `registerForActivityResult` WAJIB dipanggil
     * tanpa syarat di onCreate — alasan yang sama dengan [PemilihBerkas].
     */
    private val mintaIzinBluetooth =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { hasil ->
            val naskah = naskahTertunda
            naskahTertunda = null
            if (naskah == null) return@registerForActivityResult
            // Kalau ditolak, PrinterBluetooth yang menyusun pesannya — satu
            // tempat, supaya kalimatnya sama dengan jalur izin-dicabut.
            if (hasil.values.all { it }) {
                cetakDiUtas(naskah)
            } else {
                laporCetak(false, "Izin Bluetooth ditolak. Cetak tak bisa jalan tanpa izin itu.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        progres = findViewById(R.id.progres)

        // Printer disiapkan dengan konteks aplikasi: socket-nya hidup selama
        // aplikasi hidup, jadi ia tak boleh memegang Activity (itu membocorkan
        // halaman yang sudah ditutup).
        PrinterBluetooth.pasang(applicationContext)

        // Penangkap crash dipasang PALING AWAL: apa pun yang gagal setelah
        // baris ini terbaca di log yang dikirim kasir. Yang gagal SEBELUM
        // baris ini hanya terlihat di logcat Android.
        //
        // Di sini, bukan di onCreate sebelum WebView: PemilihBerkas memanggil
        // LogWeb lewat lambda di bawah, dan lambda itu memegang `web`.
        LogWeb.pasangPenangkapCrash(applicationContext)

        // Dipakai `jalankanJs` untuk memanggil balik halaman (hasil cetak).
        webAktif = web

        pemilih = PemilihBerkas(this) { pesan -> LogWeb.catat(web, pesan) }

        // Bilah judul memuat nama aplikasi + VERSI. Sebelumnya versi menempel
        // sebagai banner di bawah layar dan menutupi dashboard — sekarang ia
        // duduk di tempat yang memang disediakan Android untuk keterangan
        // aplikasi, dan halaman web tak berkurang sedikit pun.
        supportActionBar?.title = getString(R.string.label_versi, BuildConfig.VERSI_NAMA)

        // WebView TIDAK memakai CookieManager bawaan secara otomatis untuk
        // autentikasi; harus dinyalakan sendiri. Situs memakai cookie
        // `__Host-authjs.*` (Secure + SameSite=Lax) milik NextAuth, jadi
        // ketiga baris di bawah ini syarat mutlak supaya login nyangkut.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            // Situs memblokir agen tak dikenal; tempelkan penanda versi kita
            // supaya sisi web bisa membedakan asal permintaan bila perlu.
            // Versi asli (bukan "1.0" tetap) supaya log di sisi web tahu APK
            // mana yang mengirim — penting saat mendiagnosa tombol cetak mati
            // di APK lama yang belum punya method versi().
            userAgentString = "$userAgentString ZRoomsAndroid/${BuildConfig.VERSI_NAMA}"
        }

        // `__Host-` cookie menuntut HTTPS dan SameSite yang utuh; Android 5–6
        // (WebView lama) menolaknya di mode pihak-ketiga. Diperiksa di sini
        // supaya kegagalan login terlihat sebagai pesan, bukan layar diam.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // minSdk 24 menjamin ini tak pernah jalan — penjaga dokumentasi.
            throw IllegalStateException("Butuh Android 5+ untuk cookie Secure")
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                // Hanya host milik Z-Rooms yang dimuat di dalam aplikasi.
                // Tautan luar (WhatsApp, Google Maps) dilempar ke browser
                // supaya kasir tak terjebak di dalam aplikasi tanpa jalan pulang.
                val host = request.url.host ?: return false
                return if (host.endsWith("zomet.my.id")) {
                    false
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                }
            }

            // Penangkap log sisi APK disambungkan setelah halaman siap: skrip
            // yang disuntikkan sebelum dokumen ada akan hilang tanpa jejak.
            //
            // Digabung ke WebViewClient ini, bukan dipasang sebagai client
            // kedua — memasang `web.webViewClient` dua kali membuat yang
            // terakhir menimpa yang pertama, dan navigasi tautan luar mati
            // tanpa pesan apa pun.
            override fun onPageFinished(view: WebView, url: String) {
                LogWeb.sambungkan(view)
                // Jembatan ZXR_APK kini dipasang di onCreate (sebelum
                // pemuatan pertama). Lihat catatan di tempat pemasangan.
                if (url.startsWith(BERANDA)) {
                    autoSambungPrinter()
                }
                // Kejadian selama pemuatan (saat halaman belum siap) dikirim
                // menyusul, supaya tak hilang.
                //
                // `catat` di sini memang menyalin isi yang sudah tercatat: yang
                // dituju sisi WEB, karena pengirim laporan ada di halaman web.
                val tertunda = LogWeb.isi()
                if (tertunda.isNotEmpty()) LogWeb.catat(view, "log APK saat pemuatan:\n$tertunda")
                // Versi APK yang menjalankan halaman ini. Ditulis di sini (bukan
                // di onCreate) supaya ikut terkirim lewat tombol "Kirim log
                // error": laporan web hanya tahu versi webnya.
                LogWeb.catatVersi(view)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progres.progress = newProgress
                progres.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean =
                LogWeb.dariConsole(msg, web)

            /**
             * Tombol "Kamera" dan "Pilih file" di halaman booking.
             *
             * WebView TIDAK punya UI pemilih berkas sendiri — tanpa override ini,
             * menekan `<input type="file">` tidak melakukan apa-apa sama sekali,
             * tanpa pesan error. Seluruh urusannya ada di [PemilihBerkas]; di
             * sini cuma penyambungnya, karena `onShowFileChooser` hidup di
             * WebChromeClient (WebView tak punya UI pemilih berkas sendiri).
             */
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                return pemilih.mintaBerkas(callback, params)
            }
        }

        web.setDownloadListener { url, _, _, mime, _ ->
            unduh(url, mime)
        }

        // Tombol kembali Android: mundur di riwayat web dulu, baru keluar.
        // Tanpa ini, satu-satunya jalan keluar dari halaman dalam adalah
        // menutup aplikasi lewat daftar tugas.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        // Jembatan JS dipasang SEBELUM pemuatan pertama, di onCreate:
        // addJavascriptInterface pada WebView Chromium modern hanya
        // berlaku untuk dokumen yang dimuat SETELAH panggilan itu —
        // memasangnya di onPageFinished (laku lama) berarti jembatan
        // baru muncul di pemuatan berikutnya, dan navigasi SPA Next.js
        // tak pernah memuat ulang → halaman web selalu melihat
        // window.ZXR_APK undefined (kasus produksi 2026-09-22: tombol
        // cetak mati di APK 1.0.13–1.0.16, typeof=undefined padahal
        // blok pemasangan terbukti jalan).
        //
        // Keamanan tetap terjaga: shouldOverrideUrlLoading menolak host
        // selain zomet.my.id — halaman luar tak pernah termuat di WebView
        // ini, jadi jembatan tak bisa dipanggil situs pihak ketiga.
        web.addJavascriptInterface(
            JembatanApk(applicationContext) { naskah -> cetakDiUtas(naskah) },
            "ZXR_APK",
        )

        // Dipulihkan setelah putar layar supaya posisi tak kembali ke beranda.
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            // Membuka /dashboard, bukan akar situs: akar cuma halaman depan
            // yang mengalihkan ke dashboard setelah beberapa detik — kasir
            // melihat halaman perantara lebih dulu, dan kalau halaman itu
            // tak pernah selesai memuat, aplikasi mentok di sana.
            web.loadUrl(BERANDA + "/dashboard")
        }

        // Pembaruan diperiksa setelah beranda mulai dimuat, bukan sebelum:
        // permintaan ke GitHub di jalur pembukaan aplikasi akan menambah
        // waktu tunggu kasir. Pemeriksaannya sendiri berjalan di thread lain.
        PemeriksaPembaruan.periksa(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    /** Bon dan nota diunduh lewat DownloadManager supaya muncul di notifikasi
     *  dan tersimpan di folder Unduhan — WebView sendiri tak punya UI unduh. */
    private fun unduh(url: String, mime: String) {
        val permintaan = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mime)
            addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(
                android.os.Environment.DIRECTORY_DOWNLOADS,
                Uri.parse(url).lastPathSegment ?: "zrooms-unduhan")
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(permintaan)
    }

    /**
     * Cetak naskah nota ke printer Bluetooth, di thread latar.
     *
     * Bluetooth MEMBLOKIR: menyambung butuh ratusan milidetik sampai beberapa
     * detik. Mengerjakannya di thread utama membekukan seluruh halaman web
     * selama itu — kasir melihat aplikasi menggantung, bukan tombol yang
     * bekerja. Karena itu seluruh urusannya dipindah ke thread lain.
     *
     * Izin Bluetooth diminta DI DALAM thread latar, bukan di thread pemanggil:
     * rantai jembatan → cetakStruk → kerjakanCetak → Thread(...).start()
     * pernah menumpuk di stack JavaBridge ~1 MB dan memicu
     * StackOverflowError 1039KB SEBELUM thread latar sempat jalan.
     *
     * Stack 8 MB, bukan bawaan (~1 MB): tumpukan Bluetooth Android
     * (createRfcommSocket → connect → write) cukup dalam.
     */
    private fun cetakDiUtas(naskah: String) {
        val utas = Thread(null, {
            // Instrumentasi: nama thread dicatat agar ketahuan apakah tumpukan
            // yang jebol itu thread kerja 8 MB atau thread lain (JavaBridge
            // WebView, stack bawaan ~1 MB — angka 1039KB di laporan SOE).
            LogWeb.catat(null, "cetak: mulai UTAS=${Thread.currentThread().name} STACK=${TUMPUKAN_CETAK}")
            try {
                val kurang = PrinterBluetooth.izinDibutuhkan().filter {
                    checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (kurang.isNotEmpty()) {
                    // Minta izin, lalu cetak yang tertunda setelah dijawab.
                    // Naskahnya disimpan: izin baru berlaku pada panggilan
                    // berikutnya, dan meminta kasir menekan tombol dua kali
                    // terasa seperti kerusakan.
                    // Dialog izin wajib naik di thread utama.
                    naskahTertunda = naskah
                    runOnUiThread {
                        LogWeb.catat(web, "izin Bluetooth diminta: ${kurang.joinToString(",")}")
                        mintaIzinBluetooth.launch(kurang.toTypedArray())
                    }
                    return@Thread
                }
                PrinterBluetooth.cetak(naskah)
                laporCetak(true, "Nota terkirim ke printer.")
            } catch (e: PesanKesalahanPrinter) {
                // Pesannya sudah disusun untuk kasir — diteruskan apa adanya.
                laporCetak(false, e.message ?: "Cetak gagal.")
            } catch (e: Throwable) {
                // Throwable, bukan Exception: Error (NoClassDefFound,
                // OutOfMemoryError) tak ikut hierarki Exception. Jejak
                // tumpukan disertakan — pesan saja pernah tak cukup untuk
                // tahu baris mana yang gagal.
                laporCetak(false, "Cetak gagal: ${e.javaClass.simpleName}: ${e.message ?: "-"}")
                LogWeb.catat(null, "cetak: throwable — ${jejak(e)}")
            }
        }, "zrooms-cetak", TUMPUKAN_CETAK)
        // Penangkap terakhir: kalau pelaporan di atas sendiri melempar,
        // Thread.UncaughtExceptionHandler default hanya mencetak ke logcat —
        // yang tak pernah sampai ke laporan kasir.
        utas.setUncaughtExceptionHandler { _, e ->
            LogWeb.catat(null, "cetak: utas mati — ${jejak(e)}")
            laporCetak(false, "Cetak gagal tak terduga: ${e.javaClass.simpleName}")
        }
        utas.start()
    }

    /**
         * Jejak tumpukan jadi satu baris: laporan kasir dipisah per kejadian.
         *
         * Instrumentasi: 8 bingkai pertama dengan className utuh (tanpa
         * pemotongan nama kelas per bingkai — substringAfterLast pernah
         * memantik StackOverflowError kedua di thread jembatan ~1 MB).
         * Format: "class.method:line < class.method:line < ..."
         */
        private fun jejak(e: Throwable): String =
            e.javaClass.name + ": " + (e.message ?: "-") + " @[" +
                Thread.currentThread().name + "] " +
                e.stackTrace.take(8).joinToString(" < ") {
                    it.className + "." + it.methodName + ":" + it.lineNumber
                }

    /** Panggil balik halaman dengan hasil cetak. Selalu di thread utama. */
    private fun laporCetak(ok: Boolean, pesan: String) {
        // Instrumentasi: nama thread ikut tercatat — 4 laporan identik dalam
        // 9 ms belum jelas asalnya dari thread kerja atau dari pemantau status.
        LogWeb.catat(
            web,
            "cetak ${if (ok) "berhasil" else "gagal"}: $pesan [UTAS=${Thread.currentThread().name}]"
        )
        jalankanJs(JembatanApk.hasilCetakJs(ok, pesan))
    }

    // -------------------------------------------------------------------------
    // Dialog pemilih printer (pola Z1 Label) + auto-connect saat app buka
    // -------------------------------------------------------------------------

    /** Dialog pemilih printer yang sedang tampil, atau null. */
    private var dialogPrinter: AlertDialog? = null
    /** Sedang memindai perangkat Bluetooth? */
    private var sedangMemindai = false

    /**
     * Buka dialog pemilih printer bawaan Android.
     *
     * Dialognya ada di sini, bukan di halaman web: daftar perangkat Bluetooth
     * berubah tiap detik saat memindai, dan melewatkannya lewat jembatan JS
     * berarti memompa daftar bolak-balik tiap perangkat ditemukan. Dialog
     * native menyegarkan sendiri.
     *
     * Dipanggil dari [JembatanApk.pilihPrinter] lewat companion object —
     * jembatan tak boleh memegang Activity.
     */
    fun tampilkanDialogPrinter() {
        runOnUiThread { isiDialogPrinter() }
    }

    /**
     * Isi dialog dengan daftar perangkat terpasang + hasil pindai.
     */
    private fun isiDialogPrinter() {
        val bonded = PrinterBluetooth.daftarLengkap()
        val scan = if (sedangMemindai) PrinterBluetooth.perangkatDitemukan() else emptyList()

        // Gabung: bonded + hasil scan, tak ada ganda.
        val merged = LinkedHashMap<String, Pair<String, String>>()
        bonded.forEach { (nama, alamat) -> merged[alamat] = nama to alamat }
        scan.forEach { merged[it.address] = (it.name ?: it.address) to it.address }
        val list = merged.values.toList()

        val names = list.map { it.first + "  ·  " + it.second }.toTypedArray()

        val builder = AlertDialog.Builder(this)
            .setTitle(if (sedangMemindai) "Memindai… (${list.size})" else "Pilih Printer")
            .setItems(names) { _, i ->
                val (nama, alamat) = list[i]
                PrinterBluetooth.simpanPrinterExtern(alamat)
                jalankanJs(JembatanApk.printerDipilihJs(nama, alamat))
                // Sambung di background supaya cetak pertama cepat.
                PrinterBluetooth.sambungOtomatis(alamat) { err ->
                    runOnUiThread {
                        if (err != null) LogWeb.catat(web, "bt: sambung gagal: $err")
                    }
                }
            }
            .setNeutralButton(if (sedangMemindai) "Berhenti scan" else "Scan perangkat") { _, _ ->
                if (sedangMemindai) {
                    sedangMemindai = false
                    PrinterBluetooth.hentikanPindai()
                    tampilkanDialogPrinter()
                    return@setNeutralButton
                }
                // Izin Bluetooth diminta DI SINI sebelum memindai. Dulu tombol
                // ini langsung memanggil mulaiPindai(), yang memeriksa izin
                // lalu KELUAR DIAM-DIAM kalau belum diberi. Kasir yang membuka
                // dialog printer sebelum pernah mencetak tak pernah melihat
                // dialog izin, dan pemindaian selalu kosong tanpa penjelasan.
                val kurang = PrinterBluetooth.izinDibutuhkan().filter {
                    checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (kurang.isNotEmpty()) {
                    naskahTertunda = null
                    LogWeb.catat(web, "izin Bluetooth diminta dari dialog printer: ${kurang.joinToString(",")}")
                    mintaIzinBluetooth.launch(kurang.toTypedArray())
                    return@setNeutralButton
                }
                sedangMemindai = true
                PrinterBluetooth.saatDitemukan = { runOnUiThread { tampilkanDialogPrinter() } }
                PrinterBluetooth.mulaiPindai()
                tampilkanDialogPrinter()
            }
            .setOnCancelListener {
                // Dibatalkan: kasir tak pilih apa pun.
                jalankanJs(JembatanApk.printerDipilihJs(null, null))
            }

        dialogPrinter?.dismiss()
        dialogPrinter = builder.show()
    }

    /**
     * Sambung ke printer tersimpan saat aplikasi dibuka.
     *
     * Di background, bukan di onCreate: menyambung butuh beberapa detik, dan
     * membekukan onCreate membuat halaman pertama muncul terlambat. Kalau
     * belum ada izin, lewati — cetak nanti yang memintanya.
     */
    private fun autoSambungPrinter() {
        if (PrinterBluetooth.tersambung()) return
        if (!PrinterBluetooth.izinDiberikan(this)) return
        PrinterBluetooth.saatStatusBerubah = { tersambung ->
            runOnUiThread {
                jalankanJs(JembatanApk.statusPrinterJs(tersambung))
            }
        }
        PrinterBluetooth.sambungOtomatis() { err ->
            if (err != null && err != "belum ada printer tersimpan") {
                LogWeb.catat(web, "bt: auto-connect gagal: $err")
            }
        }
    }

    override fun onDestroy() {
        PrinterBluetooth.hentikanPindai()
        dialogPrinter?.dismiss()
        // Dijaga `isInitialized`: kalau onCreate gagal sebelum baris
        // `web = findViewById(...)`, referensi ini belum terisi dan membacanya
        // melempar UninitializedPropertyAccessException — aplikasi tak bisa
        // ditutup rapi dan yang terlihat oleh kasir cuma "keluar sendiri".
        // Callback pemilih berkas dibalas null DULUAN: kalau halaman web
        // menunggu hasil saat aplikasi ditutup, ia menggantung selamanya —
        // dan itu terlihat sebagai "tombolnya rusak" pada pemakaian berikutnya.
        if (::pemilih.isInitialized) pemilih.lepas()
        // Dikosongkan SEBELUM web.destroy(): setelah dibongkar, WebView tak
        // boleh lagi dipanggil — dan `jalankanJs` bisa dipicu thread cetak
        // yang masih berjalan saat kasir menutup aplikasi.
        webAktif = null
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        /**
         * Ukuran stack thread cetak, dalam byte (8 MB). Bawaan ~1 MB pernah
         * kurang untuk tumpukan panggilan Bluetooth Android yang dalam.
         */
        private const val TUMPUKAN_CETAK = 8L * 1024 * 1024

        /**
         * Alamat beranda. Datang dari build.gradle.kts, yang membacanya dari
         * alamat.json di akar proyek — satu sumber kebenaran untuk alamat,
         * id paket, dan versi. Jangan tulis ulang alamatnya di sini:
         * scripts/check-android-apk.mjs akan menolak kalau nilainya beda.
         */
        const val BERANDA = BuildConfig.BERANDA

        /**
         * WebView yang sedang tampil, untuk memanggil JavaScript ke halaman.
         *
         * Disimpan statis supaya [JembatanApk] bisa memanggil balik tanpa
         * memegang Activity — memegang Activity dari kelas yang hidup lebih
         * lama adalah sumber kebocoran memori yang klasik. Referensinya
         * dikosongkan di onDestroy.
         */
        @Volatile
        private var webAktif: WebView? = null

        /** Jalankan JavaScript di halaman, di thread utama. Aman kalau tak ada WebView. */
        fun jalankanJs(skrip: String) {
            val w = webAktif ?: return
            w.post {
                try {
                    w.evaluateJavascript(skrip, null)
                } catch (e: Exception) {
                    // WebView yang sedang dibongkar bisa melempar; tak ada yang
                    // bisa dilakukan, dan melempar dari sini hanya menambah
                    // crash di atas masalah aslinya.
                }
            }
        }

        /**
         * Buka dialog pemilih printer dari jembatan JS.
         *
         * Jembatan tak boleh memegang Activity, jadi ia memanggil method
         * statis ini. Activity yang sedang aktif didapat dari `webAktif`
         * (yang menyimpan referensi WebView — dan WebView menyimpan Activity).
         */
        fun bukaDialogPrinter() {
            val w = webAktif ?: return
            w.post {
                val act = w.context as? MainActivity ?: return@post
                act.tampilkanDialogPrinter()
            }
        }
    }
}
