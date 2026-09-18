package com.zrooms.app

import android.content.Context
import android.webkit.JavascriptInterface

/**
 * Jembatan dari halaman web ke aplikasi.
 *
 * Permukaannya sengaja SEMPIT: dua metode, keduanya hanya MEMBERI TAHU, tanpa
 * nilai balik yang bisa dipakai halaman untuk membaca data aplikasi. Setiap
 * metode yang menerima teks dari halaman berarti teks itu dipercaya, dan
 * halaman web bisa dipengaruhi (gambar, tautan, iklan) — jadi dari sini tak ada
 * yang bisa memerintah aplikasi.
 *
 * Dipasang HANYA saat halaman yang dimuat berasal dari host ZXRoom sendiri —
 * lihat pemasangan di MainActivity. Tanpa syarat itu, situs pihak ketiga yang
 * terlanjur terbuka di WebView bisa memanggil jembatan ini.
 */
class JembatanApk(private val konteks: Context) {

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
}
