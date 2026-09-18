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
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var tvVersi: TextView

    /**
     * Permintaan `<input type="file">` dari web yang sedang menunggu.
     *
     * WebView menyerahkan seluruh pemilihan berkas ke aplikasi lewat
     * `onShowFileChooser`, dan callback [ValueCallback] itu WAJIB dipanggil
     * balik — kalau tidak, halaman web menunggu selamanya dan tombolnya
     * tampak "tidak merespon", tanpa pesan apa pun. Dipakai juga sebagai
     * penanda bahwa pemilih berkas sedang terbuka, supaya halaman web tidak
     * memicu dua pemilih sekaligus.
     */
    private var mintaBerkas: ValueCallback<Array<Uri>>? = null

    /** Pemilih berkas & kamera. Hasilnya diteruskan balik ke WebView. */
    private lateinit var pilihBerkas: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        progres = findViewById(R.id.progres)
        tvVersi = findViewById(R.id.tvVersi)

        // Memasang penangkap crash PALING AWAL: apa pun yang gagal setelah
        // baris ini terbaca di log yang dikirim kasir. Kode yang gagal
        // SEBELUM baris ini hanya terlihat di logcat Android.
        LogWeb.pasangPenangkapCrash(applicationContext)

        // Versi terpasang ditampilkan SEBELUM halaman web dimuat; kalau
        // menunggu halaman, ia tak akan pernah muncul saat halaman gagal
        // dimuat — justru saat paling dibutuhkan.
        tvVersi.text = getString(R.string.label_versi, BuildConfig.VERSI_NAMA)
        tvVersi.visibility = View.VISIBLE

        // Didaftarkan SEBELUM onCreate selesai: Activity Result API menolak
        // registrasi setelah activity berjalan.
        //
        // Hasilnya SELALU diteruskan balik, termasuk saat kasir membatalkan
        // (data null). Callback yang tak dipanggil membuat halaman web menunggu
        // selamanya, dan tombolnya tampak rusak padahal cuma dibatalkan.
        pilihBerkas = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { hasil ->
            val cb = mintaBerkas ?: return@registerForActivityResult
            mintaBerkas = null
            val uris = WebChromeClient.FileChooserParams.parseResult(hasil.resultCode, hasil.data)
            LogWeb.catat(web, if (uris == null || uris.isEmpty())
                "pemilih selesai: DIBATALKAN / tak ada berkas (kode=${hasil.resultCode})"
            else
                "pemilih selesai: ${uris.size} berkas")
            cb.onReceiveValue(uris)
        }

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
                // Penanda versi SENGAJA tidak disembunyikan di sini. Ia menetap
                // di bawah layar selama aplikasi terbuka, supaya kasir bisa
                // membacakan versinya kapan pun diminta — tanpa harus menutup
                // dan membuka ulang aplikasi.
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
             * tanpa pesan error. Itulah bug yang dilaporkan kasir.
             *
             * Dua masukan dibedakan sesuai permintaan web: `capture` = "Kamera"
             * memakai ACTION_IMAGE_CAPTURE (buka aplikasi kamera langsung),
             * sisanya ACTION_GET_CONTENT (pemilih berkas/galeri).
             */
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                // LogWeb mencatat SETIAP langkah: tanpa itu, keluhan "tombol tak
                // merespon" tak bisa dibedakan antara pemilih tak terbuka,
                // kasir membatalkan, atau berkas dikembalikan tapi web diam.
                LogWeb.catat(web, "pilih berkas diminta: mode=${params.mode} " +
                    "capture=${params.acceptTypes.joinToString(",").take(60)}")

                // Pemilih sebelumnya belum selesai: balas yang lama dengan null
                // supaya halaman web tak menggantung, lalu layani yang baru.
                mintaBerkas?.onReceiveValue(null)
                mintaBerkas = callback

                val banyak = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                val intent = try {
                    // createIntent() sudah menyusun ACTION_GET_CONTENT lengkap
                    // dengan accept-types dari atribut `accept` web. Lebih benar
                    // daripada menyusun intent sendiri dan lupa MIME-nya.
                    params.createIntent()
                } catch (e: Exception) {
                    // Perangkat tanpa aplikasi pemilih berkas (jarang, tapi ada
                    // di HP kasir yang dipangkas pabrik).
                    LogWeb.catat(web, "createIntent GAGAL: ${e.javaClass.simpleName}: ${e.message}")
                    mintaBerkas = null
                    callback.onReceiveValue(null)
                    return false
                }

                if (banyak) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

                // `capture="environment"` dari web: pakai kamera langsung.
                // createIntent() mengabaikannya, jadi diganti di sini.
                //
                // isCaptureEnabled baru ada di API 30; minSdk aplikasi ini 24,
                // jadi dijaga versi — memanggilnya langsung akan crash di
                // Android 7-10, dan itu justru HP kasir yang umum dipakai.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && params.isCaptureEnabled) {
                    intent.action = android.provider.MediaStore.ACTION_IMAGE_CAPTURE
                    LogWeb.catat(web, "memakai kamera langsung (ACTION_IMAGE_CAPTURE)")
                }

                return try {
                    pilihBerkas.launch(intent)
                    LogWeb.catat(web, "pemilih dibuka: ${intent.action}")
                    true
                } catch (e: Exception) {
                    // Tak ada aplikasi yang bisa menangani: beri tahu web supaya
                    // tombolnya tak diam-diam menggantung.
                    LogWeb.catat(web, "LAUNCH GAGAL: ${e.javaClass.simpleName}: ${e.message}")
                    mintaBerkas = null
                    callback.onReceiveValue(null)
                    false
                }
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
