package com.zrooms.app

import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONObject

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
 *
 * ## Kenapa jumlahnya dibatasi
 *
 * Sebelumnya SETIAP baris dicatat dan tak ada yang membatasi lajunya. Satu
 * halaman yang memanggil `console.error` di dalam loop menghasilkan ratusan
 * kejadian per detik; tiap kejadian disalin ke memori, ke halaman web, lalu ke
 * laporan yang dikirim kasir. Laporan yang masuk berisi 200 kejadian dan
 * 110.013 karakter untuk satu masalah yang sama — dan yang lebih buruk,
 * WebView ikut menahan semuanya, memori naik cepat, lalu Android membunuh
 * aplikasi. Yang kasir lihat: "keluar sendiri".
 *
 * Jadi ada dua penjaga, dan keduanya harus ada:
 *  - [MAKS] — batas jumlah baris yang disimpan (memori).
 *  - [MAKS_SAMA] — batas berapa kali pesan yang SAMA dicatat. Pengulangan
 *    yang menembus batas ini DIHITUNG, bukan dicatat satu per satu, lalu
 *    diringkas jadi satu baris "… (N kali)". Jadi polanya tetap kelihatan
 *    tanpa membanjiri laporan.
 */
object LogWeb {

    /** Batas baris. Halaman dibiarkan terbuka berhari-hari di HP kasir. */
    private const val MAKS = 150

    /** Batas kejadian identik berturut-turut sebelum diringkas jadi hitungan. */
    private const val MAKS_SAMA = 3

    /** Batas panjang satu pesan; sisanya dibuang dengan penanda. */
    private const val MAKS_PESAN = 500

    /**
     * Batas laporan yang DISIMPAN di perangkat. Halaman web memotong
     * laporannya sendiri (200 kejadian); ini batas kedua supaya berkasnya tak
     * membengkak walau web mengirim lebih banyak.
     */
    private const val MAKS_LAPORAN = 40_000

    private val baris = ArrayDeque<String>()
    private const val KUNCI = "zxroom.log.apk"
    private const val PREFS = "zxroom"

    /**
     * Konteks aplikasi, diisi [pasangPenangkapCrash].
     *
     * Dipakai supaya [catat] bisa menulis ke disk tanpa harus diberi Context
     * oleh tiap pemanggil — pemanggilnya ada di MainActivity, PemilihBerkas,
     * PemeriksaPembaruan, dan penangkap crash, dan tak satu pun punya alasan
     * untuk peduli ke mana lognya disimpan.
     */
    @Volatile
    private var konteks: Context? = null

    /** Pesan terakhir & berapa kali berturut-turut — dasar peringkasan. */
    private var terakhir: String? = null
    private var ulangan = 0

