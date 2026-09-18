package com.zrooms.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Pengiriman naskah ke printer struk Bluetooth.
 *
 * KENAPA DI SINI, BUKAN DI WEB
 * Halaman web tak bisa membuka Bluetooth — itu hanya bisa dilakukan aplikasi
 * Android. Web menyiapkan teksnya (susunan kolom, lebar kertas), lalu kelas ini
 * yang mengubahnya menjadi byte ESC/POS dan mengirimkannya.
 *
 * BENTUK NASKAH
 * Naskah dari web berbentuk teks biasa dengan perintah sebagai penanda
 * `<27,64>` (angka byte dipisah koma di dalam kurung siku). Bentuk ini dipilih
 * karena `addJavascriptInterface` hanya bisa mengirim String, bukan byte.
 * PENGURAIANNYA HARUS SAMA dengan `naskahKeTeks` di lib/cetak.ts — kalau salah
 * satu berubah, perintah akan tercetak sebagai teks sampah di nota.
 *
 * KENAPA SOCKET DISIMPAN, BUKAN DIBUKA-TUTUP PER CETAK
 * Versi pertama menutup socket tiap selesai, dengan alasan printer murah sering
 * mati saat menganggur. Itu keliru: menyambung RFCOMM makan ~1 detik, dan
 * printer struk yang tetap menyala tak suka pasangan connect/close berulang.
 * Pola yang terbukti di aplikasi Z1 Label (printer label Bluetooth yang sudah
 * dipakai harian): socket disimpan selama hidup aplikasi, plus satu thread
 * pemantau yang menandai putus begitu printer benar-benar hilang. Sambungan
 * yang mati terdeteksi dari MONITOR, bukan dari menutup socket tiap kali.
 *
 * SATU INSTANSI PER APLIKASI
 * Karena socket hidup terus, `PrinterBluetooth` harus berupa object (singleton),
 * bukan kelas yang dibuat tiap kali mencetak — kalau tiap cetak membuat instance
 * baru, socket tersimpan itu ikut terbuang dan kita kembali ke connect/close.
 */
object PrinterBluetooth {
    private val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Nama berkas preferensi tempat alamat printer terakhir disimpan. */
    private const val NAMA_PREF = "zrooms_printer"
    private const val KUNCI_PRINTER = "alamat_terakhir"

    /**
     * Socket yang sedang hidup. `@Volatile` karena ditulis dari thread cetak
     * dan dibaca thread pemantau.
     */
    @Volatile
    private var socket: BluetoothSocket? = null
    @Volatile
    private var keluaran: OutputStream? = null
    @Volatile
    private var pemantau: Thread? = null
    @Volatile
    private var berjalan = false

    /** Alamat perangkat yang sedang tersambung — dipakai memutuskan perlu connect atau tidak. */
    @Volatile
    private var alamatTersambung: String? = null

    private lateinit var konteksApl: Context

    /**
     * Dipanggil sekali dari `MainActivity.onCreate`. Tanpa ini, preferensi dan
     * adapter Bluetooth tak punya konteks.
     */
    fun pasang(konteks: Context) {
        konteksApl = konteks.applicationContext
    }

    /**
     * Izin yang dibutuhkan untuk menyambung Bluetooth pada versi Android ini.
     * Android 12 memecahnya jadi dua, sementara versi lama sama sekali tak butuh
     * izin runtime.
     *
     * KHUSUS Android < 12, yang diperiksa adalah BLUETOOTH_* biasa, bukan
     * BLUETOOTH_CONNECT: memeriksa izin API-31 di HP lama membuat
     * `checkSelfPermission` selalu mengembalikan "belum diberi", sehingga tombol
     * cetak berhenti tanpa pernah membuka dialog apa pun — tombol yang terbaca
     * mati oleh kasir. (Bug ini nyata dan sudah diperbaiki di Z1 Label.)
     */
    fun izinDibutuhkan(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyArray()
        }

