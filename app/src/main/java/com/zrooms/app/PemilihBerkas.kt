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
    /** Dipanggil saat balik dari kamera: WebView harus jadi fokus lagi (lihat [saatBalikDariKamera]). */
    private val fokuskanWeb: () -> Unit,
) {

    companion object {
        /**
         * Foto KTP terakhir, terkompresi lalu di-base64-kan.
         *
         * Dijembatani ke halaman web lewat [JembatanApk.fotoKtp] — BUKAN lewat
         * `<input type=file>`: pembacaan berkas oleh WebView di beberapa
         * perangkat (itel S685LN, Android 15) SELALU menghasilkan File 0 byte
         * di tiga jalur berbeda (FileProvider cache-dir, MediaStore, dan
         * FileProvider ktp.jpg; produksi 2026-10-03 s.d. 04). APK sendiri selalu
         * bisa membacanya dengan benar via ContentResolver — jadi fotonya
         * disalin ke memori sini, dan halaman mengambilnya lewat jembatan.
         */
        @Volatile
        var fotoKtpBase64: String? = null
    }

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
            // Aktivitas kamera/pemilih sudah ditutup — apa pun hasilnya, tanda
            // "kamera masih jalan" wajib dilepas. Dulu tidak: sekali kamera
            // ditutup, kameraJalan tetap true dan klik berikutnya diabaikan
            // diam-diam, sementara callback yang baru mendaftar menggantung dan
            // memblokir SEMUA pemilih berkas sampai aplikasi ditutup (kasus
            // produksi 2026-10-03: klik kedua Kamera dan Pilih File mati total).
            kameraJalan = false
            // WebView yang menunggu hasil harus jadi fokus lagi SEKARANG —
            // bukan menunggu onResume activity (lihat [saatBalikDariKamera]).
            saatBalikDariKamera()

            // Kamera dengan EXTRA_OUTPUT kerap membalas RESULT_OK tanpa data
            // (fotonya sudah ditulis ke URI milik kita). Jalur kamera dicek
            // lebih dulu; `data == null` hanya berarti batal bagi pemilih
            // berkas biasa.
            if (tujuan != null && cb != null) {
                if (hasil.resultCode == Activity.RESULT_OK) {
                    // Kamera OEM (Transsion/itel) kerap MENGABAIKAN EXTRA_OUTPUT
                    // ke cache-dir aplikasi dan menyimpan sendiri ke penyimpanan
                    // eksternalnya — lalu gagal di izinnya sendiri ("izin ditolak,
                    // tidak dapat menulis penyimpanan eksternal", produksi
                    // 2026-10-03). Sejak 1.0.36 targetnya MediaStore (Pictures),
                    // jalur yang kamera OEM selalu bisa tulis. Foto dibaca via
                    // ContentResolver (berlaku utk MediaStore ATAU berkas), lalu
                    // DISALIN ke berkas KTP internal; entri galeri dibersihkan.
                    //
                    // 1.0.37: produksi 03:14 — kamera itel membalas RESULT_OK
                    // SEBELUM tulisan MediaStore-nya flush: URI terbaca tapi
                    // isinya 0 byte → WebView deliver berkas kosong → web
                    // "failed to fetch". Baca pakai RETRY: 3× jeda 0,5 dtk,
                    // hanya deliver kalau ada isi nyata.
                    val berkasKtp = File(File(activity.cacheDir, "ktp-foto").apply { mkdirs() }, "ktp.jpg")
                    var ukuran = -1L
                    for (coba in 1..3) {
                        try {
                            activity.contentResolver.openInputStream(tujuan)?.use { masuk ->
                                berkasKtp.outputStream().use { keluar -> masuk.copyTo(keluar) }
                            } ?: catat("kamera: resolver tak bisa buka $tujuan (coba $coba)")
                        } catch (e: Exception) {
                            catat("kamera: salin GAGAL (coba $coba): ${e.javaClass.simpleName}: ${e.message}")
                        }
                        ukuran = berkasKtp.length()
                        catat("kamera: baca coba $coba → $ukuran byte")
                        if (ukuran > 0L) break
                        if (coba < 3) Thread.sleep(500)
                    }
                    if (ukuran <= 0L) {
                        catat(
                            "KAMERA GAGAL TULIS FOTO: RESULT_OK tapi foto tetap 0 byte " +
                                "setelah 3 coba (tujuan=$tujuan). Kamera belum flush/menulis."
                        )
                        bersihkanTargetKamera(tujuan)
                        batal("Kamera gagal menyimpan foto. Coba lagi, atau pilih dari galeri.")
                        return@registerForActivityResult
                    }
                    bersihkanTargetKamera(tujuan)
                    // Simpan salinan terkompresi untuk jembatan fotoKtp() —
                    // jalur utama ke web; lihat [fotoKtpBase64]. Gagal kompres
                    // tak membatalkan deliver: fallback input file masih jalan.
                    try {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(berkasKtp.absolutePath)
                        if (bitmap != null) {
                            val keluaran = java.io.ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, keluaran)
                            bitmap.recycle()
                            if (keluaran.size() > 0) {
                                fotoKtpBase64 = android.util.Base64.encodeToString(
                                    keluaran.toByteArray(), android.util.Base64.NO_WRAP)
                                catat("kamera: salinan jembatan siap (${fotoKtpBase64!!.length} b64)")
                            }
                        } else {
                            catat("kamera: kompres jembatan gagal (bukan bitmap)")
                        }
                    } catch (e: Exception) {
                        catat("kamera: kompres jembatan GAGAL: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    // Balas URI berkas internal (content:// FileProvider) ke web:
                    // WebView/mesin cetak hanya butuh URI yang bisa dibaca lokal.
                    val uriKtp = FileProvider.getUriForFile(activity, "${activity.packageName}.berkas",
                        File(File(activity.cacheDir, "ktp-foto"), "ktp.jpg"))
                    cb.onReceiveValue(arrayOf(uriKtp))
                } else {
                    catat("kamera dibatalkan kasir (kode=${hasil.resultCode})")
                    bersihkanTargetKamera(tujuan)
                    cb.onReceiveValue(null)
                }
                return@registerForActivityResult
            }

            // `data == null` saat kasir MENUTUP kamera tanpa memotret. Dulu
            // jalur ini mengantar `parseResult` ke Intent kosong, padahal yang
            // ditanya "FileChooserParams"; pesannya menyesatkan.
            if (hasil.resultCode != Activity.RESULT_OK || hasil.data == null) {
                catat("pemilih selesai: DIBATALKAN (kode=${hasil.resultCode})")
                cb?.onReceiveValue(null)
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
            catat("kamera sebelumnya masih terbuka — permintaan lama dibatalkan")
            // Jangan biarkan callback baru menggantung: WebView menunggu balasan
            // dan semua tombol berkas lain ikut mati sampai dibalas.
            callback.onReceiveValue(null)
            return
        }
        val tujuan: Uri
        try {
            tujuan = siapkanTargetKamera() ?: return
        } catch (e: Exception) {
            catat("siapkan kamera GAGAL: ${e.javaClass.simpleName}: ${e.message}")
            batal("Kamera tidak bisa disiapkan di perangkat ini.")
            return
        }
        tujuanKamera = tujuan
        // Disusun per-baris, bukan chain: stub setter tertentu di android.jar
        // membuat seluruh chain ter-infer Unit (kompilasi 2026-10-03: "inferred
        // type is Unit but Intent! was expected" mematikan dua pemakaian
        // `intent` di bawahnya).
        val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, tujuan)
        // Aplikasi kamera harus boleh MENULIS ke URI ini.
        intent.addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Kamera OEM (Transsion/itel dkk) kerap mengabaikan grant lewat flags
        // saja: ia gagal membaca URI, mundur ke jalur simpan sendiri di
        // penyimpanan eksternal, lalu error "izin ditolak — tidak dapat
        // menulis penyimpanan eksternal". ClipData menyalin URI + grant ke
        // tumpukan aktivitas — cara baku yang selalu sampai (kasus produksi
        // 2026-10-03, itel S685LN Android 15).
        intent.clipData = android.content.ClipData.newRawUri("ktp", tujuan)

        // Beri grant langsung ke tiap paket kamera, di luar mekanisme Intent:
        // sebagian kamera OEM membaca berkas lewat jalur yang tak dibawa flags.
        // queryIntentActivities: MATCH_DEFAULT_ONLY saja kurang — pakai 0 agar
        // semua kamera terpasang (bisa diintent-filter-nya bukan DEFAULT) ikut
        // kebagian grant.
        // ResolveInfoFlags baru Android 13; overload Int sudah didepresiasi di
        // 33 tapi tetap ada — minSdk 24 menuntutnya. Panggilan berdaftar 2
        // argumen (bukan named/unamed campur) supaya stub mana pun cocok.
        val kameraTersedia: List<android.content.pm.ResolveInfo> =
            activity.packageManager.queryIntentActivities(intent, 0)
        for (res in kameraTersedia) {
            activity.grantUriPermission(
                res.activityInfo.packageName,
                tujuan,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        if (kameraTersedia.isEmpty()) {
            // Daftar kamera diambil dari queryIntentActivities di atas
            // (sejak Android 11, resolveActivity butuh <queries> di manifes
            // dan kerap balik null pada kamera OEM — daftar eksplisit lebih
            // dapat diandalkan).
            catat("tak ada aplikasi kamera")
            batal("Tidak ada aplikasi kamera di perangkat ini.")
            return
        }
        // DIAGNOSTIK "izin ditolak — tidak dapat menulis penyimpanan eksternal":
        // kalau grant URI kita tak sampai ke proses kamera, kamera OEM mundur
        // menyimpan ke penyimpanan eksternalnya sendiri (butuh WRITE_EXTERNAL_
        // STORAGE / MANAGE_EXTERNAL_STORAGE) dan ditolak di sana. Catat paket +
        // status grant per-uid tiap kamera supaya log kasir menunjukkan
        // siapa yang kebagian dan siapa yang tidak.
        val daftarKamera = kameraTersedia.joinToString(", ") { res ->
            val pkg = res.activityInfo.packageName
            val uid = res.activityInfo.applicationInfo.uid
            val granted = activity.checkUriPermission(
                tujuan, -1, uid,
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            "$pkg(uid=$uid,grant=${if (granted) "OK" else "TAK_TERBACA"})"
        }
        catat("kamera terdeteksi: $daftarKamera")
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

    /**
     * WebView balik ke depan setelah kamera/pemilih ditutup — paksa WebView
     * yang menunggu jadi fokus lagi.
     *
     * Kasus produksi 2026-10-03 (itel S685LN, Android 15, WebView 153): kamera
     * dibuka 16:52 lalu sejak 17:32 SEMUA klik kamera DIAM — klik sampai ke
     * `el.click()` (instrumentasi web `b6bf18d`) tapi `onShowFileChooser` tak
     * pernah dipanggil lagi, bahkan setelah proses di-restart dan halaman
     * dimuat ulang. Chromium menahan pemilih bila WebView yang menunggu bukan
     * lagi jendela fokus; pemanggilan `onResume` saja tidak cukup karena
     * resumeTimers masih ditahan oleh kamera OEM.
     */
    fun saatBalikDariKamera() {
        activity.runOnUiThread {
            fokuskanWeb()
            catat("saatBalikDariKamera: resumeTimers+requestFocus dipanggil")
        }
    }

    /**
     * Target foto kamera via MediaStore (galeri Android baku).
     *
     * Sejak 1.0.36: kamera OEM Transsion/itel ternyata MENGABAIKAN target
     * cache-dir via FileProvider (produksi 2026-10-03: RESULT_OK tapi foto tak
     * ada + toast 'izin ditolak, tidak dapat menulis penyimpanan eksternal' —
     * kamera mundur menyimpan ke penyimpanannya sendiri dan kena izinnya
     * sendiri), padahal memotret manual dari ikon kamera selalu sukses.
     * MediaStore di Pictures adalah jalur yang kamera OEM selalu bisa tulis.
     *
     * Android < 10 tak punya MediaStore API Modern → tetap jalur lama
     * (cache-dir + FileProvider; di perangkat lama grant-nya bekerja).
     */
    private fun siapkanTargetKamera(): Uri? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            val berkas = File(File(activity.cacheDir, "ktp-foto").apply { mkdirs() }, "ktp.jpg")
            return FileProvider.getUriForFile(activity, "${activity.packageName}.berkas", berkas)
        }
        val nilai = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "zrooms-ktp-${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Z-Rooms")
        }
        return activity.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, nilai
        ) ?: run {
            catat("MediaStore insert GAGAL — mundur ke cache-dir")
            val berkas = File(File(activity.cacheDir, "ktp-foto").apply { mkdirs() }, "ktp.jpg")
            FileProvider.getUriForFile(activity, "${activity.packageName}.berkas", berkas)
        }
    }

    /** Hapus sisa target kamera: entri MediaStore (galeri) atau berkas cache. */
    private fun bersihkanTargetKamera(uri: Uri) {
        try {
            if (uri.scheme == "content" &&
                uri.authority == "media" &&
                activity.contentResolver.delete(uri, null, null) > 0
            ) {
                catat("kamera: entri galeri dibersihkan")
            }
        } catch (e: Exception) {
            catat("kamera: bersih galeri GAGAL: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