    /**
     * Pasang penangkap untuk error yang menjatuhkan aplikasi.
     *
     * Tanpa ini, penyebab "aplikasi keluar sendiri" HILANG: Thread bawaan
     * Android menulis jejaknya ke logcat, dan logcat tak bisa dibaca dari HP
     * kasir tanpa komputer. Yang tersimpan di sini justru satu-satunya jejak
     * yang ikut terkirim lewat tombol "Kirim log error".
     *
     * Dipanggil sekali dari MainActivity. Handler lama tetap dipanggil balik
     * supaya perilaku `force close` Android tak berubah — keluar dengan pesan,
     * bukan diam.
     */
    fun pasangPenangkapCrash(konteks: Context) {
        this.konteks = konteks.applicationContext
        val bawaan = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { utas, galat ->
            try {
                catat(null, "APLIKASI BERHENTI MENDADAK (utas ${utas.name}): " + jejak(galat))
                // Tulis paksa SEBELUM keluar. [catat] sudah menulis ke disk
                // tiap kali, tapi di sini `commit()` dipakai lagi karena
                // jalur ini berjalan tepat sebelum proses mati: apa pun yang
                // tertunda tak akan pernah selesai.
                konteks.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KUNCI, isi()).commit()
            } catch (_: Throwable) {
                // Penangkap tak boleh menjatuhkan aplikasi: biarkan handler
                // bawaan di bawah yang menutupnya.
            }
            bawaan?.uncaughtException(utas, galat)
        }
    }

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
            null,
        )
    }

    /**
     * Catat penanda versi aplikasi yang sedang terpasang.
     *
     * Ditulis dari sisi APK, bukan dari halaman web: halaman hanya tahu versinya
     * sendiri, sedangkan laporan sering harus dipasangkan dengan VERSI APLIKASI
     * yang menjalankannya.
     */
    fun catatVersi(web: WebView?) {
        catat(web, "APK versi ${BuildConfig.VERSI_NAMA} (versiKode ${BuildConfig.VERSI_KODE})")
    }

    /**
     * Catat satu kejadian dari sisi aplikasi.
     *
     * Disimpan di memori DAN dikirim ke halaman web, kalau halaman sudah siap.
     * Memori dipakai sebagai cadangan saat halaman belum dimuat — kesalahan
     * yang paling sulit dilacak justru terjadi saat pemuatan awal.
     *
     * `web` boleh null; dipakai oleh penangkap crash, yang berjalan saat
     * halaman sudah tak bisa dipercaya lagi.
     */
    @Synchronized
    fun catat(web: WebView?, pesan: String) {
        // Peringkasan pengulangan identik: yang dicatat cuma tiga yang pertama,
        // selebihnya jadi satu baris "… (N kali)". Lihat catatan MAKS_SAMA.
        if (pesan == terakhir) {
            ulangan++
            if (ulangan > MAKS_SAMA) {
                if (ulangan == MAKS_SAMA + 1) {
                    baris.addLast(dipotong("[ulangan] pesan yang sama diulang — sisanya dihitung"))
                } else {
                    baris.removeLast()
                    baris.addLast("[ulangan] pesan yang sama diulang $ulangan kali total")
                }
                // Baris ringkasan ini juga ikut ke disk: isinya ("… diulang
                // N kali total") berubah tiap kali hitungannya naik, dan itu
                // satu-satunya jejak yang membedakan "sekali" dari "200 kali".
                simpanKeDisk()
                return
            }
        } else {
            terakhir = pesan
            ulangan = 1
        }

        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        baris.addLast("[$t] ${dipotong(pesan)}")
        while (baris.size > MAKS) baris.removeFirst()
        simpanKeDisk()

        web?.post {
            // Dikirim lewat event supaya JS yang menerjemahkannya ke console.error,
            // bukan disisipkan ke string — pesan bisa memuat tanda kutip dan
            // baris baru yang kalau disisipkan langsung akan merusak skripnya.
            val aman = JSONObject.quote(pesan)
            web.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('zxr-apk-log',{detail:$aman}))",
                null,
            )
        }
    }

    /**
     * Tulis isi log ke disk.
     *
     * ## Kenapa tiap kejadian, bukan cuma saat crash
     *
     * Dulu `baris` cuma hidup di memori proses; yang bertahan di disk hanya
     * tulisan di penangkap crash. Akibatnya persis seperti yang dilaporkan
     * kasir: tombol "Kirim log error" melaporkan **0 kejadian**.
     *
     * Sebabnya: yang menjatuhkan aplikasi bukan selalu crash. Android boleh
     * MEMBUNUH proses yang sedang di latar belakang — kamera terbuka, memori
     * sesak, kasir pindah aplikasi. Proses yang dibunuh begitu tak menjalankan
     * penangkap crash apa pun, jadi seluruh isi memori hilang tanpa jejak, dan
     * kejadian yang justru menjelaskan masalahnya (pembaruan APK, versi
     * terpasang, kamera gagal, izin ditolak) ikut lenyap.
     *
     * `apply()`, bukan `commit()`: tulisannya di latar belakang, dan `baris`
     * cuma sampai [MAKS] baris sehingga satu tulisan kecil — menahan UI kasir
     * untuk tiap baris log justru merugikan. Jalur yang berjalan tepat sebelum
     * proses mati tetap memakai `commit()` di [pasangPenangkapCrash].
     *
     * Dipanggil dengan `@Synchronized` dari [catat] dan [kosongkan] saja.
     */
    private fun simpanKeDisk() {
        val k = konteks ?: return
        try {
            k.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KUNCI, isi()).apply()
        } catch (_: Throwable) {
            // Menyimpan log tak boleh menjatuhkan aplikasi — kegagalan di sini
            // cuma berarti log sisi APK hilang, bukan aplikasi rusak.
        }
    }

    /**
     * Simpan laporan utuh dari halaman web ke perangkat.
     *
     * Dipanggil halaman lewat jembatan `ZXR_APK.simpanLaporan` SETELAH kiriman
     * sukses. Gunanya bukan arsip: kalau kasir mengirim ulang laporan, isinya
     * masih ada walau kiriman pertama gagal — dan laporan tak bisa lagi dibuat
     * ulang setelah kejadiannya lewat.
     */
    fun simpanLaporan(konteks: Context, laporan: String) {
        konteks.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KUNCI, laporan.take(MAKS_LAPORAN)).apply()
    }

    /** Laporan terakhir yang tersimpan di perangkat, atau kosong. */
    fun laporanTersimpan(konteks: Context): String =
        konteks.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KUNCI, "") ?: ""

    /** Isi log dari sisi aplikasi, untuk ditampilkan kalau halaman belum siap. */
    @Synchronized
    fun isi(): String = baris.joinToString("\n")

    /**
     * Buang log yang sudah terkirim. Dipanggil halaman web setelah kiriman
     * sukses, supaya kiriman berikutnya tidak mengulang yang lama.
     *
     * Simpanan terakhir TIDAK dihapus di sini — lihat [simpanLaporan].
     */
    @Synchronized
    fun kosongkan() {
        baris.clear()
        terakhir = null
        ulangan = 0
        // Disk ikut dikosongkan. Kalau tidak, isi lama tetap terbaca setelah
        // kiriman sukses, dan kiriman berikutnya mengulang kejadian yang sudah
        // terkirim — persis yang mau dihindari oleh fungsi ini.
        simpanKeDisk()
    }

    /**
     * Ambil ConsoleMessage dari halaman web — error JS yang tak sampai ke
     * penangkap web (mis. karena halaman memuat skripnya gagal).
     *
     * HANYA level ERROR. Penangkap web sengaja hanya menangkap error; menyalin
     * `console.warn`/`console.log` ke sini membuat laporan penuh sampah dan
     * yang penting tenggelam.
     */
    fun dariConsole(msg: ConsoleMessage, web: WebView?): Boolean {
        if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            catat(web, "web: ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
        }
        return false
    }

    /** Potong satu pesan agar satu kejadian tak menghabiskan seluruh laporan. */
    private fun dipotong(pesan: String): String =
        if (pesan.length <= MAKS_PESAN) pesan
        else pesan.take(MAKS_PESAN) + "… [dipotong ${pesan.length - MAKS_PESAN} karakter]"

    /** Jejak galat: jenis + pesan + beberapa baris pertama penelusuran tumpukan. */
    private fun jejak(galat: Throwable): String {
        val kepala = "${galat.javaClass.name}: ${galat.message}"
        val tumpukan = galat.stackTrace.take(15).joinToString(" <- ") {
            "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }
        return "$kepala | $tumpukan"
    }
}