    /** Apakah semua izin Bluetooth sudah diberikan. */
    fun izinDiberikan(konteks: Context): Boolean = izinDibutuhkan().all {
        ContextCompat.checkSelfPermission(konteks, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Apakah socket sedang hidup. */
    fun tersambung(): Boolean = socket?.isConnected == true

    /**
     * Uraikan naskah dari web menjadi byte ESC/POS.
     *
     * Dipisah dari pengiriman supaya bisa diuji tanpa printer — dan inilah
     * bagian yang paling mudah salah, karena perintah dan teks biasa
     * bercampur dalam satu string.
     *
     * Aturan:
     *   - Baris `<27,64>` menjadi byte 27 64.
     *   - Baris lain menjadi teks + baris baru (LF, 10).
     *   - Kurung siku yang BUKAN angka byte diperlakukan sebagai teks
     *     biasa. Nota boleh memuat tanda `<` atau `>` — memaksakannya jadi
     *     perintah akan merusak isi nota.
     */
    fun uraikanNaskah(naskah: String): ByteArray {
        val keluaran = java.io.ByteArrayOutputStream()
        for (baris in naskah.split("\n")) {
            val perintah = bacaPerintah(baris)
            if (perintah != null) {
                keluaran.write(perintah)
            } else {
                // Charset ISO-8859-1: satu karakter = satu byte. UTF-8 akan
                // mengubah karakter beraksen jadi dua byte dan menggeser
                // seluruh baris di printer. Teks nota ditulis ASCII.
                keluaran.write(baris.toByteArray(Charsets.ISO_8859_1))
                keluaran.write(0x0a)
            }
        }
        return keluaran.toByteArray()
    }

    /**
     * Baca baris perintah `<27,64>` menjadi byte, atau null kalau baris ini
     * teks biasa.
     */
    private fun bacaPerintah(baris: String): ByteArray? {
        val t = baris.trim()
        if (t.length < 4 || !t.startsWith("<") || !t.endsWith(">")) return null
        val isi = t.substring(1, t.length - 1)
        // Hanya angka 0-255 yang dipisah koma. Apa pun selain itu = teks.
        if (isi.isEmpty()) return null
        val bagian = isi.split(",")
        val byte = ByteArray(bagian.size)
        for (i in bagian.indices) {
            val n = bagian[i].trim().toIntOrNull() ?: return null
            if (n < 0 || n > 255) return null
            byte[i] = n.toByte()
        }
        return byte
    }

    /** Nama printer yang dipasangkan ke perangkat ini. Kosong kalau Bluetooth mati. */
    @SuppressLint("MissingPermission")
    fun daftarPrinter(): List<String> {
        if (!izinDiberikan(konteksApl)) return emptyList()
        val adapter = adapter() ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().map { it.name ?: it.address }
        } catch (e: SecurityException) {
            // Terjadi kalau izin dicabut pengguna setelah pemeriksaan di atas.
            emptyList()
        }
    }

    /** Alamat MAC printer tersimpan, "" kalau belum pernah berhasil mencetak. */
    fun printerTersimpan(): String = konteksApl
        .getSharedPreferences(NAMA_PREF, Context.MODE_PRIVATE)
        .getString(KUNCI_PRINTER, "")
        .orEmpty()

    private fun simpanPrinter(alamat: String) {
        konteksApl.getSharedPreferences(NAMA_PREF, Context.MODE_PRIVATE)
            .edit().putString(KUNCI_PRINTER, alamat).apply()
    }

    private fun adapter(): BluetoothAdapter? =
        (konteksApl.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Kirim naskah ke printer.
     *
     * @param alamat MAC printer. Kosong = pakai printer tersimpan; kalau itu
     *   juga kosong, printer PERTAMA yang dipasangkan dipakai — kasir biasanya
     *   hanya memasangkan satu printer, dan memaksa memilih dulu akan membuat
     *   cetakan pertama selalu gagal.
     * @throws PesanKesalahanPrinter dengan kalimat yang bisa langsung dibaca
     *   kasir. Melempar pesan jadi lebih berguna daripada mengembalikan null:
     *   pemanggilnya (jembatan JS) tak punya tempat menaruh pesan itu.
     */
    @SuppressLint("MissingPermission")
    @Throws(PesanKesalahanPrinter::class)
    fun cetak(naskah: String, alamat: String = "") {
        if (!izinDiberikan(konteksApl)) {
            throw PesanKesalahanPrinter("Izin Bluetooth belum diberikan. Buka Pengaturan Android → Aplikasi → Z-Rooms → Izin.")
        }
        val adapter = adapter() ?: throw PesanKesalahanPrinter("Perangkat ini tidak punya Bluetooth.")
        if (!adapter.isEnabled) {
            throw PesanKesalahanPrinter("Bluetooth sedang mati. Nyalakan dulu, lalu coba lagi.")
        }

        val perangkat = pilihPerangkat(adapter, alamat)
            ?: throw PesanKesalahanPrinter("Belum ada printer yang dipasangkan. Pasangkan printer di Pengaturan Bluetooth Android.")

        val byte = uraikanNaskah(naskah)
        if (byte.isEmpty()) throw PesanKesalahanPrinter("Naskah nota kosong.")

        // Sudah tersambung ke printer yang sama? Kirim langsung. Ini yang
        // membuat cetakan kedua dan seterusnya terasa sekejap.
        if (!tersambung() || alamatTersambung != perangkat.address) {
            sambung(adapter, perangkat)
        }

        val keluaranSekarang = keluaran
            ?: throw PesanKesalahanPrinter("Sambungan printer terputus. Coba lagi.")

        try {
            keluaranSekarang.write(byte)
            keluaranSekarang.flush()
        } catch (e: IOException) {
            // Stream putus di tengah kirim: tutup supaya cetak berikutnya
            // menyambung ulang, bukan menulis ke socket mati.
            tutup()
            throw PesanKesalahanPrinter("Kirim ke printer gagal: ${e.message ?: "sambungan putus"}")
        }

        // Baru disimpan SETELAH berhasil — printer yang gagal tak boleh tercatat
        // sebagai "terakhir", karena nota berikutnya akan mencoba alamat yang
        // sama dan gagal lagi tanpa sebab yang jelas.
        simpanPrinter(perangkat.address)
    }

    /**
     * Sambung ke perangkat, mulai pemantau. Menutup sambungan lama dulu supaya
     * tak ada dua socket hidup.
     */
    @SuppressLint("MissingPermission")
    @Throws(PesanKesalahanPrinter::class)
    private fun sambung(adapter: BluetoothAdapter, perangkat: BluetoothDevice) {
        tutup()
        try {
            // Membatalkan penemuan WAJIB: penemuan perangkat memperlambat atau
            // menggagalkan sambungan, dan printer sudah diketahui alamatnya.
            try {
                adapter.cancelDiscovery()
            } catch (_: SecurityException) {
                // Tak berbahaya; sambungan tetap dicoba.
            }

            val s = bukaSocket(perangkat)
            s.connect()
            socket = s
            keluaran = s.outputStream
            alamatTersambung = perangkat.address
            mulaiPemantau(s)
        } catch (e: SecurityException) {
            tutup()
            throw PesanKesalahanPrinter("Izin Bluetooth ditolak Android. Buka Pengaturan Android → Aplikasi → Z-Rooms → Izin.")
        } catch (e: IOException) {
            tutup()
            throw PesanKesalahanPrinter("Tidak bisa tersambung ke printer ${perangkat.name ?: perangkat.address}. Pastikan printer menyala, lalu coba lagi.")
        }
    }

    /**
     * Buka socket RFCOMM: jalur SPP standar dulu, lalu refleksi
     * `createRfcommSocket(1)` sebagai cadangan.
     *
     * Cadangan ini bukan kemewahan — sebagian printer struk murah tak
     * mendaftarkan UUID SPP, dan `createRfcommSocketToServiceRecord` gagal di
     * alat itu. Tanpa cadangan, printer yang sebenarnya bisa dipakai akan
     * dilaporkan "tidak bisa tersambung" selamanya. Pola ini dipakai aplikasi
     * label Z1 Label yang sudah terbukti di lapangan.
     */
    @SuppressLint("MissingPermission")
    private fun bukaSocket(perangkat: BluetoothDevice): BluetoothSocket {
        try {
            return perangkat.createRfcommSocketToServiceRecord(UUID_SPP)
        } catch (_: Exception) {
            // Lanjut ke cadangan refleksi di bawah.
        }
        val metode = perangkat.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return metode.invoke(perangkat, 1) as BluetoothSocket
    }

    /**
     * Pantau aliran masuk: begitu printer mati atau menjauh, `read` mengembalikan
     * -1 atau melempar, dan sambungan ditutup.
     *
     * Inilah pengganti "tutup socket tiap cetak": sambungan mati terdeteksi dari
     * perangkatnya sendiri, jadi kita bisa menyimpan socket selama printer
     * memang hidup.
     */
    private fun mulaiPemantau(s: BluetoothSocket) {
        berjalan = true
        pemantau?.interrupt()
        pemantau = Thread {
            try {
                val masuk = s.inputStream
                val buf = ByteArray(64)
                while (berjalan && s.isConnected) {
                    if (masuk.read(buf) < 0) break // EOF = printer menutup
                }
            } catch (_: Exception) {
                // Putus tak disengaja; ditangani di finally.
            } finally {
                // Hanya tutup kalau pemantau ini memang masih yang berlaku —
                // kalau tidak, sambungan BARU ikut ditutup oleh thread lama.
                if (berjalan && socket === s) tutup()
            }
        }.apply {
            isDaemon = true
            name = "zrooms-printer-monitor"
        }.also { it.start() }
    }

    /** Tutup socket & hentikan pemantau. Aman dipanggil berkali-kali. */
    fun tutup() {
        berjalan = false
        pemantau?.let { runCatching { it.interrupt() } }
        pemantau = null
        runCatching { keluaran?.close() }
        runCatching { socket?.close() }
        keluaran = null
        socket = null
        alamatTersambung = null
    }

    /**
     * Pilih perangkat yang akan dipakai, berurutan: alamat yang diminta, lalu
     * printer tersimpan, lalu satu-satunya printer yang dipasangkan.
     */
    @SuppressLint("MissingPermission")
    private fun pilihPerangkat(adapter: BluetoothAdapter, alamat: String): BluetoothDevice? {
        val terpasang = try {
            adapter.bondedDevices.orEmpty().toList()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (terpasang.isEmpty()) return null

        val dicari = listOf(alamat, printerTersimpan()).firstOrNull { it.isNotBlank() }
        if (dicari != null) {
            terpasang.firstOrNull { it.address.equals(dicari, ignoreCase = true) }?.let { return it }
            // Alamat tersimpan tak lagi terpasang (printer diganti/dilupakan):
            // jatuh ke bawaan di bawah, bukan gagal.
        }
        return terpasang.firstOrNull()
    }
}

/**
 * Kesalahan printer dengan kalimat yang ditujukan ke kasir.
 *
 * Dibedakan dari Exception biasa supaya pemanggil bisa menampilkan
 * `message`-nya apa adanya ke halaman web.
 */
class PesanKesalahanPrinter(message: String) : Exception(message)
