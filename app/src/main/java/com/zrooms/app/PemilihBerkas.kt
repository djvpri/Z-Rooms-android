package com.zrooms.app

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * Tombol "Kamera" & "Pilih file" pada `<input type="file">`.
 *
 * ## Kenapa kelas terpisah
 *
 * Dua alasan, keduanya dari crash yang benar-benar terjadi:
 *
 * 1. **Registrasi hasil WAJIB terjadi di onCreate, tanpa syarat apa pun.**
 *    `registerForActivityResult` menolak dipanggil setelah activity berjalan,
 *    dan callback-nya harus sudah ada saat activity dibangun ULANG — Android
 *    boleh membunuh proses saat aplikasi kamera terbuka, karena kamera itu
 *    operasi yang rakus memori ("almost certain that your process and your
 *    activity will be destroyed"). Callback yang didaftarkan bersyarat tak
 *    pernah ada saat pemulihan, dan yang terlihat kasir: aplikasi keluar
 *    sendiri.
 * 2. **Kamera menerima berkas lewat URI, bukan lewat extras.** Lihat [siapkanKamera].
 *
 * Semua urusan pemilih berkas dikumpulkan di sini supaya MainActivity tetap
 * jadi WebView tipis, dan supaya `scripts/check-berkas-webview.mjs` punya satu
 * tempat yang bisa diperiksa.
 */
class PemilihBerkas(
    private val activity: AppCompatActivity,
    private val catat: (String) -> Unit,
) {

    /** Permintaan yang sedang menunggu. Lihat catatan [batal]. */
    private var menunggu: ValueCallback<Array<Uri>>? = null

    /**
     * Berkas tujuan kamera. Disimpan sebagai field karena hasil Intent baru
     * datang setelah aplikasi kamera ditutup — boleh jadi setelah activity
     * dibangun ulang, dan saat itu URI-nya tak ada di Intent mana pun.
     */
    private var tujuanKamera: Uri? = null

    /** Aktivitas kamera yang sedang berjalan; dicegah dipanggil dua kali. */
    private var kameraJalan = false

    /**
     * Didaftarkan TANPA syarat dan HANYA di sini.
     *
     * Memilih berkas tak pernah butuh izin runtime: sistem memberi izin baca
     * per-URI saat hasilnya kembali. Yang butuh izin cuma kamera, dan izin itu
     * diminta di [mintaBerkas] tepat sebelum kamera dibuka — bukan di sini,
     * supaya dialog izin tak muncul untuk kasir yang hanya memilih dari galeri.
     */
    private val pilih: ActivityResultLauncher<android.content.Intent> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { hasil ->
            val cb = menunggu
            menunggu = null
            val tujuan = tujuanKamera
            tujuanKamera = null

            // `data == null` saat kasir MENUTUP kamera tanpa memotret. Dulu
            // jalur ini mengantar `parseResult` ke Intent kosong, padahal yang
            // ditanya "FileChooserParams"; pesannya menyesatkan.
            if (hasil.resultCode != Activity.RESULT_OK || hasil.data == null) {
                catat("pemilih selesai: DIBATALKAN (kode=${hasil.resultCode})")
                cb?.onReceiveValue(null)
                return@registerForActivityResult
            }

            // Kamera: hasilnya TIDAK dikirim lewat extras (dan sejak Android 11
            // extras dari aplikasi lain memang tak bisa dipercaya). Fotonya
            // sudah ditulis sendiri ke URI yang kita minta di [siapkanKamera].
            if (tujuan != null && cb != null) {
                catat("kamera selesai: foto tersimpan di ${tujuan.path}")
                cb.onReceiveValue(arrayOf(tujuan))
                return@registerForActivityResult
            }

            val uris = WebChromeClient.FileChooserParams.parseResult(hasil.resultCode, hasil.data)
            catat(if (uris.isNullOrEmpty())
                "pemilih selesai: tak ada berkas (kode=${hasil.resultCode})"
            else
                "pemilih selesai: ${uris.size} berkas")
            cb?.onReceiveValue(uris)
        }

    /** Izin kamera sudah diminta sekali di sesi ini? Lihat [mintaKamera]. */
    private var sudahMinta: Boolean = false

    private var kelanjutanIzin: (() -> Unit)? = null

    private val mintaIzin = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { diberikan ->
        sudahMinta = true
        val lanjut = kelanjutanIzin
        kelanjutanIzin = null
        if (!diberikan) {
            catat("izin kamera DITOLAK kasir")
            batal("Izin kamera ditolak. Buka Pengaturan Android → Aplikasi → ZXRoom → Izin, lalu nyalakan Kamera.")
        }
        lanjut?.invoke()
    }

    /**
     * Izin kamera diminta dari KASIR, bukan di jalur pembukaan aplikasi tanpa
     * sebab — dan hanya sekali per sesi. Kalau izinnya sudah ada, atau kasir
     * sudah pernah menolak, jalan langsung diteruskan: aplikasi kamera tetap
     * dibuka, dan `SecurityException` yang dulu mematikan proses tak lagi
     * terjadi karena pemanggilan izin sudah mendahului.
     */
    private fun mintaKamera(lanjut: () -> Unit) {
        val sudahPunya = activity.checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (sudahPunya || sudahMinta) {
            catat("izin kamera: ${if (sudahPunya) "sudah ada" else "pernah ditolak — diteruskan"}")
            lanjut()
            return
        }
        kelanjutanIzin = lanjut
        catat("izin kamera: diminta ke kasir")
        mintaIzin.launch(android.Manifest.permission.CAMERA)
    }

    /**
     * Titik masuk `onShowFileChooser`.
     *
     * @return true kalau permintaan diterima; false berarti WebView mundur ke
     *   perilaku bawaannya (yang pada praktiknya: tombol diam tanpa pesan).
     */
    fun mintaBerkas(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        catat("pilih berkas diminta: mode=${params.mode} accept=${params.acceptTypes.joinToString(",").take(60)} " +
            "kamera=${params.isCaptureEnabled()}")

        // Permintaan sebelumnya belum selesai: balas null supaya halaman web
        // tak menggantung, lalu layani yang baru.
        batal(null)
        menunggu = callback

        val langsungKamera = params.isCaptureEnabled()
        if (langsungKamera) {
            mintaKamera {
                bukaKamera(callback)
            }
        } else {
            bukaPemilih(callback, params)
        }
        return true
    }

    /** `isCaptureEnabled` baru ada API 30; minSdk aplikasi ini 24. */
    private fun WebChromeClient.FileChooserParams.isCaptureEnabled(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            this.isCaptureEnabled

    /**
     * Kamera: berkas DITULIS ke URI yang kita tentukan sendiri.
     *
     * Inilah akar "aplikasi keluar sendiri saat klik kamera". Tanpa
     * `EXTRA_OUTPUT`, aplikasi kamera mengembalikan fotonya lewat extras —
     * dan sejak Android 11 extras dari aplikasi lain TIDAK bisa dibaca lagi;
     * membacanya melempar `SecurityException` di WebView, bukan pesan galat.
     * Dengan `EXTRA_OUTPUT`, extras tak dipakai sama sekali: berkasnya kita
     * yang punya.
     *
     * URI-nya `content://` dari [FileProvider], bukan `file://`: sejak Android
     * 7 (`FileUriExposedException`) URI berkas telanjang ditolak begitu
     * diserahkan ke aplikasi lain.
     *
     * Mulai API 30, aplikasi kamera boleh MENOLAK kalau action-nya bukan
     * ACTION_IMAGE_CAPTURE, jadi kamera memakai intent baku; pemilih berkas
     * memakai `createIntent()` dari web karena di sanalah accept-types-nya
     * hidup.
     */
    private fun bukaKamera(callback: ValueCallback<Array<Uri>>) {
        if (kameraJalan) {
            catat("kamera sebelumnya masih terbuka — permintaan diabaikan")
            return
        }
        val tujuan = try {
            val berkas = File(File(activity.cacheDir, "ktp-foto").apply { mkdirs() }, "ktp.jpg")
            FileProvider.getUriForFile(activity, "${activity.packageName}.berkas", berkas)
        } catch (e: Exception) {
            catat("siapkan kamera GAGAL: ${e.javaClass.simpleName}: ${e.message}")
            batal("Kamera tidak bisa disiapkan di perangkat ini.")
            return
        }
        tujuanKamera = tujuan
        val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, tujuan)
            // Aplikasi kamera harus boleh MENULIS ke URI ini.
            .addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)

        if (intent.resolveActivity(activity.packageManager) == null) {
            // Sejak Android 11 pencarian begini cuma melihat aplikasi yang
            // terlihat; ketiadaan hasil berarti memang tak ada kamera terpasang.
            catat("tak ada aplikasi kamera")
            batal("Tidak ada aplikasi kamera di perangkat ini.")
            return
        }
        kameraJalan = true
        try {
            pilih.launch(intent)
            catat("kamera dibuka: tujuan=${tujuan.path}")
        } catch (e: Exception) {
            kameraJalan = false
            catat("KAMERA GAGAL DIBUKA: ${e.javaClass.simpleName}: ${e.message}")
            batal("Kamera tidak mau terbuka. Coba pilih berkas dari galeri.")
        }
    }

    private fun bukaPemilih(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams) {
        val intent = try {
            // createIntent() menyusun ACTION_GET_CONTENT lengkap dengan
            // accept-types dari atribut `accept` web — lebih benar daripada
            // menyusun sendiri dan lupa MIME-nya.
            params.createIntent()
        } catch (e: Exception) {
            catat("createIntent GAGAL: ${e.javaClass.simpleName}: ${e.message}")
            batal("Tidak bisa membuka pemilih berkas.")
            return
        }
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            pilih.launch(intent)
            catat("pemilih dibuka: ${intent.action}")
        } catch (e: Exception) {
            catat("LAUNCH GAGAL: ${e.javaClass.simpleName}: ${e.message}")
            batal("Tidak ada aplikasi yang bisa memilih berkas di perangkat ini.")
        }
    }

    /**
     * Balas permintaan yang menggantung dengan null, dan beri tahu halaman web
     * lewat jembatan supaya pesannya sampai ke kasir — bukan tombol yang diam.
     *
     * Callback yang TAK PERNAH dibalas membuat halaman web menunggu selamanya;
     * itu sebabnya jalur ini selalu dipanggil, termasuk saat gagal.
     */
    fun batal(pesan: String?) {
        val cb = menunggu
        menunggu = null
        catat(if (pesan == null) "permintaan dibatalkan" else "permintaan gagal: $pesan")
        cb?.onReceiveValue(null)
        if (pesan != null) {
            activity.runOnUiThread {
                android.widget.Toast.makeText(activity, pesan, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Dipanggil saat activity dilepas; tak ada callback yang boleh menggantung. */
    fun lepas() {
        batal(null)
        tujuanKamera = null
    }
}
