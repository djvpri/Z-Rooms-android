package com.zrooms.app

import android.content.Context
import android.webkit.JavascriptInterface

/**
 * Jembatan dari halaman web ke aplikasi.
 *
 * Permukaannya sengaja SEMPIT: satu metode, tanpa argumen, tanpa nilai balik.
 * Setiap metode yang menerima teks dari halaman berarti teks itu dipercaya,
 * dan halaman web bisa dipengaruhi (gambar, tautan, iklan) — jadi yang bisa
 * dilakukan dari sini hanya memberi tahu, bukan memerintah.
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
        LogWeb.kosongkan(konteks)
    }
}
