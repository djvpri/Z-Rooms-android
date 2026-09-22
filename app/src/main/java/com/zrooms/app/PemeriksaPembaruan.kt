package com.zrooms.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.CookieManager
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Pembaruan mandiri: memeriksa rilis terakhir Z-Rooms, mengunduh APK-nya, lalu
 * menyerahkannya ke pemasang paket Android.
 *
 * Kenapa perlu: sebelumnya APK tak tahu sama sekali kalau ada versi baru —
 * tag rilis mendarat di GitHub dan tak satu pun HP diberi tahu. Distribusinya
 * bergantung pada seseorang mengirim berkasnya lewat WhatsApp.
 *
 * Kenapa TANPA layar sendiri: seluruh tampilan Z-Rooms datang dari web, dan
 * menambah UI di sini berarti dua tempat yang harus dirawat. Yang dilakukan
 * kelas ini hanya memberi tahu lewat [LogWeb] dan toast bawaan Android.
 *
 * Yang TIDAK bisa dilakukan siapa pun di sini: memasang diam-diam. Sejak
 * Android 8, aplikasi yang memasang APK harus punya izin
 * REQUEST_INSTALL_PACKAGES, dan pengguna tetap harus menyalakan "Pasang
 * aplikasi tak dikenal" untuk aplikasi ini, sekali, di Pengaturan. Kalau belum
 * dinyalakan, [pasang] membuka layar Pengaturan itu — bukan diam-diam gagal.
 */
object PemeriksaPembaruan {

    /** Dijalankan di luar thread UI: menyentuh jaringan dilarang di sana. */
    private val tukang = Executors.newSingleThreadExecutor()

    /** Cegah dua pemeriksaan berbarengan saat aplikasi dibuka berulang cepat. */
    @Volatile
    private var sedangJalan = false

    private const val NAMA_BERKAS = "zrooms-baru.apk"

    /** Subfolder cache tempat APK disimpan; harus cocok dengan file_paths.xml. */
    private const val FOLDER = "pembaruan"

    /**
     * Periksa rilis terakhir di latar belakang. Aman dipanggil kapan saja.
     *
     * TIDAK menerima WebView: pemeriksaan ini berjalan di thread lain dan bisa
     * selesai SETELAH jendela ditutup, dan saat itu WebView sudah dihancurkan —
     * menyentuhnya dari thread lain membuat aplikasi jatuh. Hasilnya masuk ke
     * [LogWeb] saja, yang tetap hidup dan sudah dibatasi jumlahnya.
     *
     * Kegagalan apa pun (tak ada jaringan, GitHub membatasi permintaan,
     * rilisnya belum punya APK) sengaja hanya DICATAT, bukan ditampilkan:
     * ini fitur tambahan, dan mengganggu kasir dengan pesan pembaruan saat
     * sinyalnya buruk lebih merugikan daripada sekadar melewatinya.
     */
    fun periksa(konteks: Context) {
        if (sedangJalan) return
        sedangJalan = true
        tukang.execute {
            try {
                val rilis = tanyaRilisTerakhir()
                if (rilis == null) {
                    LogWeb.catat(null, "pembaruan: tak ada rilis terbit")
                    return@execute
                }
                val (tag, urlApk) = rilis
                if (angkaVersi(tag) == null) {
                    LogWeb.catat(null, "pembaruan: tag rilis \"$tag\" tak dikenali")
                    return@execute
                }
                // Hanya versi yang LEBIH BARU. Bandingkan versiNama sebagai
                // tuple (1.0.12 > 1.0.11), bukan versiKode — versiKode naik
                // per rilis dan tak selalu = patch+1, jadi perbandingan
                // angka tunggal menyesatkan sejak 1.0.10.
                if (!lebihBaru(tag)) {
                    LogWeb.catat(null, "pembaruan: versi terpasang ${BuildConfig.VERSI_NAMA} " +
                        "sudah terbaru (terbit $tag)")
                    return@execute
                }
                if (urlApk == null) {
                    LogWeb.catat(null, "pembaruan: rilis $tag tak melampirkan APK")
                    return@execute
                }

                LogWeb.catat(null, "pembaruan: versi $tag tersedia, mengunduh ${urlApk.substringAfterLast('/')}")
                val berkas = unduh(konteks, urlApk)
                LogWeb.catat(null, "pembaruan: unduhan selesai ${berkas.length()} byte")
                pasang(konteks, berkas)
            } catch (e: Exception) {
                // Termasuk IOException dan JSONException: apa pun bentuk
                // kegagalannya, fitur ini tak boleh menjatuhkan aplikasi.
                LogWeb.catat(null, "pembaruan GAGAL: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                sedangJalan = false
            }
        }
    }

    /**
     * Rilis terakhir dari GitHub: (tag, url APK) atau null kalau belum ada rilis.
     *
     * API GitHub memblokir permintaan tanpa User-Agent — tanpa header di bawah
     * balasannya 403, bukan data.
     */
    private fun tanyaRilisTerakhir(): Pair<String, String?>? {
        val koneksi = URL("https://api.github.com/repos/${BuildConfig.REPO_RILIS}/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            koneksi.requestMethod = "GET"
            koneksi.setRequestProperty("User-Agent", "Z-Rooms-Android/${BuildConfig.VERSI_NAMA}")
            koneksi.setRequestProperty("Accept", "application/vnd.github+json")
            koneksi.connectTimeout = 10_000
            koneksi.readTimeout = 10_000
            if (koneksi.responseCode != 200) {
                LogWeb.catat(null, "pembaruan: GitHub membalas ${koneksi.responseCode}")
                return null
            }
            val isi = koneksi.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(isi)
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isEmpty()) return null
            // Dicari lampiran berakhiran .apk — nama berkasnya berubah tiap
            // versi (Z-Rooms-1.0.3.apk), jadi tak bisa ditulis tetap.
            val lampiran = json.optJSONArray("assets")
            for (i in 0 until (lampiran?.length() ?: 0)) {
                val a = lampiran!!.getJSONObject(i)
                val nama = a.optString("name")
                if (nama.endsWith(".apk", ignoreCase = true)) {
                    return tag to a.optString("browser_download_url")
                }
            }
            return tag to null
        } finally {
            koneksi.disconnect()
        }
    }

