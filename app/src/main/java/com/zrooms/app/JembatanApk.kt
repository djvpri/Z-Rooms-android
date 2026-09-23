package com.zrooms.app

import android.content.Context
import android.webkit.JavascriptInterface

/**
 * Jembatan dari halaman web ke aplikasi.
 *
 * Permukaannya sengaja SEMPIT: metode yang menerima teks dari halaman hanya
 * menyimpannya atau mengirimkannya ke printer — tak ada yang bisa dipakai
 * halaman untuk membaca data pribadi dari perangkat.
 *
 * Dipasang HANYA saat halaman yang dimuat berasal dari host ZXRoom sendiri —
 * lihat pemasangan di MainActivity. Tanpa syarat itu, situs pihak ketiga yang
 * terlanjur terbuka di WebView bisa memanggil jembatan ini.
 */
class JembatanApk(
    private val konteks: Context,
    /**
     * Dijalankan di thread utama dengan naskah nota.
     *
     * Cetak TIDAK dikerjakan di sini karena Bluetooth memblokir: menyambung
     * butuh ratusan milidetik sampai beberapa detik, dan mengerjakannya di
     * thread UI membuat halaman web membeku selama itu. MainActivity yang
     * memindahkannya ke thread lain, lalu memanggil balik lewat JavaScript.
     */
    private val cetak: (naskah: String) -> Unit,
) {

    /**
     * Dipanggil halaman setelah log berhasil terkirim, supaya simpanan log di
     * sisi APK ikut dikosongkan dan tak terkirim ulang pada kiriman berikutnya.
     *
     * Sekadar memberi tahu: salah panggil pun tak merusak apa pun — paling
     * banyak log APK terbuang, dan itu bisa dibuat lagi dengan mengulang
     * langkah yang bermasalah.
     */
    @JavascriptInterface
    fun kosongkan() {
        LogWeb.kosongkan()
    }

    /**
     * Salinan laporan yang baru terkirim, disimpan di perangkat.
     *
     * Gunanya bukan arsip: kalau kasir menekan kirim lagi, isinya masih ada
     * walau kiriman pertama gagal — dan laporan tak bisa dibuat ulang setelah
     * kejadiannya lewat.
     *
     * Dibatasi panjangnya di [LogWeb] (laporan halaman sendiri sudah dipotong
     * 200 kejadian). Teks dari halaman diperlakukan sebagai data, bukan
     * perintah: yang dilakukan hanya menyimpannya.
     */
    @JavascriptInterface
    fun simpanLaporan(laporan: String) {
        LogWeb.simpanLaporan(konteks, laporan)
    }

    /**
     * Cetak naskah nota ke printer Bluetooth.
     *
     * TIDAK mengembalikan apa pun: hasilnya (berhasil/gagal) dilaporkan
     * BELAKANGAN lewat `ZXR_CETAK_HASIL(...)` karena menyambung printer makan
     * waktu — dan `@JavascriptInterface` yang menunggu akan membekukan halaman.
     * Halaman harus menyiapkan `window.ZXR_CETAK_HASIL` sebelum memanggil ini.
     *
     * Panjang dibatasi supaya halaman yang salah (atau situs yang menyusup)
     * tak mengirim teks raksasa yang menghabiskan memori.
     */
    @JavascriptInterface
        fun cetak(naskah: String) {
            if (naskah.length > MAKS_NASKAH) {
                laporHasil(false, "Naskah nota terlalu panjang (${naskah.length} karakter).")
                return
            }
            // Bungkus callback cetakStruk: exception yang sampai ke luar
            // @JavascriptInterface ditangkap WebView sebagai "Error invoking
            // cetak" — pesan asli (mis. "Belum ada printer yang dipasangkan")
            // hilang. Tangkap di sini agar pesan kasir-friendly sampai ke
            // halaman lewat ZXR_CETAK_HASIL.
            try {
                this.cetak(naskah)
            } catch (e: PesanKesalahanPrinter) {
                laporHasil(false, e.message ?: "Cetak gagal.")
            } catch (e: Exception) {
                laporHasil(false, "Cetak gagal: ${e.message ?: e.javaClass.simpleName}")
                LogWeb.catat(null, "cetak: exception dari jembatan — ${e.javaClass.name}: ${e.message}")
            }
        }

    /**
     * Daftar printer yang sudah dipasangkan ke perangkat, dipisah baris baru.
     *
     * Sengaja mengembalikan daftar nama+alamat, bukan alamat saja: kasir
     * memilih berdasarkan nama yang dikenali ("RPP02N"), bukan MAC.
     */
    @JavascriptInterface
    fun daftarPrinter(): String {
        if (!PrinterBluetooth.izinDiberikan(konteks)) return ""
        return PrinterBluetooth.daftarPrinter().joinToString("\n")
    }

    /** Alamat printer terakhir yang berhasil dipakai, "address name" atau "address". */
    @JavascriptInterface
    fun printerTersimpan(): String = PrinterBluetooth.printerTersimpan()

    /**
     * Nama printer tersimpan (bukan alamat MAC) — lebih enak dibaca kasir.
     * Kosong kalau belum pernah mencetak.
     */
    @JavascriptInterface
    fun namaPrinterTersimpan(): String {
        val alamat = PrinterBluetooth.printerTersimpan()
        if (alamat.isBlank()) return ""
        return PrinterBluetooth.namaPerangkat(alamat) ?: alamat
    }

    /**
     * Apakah socket printer sedang hidup?
     *
     * Halaman memakai ini untuk menampilkan status sambungan tanpa harus
     * mencetak sesuatu. `false` bukan berarti rusak — bisa saja sengaja
     * belum disambung.
     */
    @JavascriptInterface
    fun statusPrinter(): Boolean = PrinterBluetooth.tersambung()

    /**
     * Buka dialog pemilih printer bawaan Android (native).
     *
     * Dialognya ada di APK, bukan di web: daftar perangkat Bluetooth berubah
     * tiap detik saat memindai, dan melewatkannya lewat jembatan JS berarti
     * memompa daftar bolak-balik tiap perangkat ditemukan. Sekali pilih di
     * dialog, hasilnya (nama+alamat) balik lewat `ZXR_PRINTER_DIPILIH(...)`.
     *
     * Halaman harus menyiapkan `window.ZXR_PRINTER_DIPILIH(nama, alamat)`
     * sebelum memanggil ini; dipanggil `null, null` kalau dialog dibatalkan.
     */
    @JavascriptInterface
    fun pilihPrinter() {
        MainActivity.bukaDialogPrinter()
    }

    /**
     * Kode versi APK. Dipakai halaman untuk memastikan APK-nya cukup baru:
     * jembatan ini bisa dipanggil dari APK lama yang tak punya `cetak`, dan
     * halaman perlu membedakan "belum ada" dari "ada tapi diam".
     */
    @JavascriptInterface
    fun versi(): String = BuildConfig.VERSION_NAME

    private fun laporHasil(ok: Boolean, pesan: String) {
        MainActivity.jalankanJs(hasilCetakJs(ok, pesan))
    }

    companion object {
        /** Batas panjang naskah. Nota thermal wajar < 4 KB. */
        const val MAKS_NASKAH = 16_000

        /**
         * Panggil `window.ZXR_CETAK_HASIL(ok, pesan)` di halaman.
         *
         * Pesannya di-escape sebagai string JSON, bukan disisipkan apa adanya:
         * pesan kesalahan memuat nama printer dan tanda kutip, dan menyisipkan
         * mentah-mentah akan membuat skripnya rusak — tepat saat kasir butuh
         * membaca penyebabnya.
         */
        fun hasilCetakJs(ok: Boolean, pesan: String): String {
            val isi = org.json.JSONObject.quote(pesan)
            return "if (window.ZXR_CETAK_HASIL) window.ZXR_CETAK_HASIL($ok, $isi);"
        }

        /**
         * Panggil `window.ZXR_PRINTER_DIPILIH(nama, alamat)` di halaman.
         *
         * Di-escape sebagai string JSON — nama printer (dan alamat MAC) bisa
         * memuat karakter yang merusak skrip kalau disisipkan mentah-mentah.
         * `null` dikirim apa adanya (bukan string "null") saat dibatalkan,
         * supaya halaman bisa membedakan "dibatalkan" dari "printer bernama null".
         */
        fun printerDipilihJs(nama: String?, alamat: String?): String {
            val n = if (nama == null) "null" else org.json.JSONObject.quote(nama)
            val a = if (alamat == null) "null" else org.json.JSONObject.quote(alamat)
            return "if (window.ZXR_PRINTER_DIPILIH) window.ZXR_PRINTER_DIPILIH($n, $a);"
        }

        /**
         * Laporkan status sambungan printer ke halaman: `window.ZXR_PRINTER_STATUS(true/false)`.
         *
         * Halaman memakainya untuk menampilkan indikator "printer tersambung"
         * tanpa perlu mencetak dulu — karena sambungan kini dipasang saat
         * aplikasi dibuka, statusnya bisa berubah tanpa halaman meminta apa pun.
         */
        fun statusPrinterJs(tersambung: Boolean): String =
            "if (window.ZXR_PRINTER_STATUS) window.ZXR_PRINTER_STATUS($tersambung);"
    }
}
