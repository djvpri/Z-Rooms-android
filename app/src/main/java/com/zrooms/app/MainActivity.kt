package com.zrooms.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Z-Rooms versi Android: WebView tipis di atas https://zxroom.zomet.my.id.
 *
 * Sengaja TANPA Compose dan tanpa UI sendiri — seluruh tampilan datang dari
 * web. Yang ditambahkan di sini cuma yang browser tak bisa lakukan:
 * tombol kembali Android, unduhan bon, dan tempat menaruh printer Bluetooth
 * nanti (lihat [cetakStruk], masih kosong).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var progres: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        progres = findViewById(R.id.progres)

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
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progres.progress = newProgress
                progres.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
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
            web.loadUrl(BERANDA)
        }
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
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val BERANDA = "https://zxroom.zomet.my.id"
    }
}
