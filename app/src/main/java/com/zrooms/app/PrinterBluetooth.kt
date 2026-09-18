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
 * KENAPA SATU KONEKSI PER CETAK
 * Socket ditutup begitu selesai. Printer thermal murah sering mati sendiri saat
 * menganggur, dan socket yang dibiarkan terbuka jadi "tersambung" palsu yang
 * gagal saat dipakai. Menyambung ulang tiap cetak menambah sekitar satu detik,
 * tapi selalu benar.
 */
class PrinterBluetooth(private val konteks: Context) {

    companion object {
        /**
         * UUID layanan SPP (Serial Port Profile) — standar Bluetooth, sama di
         * semua printer struk. Bukan nilai yang boleh diganti sembarangan.
         */
        private val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Nama berkas preferensi tempat alamat printer terakhir disimpan. */
        private const val NAMA_PREF = "zrooms_printer"
        private const val KUNCI_PRINTER = "alamat_terakhir"

        /**
         * Izin yang dibutuhkan untuk menyambung Bluetooth pada versi Android
         * ini. Dikembalikan sebagai daftar karena Android 12 memecahnya jadi
         * dua, sementara versi lama sama sekali tak butuh izin runtime.
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
    }

    /** Nama printer yang dipasangkan ke perangkat ini. "" kalau Bluetooth mati. */
    @SuppressLint("MissingPermission")
    fun daftarPrinter(): List<String> {
        if (!izinDiberikan(konteks)) return emptyList()
        val adapter = adapter() ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().map { it.name ?: it.address }
        } catch (e: SecurityException) {
            // Terjadi kalau izin dicabut pengguna setelah pemeriksaan di atas.
            emptyList()
        }
    }

    /** Alamat MAC printer tersimpan, "" kalau belum pernah berhasil mencetak. */
    fun printerTersimpan(): String = konteks.getSharedPreferences(NAMA_PREF, Context.MODE_PRIVATE)
        .getString(KUNCI_PRINTER, "").orEmpty()

    private fun simpanPrinter(alamat: String) {
        konteks.getSharedPreferences(NAMA_PREF, Context.MODE_PRIVATE)
            .edit().putString(KUNCI_PRINTER, alamat).apply()
    }

    private fun adapter(): BluetoothAdapter? =
        (konteks.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

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
        if (!izinDiberikan(konteks)) {
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

        var socket: BluetoothSocket? = null
        try {
            // createRfcommSocketToServiceRecord adalah jalur standar; printer
            // yang menyimpang dari SPP tak terduga tak didukung di sini, karena
            // mencoba banyak jalur membuat kesalahan sambungan jadi tak jelas.
            socket = perangkat.createRfcommSocketToServiceRecord(UUID_SPP)
            // Membatalkan penemuan WAJIB: penemuan perangkat memperlambat atau
            // menggagalkan sambungan, dan printer sudah diketahui alamatnya.
            try {
                adapter.cancelDiscovery()
            } catch (_: SecurityException) {
                // Tak berbahaya; sambungan tetap dicoba.
            }

            socket.connect()
            val keluaran: OutputStream = socket.outputStream
            keluaran.write(byte)
            keluaran.flush()
            // Beri waktu printer mencetak sebelum socket ditutup. Tanpa jeda
            // ini, potongan terakhir sering hilang pada printer murah.
            Thread.sleep(400)

            // Baru disimpan SETELAH berhasil — printer yang gagal tak boleh
            // tercatat sebagai "terakhir", karena nota berikutnya akan mencoba
            // alamat yang sama dan gagal lagi tanpa sebab yang jelas.
            simpanPrinter(perangkat.address)
        } catch (e: SecurityException) {
            throw PesanKesalahanPrinter("Izin Bluetooth ditolak Android. Buka Pengaturan Android → Aplikasi → Z-Rooms → Izin.")
        } catch (e: IOException) {
            throw PesanKesalahanPrinter("Tidak bisa tersambung ke printer ${perangkat.name ?: perangkat.address}. Pastikan printer menyala, lalu coba lagi.")
        } finally {
            try {
                socket?.close()
            } catch (_: IOException) {
                // Socket sudah tak berguna; gagal menutup bukan masalah.
            }
        }
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
