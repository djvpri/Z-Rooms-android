package com.zrooms.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
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
import androidx.appcompat.app.AppCompatActivity

/**
 * Z-Rooms versi Android: WebView tipis di atas situs produksi Z-Rooms.
 * Alamatnya datang dari [BuildConfig.BERANDA], yang dibaca build.gradle.kts
 * dari alamat.json — lihat catatan di `companion object` bawah.
 *
 * Sengaja TANPA Compose dan tanpa UI sendiri — seluruh tampilan datang dari
 * web. Yang ditambahkan di sini cuma yang browser tak bisa lakukan:
 * tombol kembali Android, unduhan bon, dan tempat menaruh printer Bluetooth
 * nanti (lihat [cetakStruk], masih kosong).
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        progres = findViewById(R.id.progres)

        // Penangkap crash dipasang PALING AWAL: apa pun yang gagal setelah
        // baris ini terbaca di log yang dikirim kasir. Yang gagal SEBELUM
        // baris ini hanya terlihat di logcat Android.
        //
        // Di sini, bukan di onCreate sebelum WebView: PemilihBerkas memanggil
        // LogWeb lewat lambda di bawah, dan lambda itu memegang `web`.
        LogWeb.pasangPenangkapCrash(applicationContext)

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
            userAgentString = "$userAgentString ZRoomsAndroid/1.0"
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
                // Jembatan supaya halaman bisa memberi tahu APK saat log sudah
                // terkirim. Didaftarkan ulang tiap halaman selesai karena
                // addJavascriptInterface menempel pada konteks JS halaman.
                //
                // HANYA untuk host ZXRoom: jembatan ini bisa dipanggil skrip
                // mana pun yang termuat di WebView, jadi memasangnya untuk
                // semua alamat akan membuka jalur dari situs pihak ketiga.
                if (url.startsWith(BERANDA)) {
                    view.addJavascriptInterface(JembatanApk(applicationContext), "ZXR_APK")
                }
                // Kejadian selama pemuatan (saat halaman belum siap) dikirim
                // menyusul, supaya tak hilang.
                //
                // `catat` di sini memang menyalin isi yang sudah tercatat: yang
                // dituju sisi WEB, karena pengirim laporan ada di halaman web.
                // Sisi APK menyimpan isinya sendiri di dalam LogWeb.
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
     * Tempat printer struk Bluetooth nanti.
     *
     * Belum ada isinya — sengaja. Rencananya halaman web mengirim bon sebagai
     * teks lewat jembatan JavaScript (`addJavascriptInterface`), lalu metode
     * ini yang mengirimkannya ke printer. Dikosongkan supaya jelas bahwa
     * jalur ini memang belum ada, bukan lupa.
     */
    @Suppress("unused")
    private fun cetakStruk(teks: String) {
        // TODO(printer): sambungkan ke BluetoothSocket printer thermal 58mm,
        // tulis ESC/POS, lihat stack/ (belum dibuat).
        throw NotImplementedError("Printer Bluetooth belum dipasang")
    }

    override fun onDestroy() {
        // Dijaga `isInitialized`: kalau onCreate gagal sebelum baris
        // `web = findViewById(...)`, referensi ini belum terisi dan membacanya
        // melempar UninitializedPropertyAccessException — aplikasi tak bisa
        // ditutup rapi dan yang terlihat oleh kasir cuma "keluar sendiri".
        // Callback pemilih berkas dibalas null DULUAN: kalau halaman web
        // menunggu hasil saat aplikasi ditutup, ia menggantung selamanya —
        // dan itu terlihat sebagai "tombolnya rusak" pada pemakaian berikutnya.
        if (::pemilih.isInitialized) pemilih.lepas()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        /**
         * Alamat beranda. Datang dari build.gradle.kts, yang membacanya dari
         * alamat.json di akar proyek — satu sumber kebenaran untuk alamat,
         * id paket, dan versi. Jangan tulis ulang alamatnya di sini:
         * scripts/check-android-apk.mjs akan menolak kalau nilainya beda.
         */
        const val BERANDA = BuildConfig.BERANDA
    }
}