    /**
     * Versi dari tag rilis sebagai daftar angka: "1.0.12" -> [1, 0, 12].
     *
     * Yang dibandingkan adalah angka per bagian, bukan teks: "1.0.10" sebagai
     * teks lebih kecil daripada "1.0.9", dan pembaruan terlewat diam-diam.
     */
    private fun angkaVersi(tag: String): List<Int>? {
        val bersih = tag.removePrefix("v").trim()
        val bagian = bersih.split(".")
        if (bagian.isEmpty()) return null
        return bagian.map { it.toIntOrNull() ?: return null }
    }

    /** Benarkah `tag` lebih baru dari versi yang terpasang?
     *
     * Kenapa TIDAK memakai versiKode: versiKode naik +1 per rilis, sementara
     * rumus lama menganggapnya = patch+1. Sejak 1.0.10 kedua skala itu
     * berpisah (1.0.10 -> 12, bukan 11), sehingga pembaruan selalu dianggap
     * "sudah terbaru" dan tak pernah terpasang.
     */
    private fun lebihBaru(tag: String): Boolean {
        val baru = angkaVersi(tag) ?: return false
        val terpasang = angkaVersi(BuildConfig.VERSI_NAMA) ?: return false
        // zip saja akan memotong di bagian terpendek ("1.1" vs "1.1.0" dianggap
        // sama) — sisa bagian pada yang lebih panjang harus dihitung ikut.
        val n = maxOf(baru.size, terpasang.size)
        for (i in 0 until n) {
            val a = baru.getOrElse(i) { 0 }
            val b = terpasang.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * Unduh APK ke cache aplikasi, menunggu sampai selesai.
     *
     * Sengaja memakai unduhan sendiri, bukan [DownloadManager] yang dipakai
     * bon: DownloadManager menaruh berkas di folder Unduhan PUBLIK, dan APK
     * pembaruan di sana bisa dipasang siapa pun tanpa lewat aplikasi ini —
     * selain itu FileProvider tak boleh membuka berkas di luar cache aplikasi.
     */
    private fun unduh(konteks: Context, url: String): File {
        val folder = File(konteks.cacheDir, FOLDER).apply { mkdirs() }
        // Berkas lama dibuang: sisa unduhan yang terputus akan dibaca sebagai
        // APK rusak, dan pemasang paket menolaknya dengan pesan yang tak
        // menjelaskan apa pun.
        val tujuan = File(folder, NAMA_BERKAS)
        if (tujuan.exists()) tujuan.delete()

        val koneksi = URL(url).openConnection() as HttpURLConnection
        try {
            koneksi.instanceFollowRedirects = true
            koneksi.setRequestProperty("User-Agent", "Z-Rooms-Android/${BuildConfig.VERSI_NAMA}")
            koneksi.connectTimeout = 15_000
            koneksi.readTimeout = 60_000
            if (koneksi.responseCode != 200) {
                throw IllegalStateException("unduhan dibalas ${koneksi.responseCode}")
            }
            koneksi.inputStream.use { masuk ->
                tujuan.outputStream().use { keluar -> masuk.copyTo(keluar) }
            }
        } finally {
            koneksi.disconnect()
        }
        return tujuan
    }

    /**
     * Serahkan APK ke pemasang paket Android.
     *
     * Dua syarat Android yang harus dipenuhi lebih dulu, dan keduanya diperiksa
     * di sini supaya kegagalannya muncul sebagai pesan, bukan tombol yang diam:
     *
     *  1. Izin REQUEST_INSTALL_PACKAGES harus disetujui. Kalau belum, layar
     *     Pengaturannya dibuka — tanpa itu tak ada cara menyalakannya.
     *  2. Uri harus lewat FileProvider. Sejak Android 7 `file://` ditolak
     *     dengan FileUriExposedException.
     */
    private fun pasang(konteks: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !konteks.packageManager.canRequestPackageInstalls()
        ) {
            LogWeb.catat(null, "pembaruan: izin \"Pasang aplikasi tak dikenal\" belum aktif, membuka Pengaturan")
            konteks.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${konteks.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        val uri = FileProvider.getUriForFile(
            konteks, "${konteks.packageName}.pembaruan", apk)

        // FLAG_GRANT_READ_URI_PERMISSION wajib: pemasang paket berjalan sebagai
        // proses LAIN, dan tanpa izin ini ia tak bisa membaca berkasnya.
        konteks.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}
