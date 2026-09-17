package com.zrooms.app

import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Penangkap error halaman web yang berjalan DI DALAM APK.
 *
 * Kenapa perlu: saat kasir melaporkan "tombol tak merespon", laporan web biasa
 * tak menolong — yang rusak justru jembatan antara aplikasi dan halaman (`<input
 * type="file">`, unduhan, kamera). ConsoleMessage di sini adalah satu-satunya
 * tempat error itu muncul.
 *
 * Hasilnya diumpankan ke penangkap yang SAMA dengan halaman web
 * (`window.ZXR_LOG`), supaya log dari APK dan dari web menyatu di satu kiriman
 * dan tak perlu dua tempat untuk memeriksa.
 */
object LogWeb {

    /** Batas baris. Halaman dibiarkan terbuka berhari-hari di HP kasir. */
    private const val MAKS = 150

    private val baris = ArrayDeque<String>()
    private const val KUNCI = "zxroom.log.apk"

    /**
     * Pasang `console.log` di halaman web supaya pesan dari APK ikut tercatat
     * di log yang dikirim lewat tombol "Kirim log error".
     *
     * Dipasang setelah halaman selesai dimuat: `evaluateJavascript` pada
     * dokumen yang belum ada akan hilang tanpa jejak.
     */
    fun sambungkan(web: WebView) {
        web.evaluateJavascript(
            """
            (function () {
              if (window.__zxrApkTerpasang) return;
              window.__zxrApkTerpasang = true;
              // Kirim ke web supaya ikut tertangkap penangkap log halaman
              // (lib/logError.ts). Ditulis lewat console.error, bukan
              // console.log: penangkap web sengaja hanya menangkap error —
              // console.log membuat log penuh sampah.
              window.addEventListener('zxr-apk-log', function (e) {
                console.error('[APK] ' + (e.detail || ''));
              });

              // Halaman memberi tahu APK saat kiriman sukses, supaya log sisi
              // APK ikut dikosongkan dan tak terkirim ulang. Dipicu event
              // kustom yang dilempar komponen KirimLogError.
              window.addEventListener('zxr-log-terkirim', function () {
                if (window.ZXR_APK && window.ZXR_APK.kosongkan) window.ZXR_APK.kosongkan();
              });
            })();
            """.trimIndent(),
        )
    }

    /**
     * Catat satu kejadian dari sisi aplikasi.
     *
     * Disimpan di memori DAN dikirim ke halaman web, kalau halaman sudah siap.
     * Memori dipakai sebagai cadangan saat halaman belum dimuat — kesalahan
     * yang paling sulit dilacak justru terjadi saat pemuatan awal.
     */
    fun catat(web: WebView?, pesan: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        baris.addLast("[$t] $pesan")
        while (baris.size > MAKS) baris.removeFirst()

        web?.post {
            // Dikirim lewat event supaya JS yang menerjemahkannya ke console.error,
            // bukan disisipkan ke string — pesan bisa memuat tanda kutip dan
            // baris baru yang kalau disisipkan langsung akan merusak skripnya.
            val aman = org.json.JSONObject.quote(pesan)
            web.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('zxr-apk-log',{detail:$aman}))",
                null,
            )
        }
    }

    /** Isi log dari sisi aplikasi, untuk ditampilkan kalau halaman belum siap. */
    fun isi(): String = baris.joinToString("\n")

    /**
     * Buang log yang sudah terkirim. Dipanggil halaman web setelah kiriman
     * sukses, supaya kiriman berikutnya tidak mengulang yang lama.
     */
    fun kosongkan(context: Context) {
        baris.clear()
        context.getSharedPreferences("zxroom", Context.MODE_PRIVATE)
            .edit().remove(KUNCI).apply()
    }

    /** Ambil ConsoleMessage dari halaman web — error JS yang tak sampai ke
     *  penangkap web (mis. karena halaman memuat skripnya gagal). */
    fun dariConsole(msg: ConsoleMessage, web: WebView?): Boolean {
        if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            catat(web, "web: ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
        }
        return false
    }
}
