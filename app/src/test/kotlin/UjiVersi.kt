// Uji logika versi pembaruan mandiri Z-Rooms.
//
// angkaVersi() di bawah SALINAN PERSIS dari PemeriksaPembaruan.kt. Kalau blok
// itu berubah, salinan ini ikut berubah — memang itu tujuannya: yang diuji
// harus fungsi yang benar-benar dipakai APK, bukan terjemahan ke bahasa lain
// yang bisa menyimpang diam-diam. Skrip verifikasi membandingkan kedua blok
// byte-per-byte, jadi salinan yang basi langsung ketahuan.
//
// Jalankan: kotlinc UjiVersi.kt -include-runtime -d uji.jar && java -jar uji.jar
//
// Kontrak yang dijaga: angkaVersi memetakan tag rilis -> versiKode calon
// (N+1, karena alamat.json menaikkan versiKode bersama versiNama), dan null
// kalau tag tak dikenali. Pemanggil menolak null dan versi yang tak lebih
// besar dari BuildConfig.VERSI_KODE.

fun angkaVersi(tag: String): Int? {
    val bersih = tag.removePrefix("v").trim()
    // Hanya yg berakhiran digit tunggal "1.0.N" yang bisa dipetakan.
    // Lihat catatan di atas: versiKode = N + 1.
    val cocok = Regex("^\\d+\\.\\d+\\.(\\d+)$").find(bersih) ?: return null
    return cocok.groupValues[1].toIntOrNull()?.plus(1)
}

var gagal = 0
fun cek(nama: String, dapat: Any?, harus: Any?) {
    if (dapat == harus) println("  ok  $nama")
    else { println("  GAGAL $nama  <- dapat=$dapat harus=$harus"); gagal++ }
}

fun main() {
    println("=== angkaVersi: tag -> versiKode calon (N+1), null kalau ngawur ===")
    cek("\"1.0.3\" -> 4", angkaVersi("1.0.3"), 4)
    cek("\"v1.0.3\" awalan v diabaikan", angkaVersi("v1.0.3"), 4)
    cek("\"2.0.0\" -> 1", angkaVersi("2.0.0"), 1)
    cek("\"1.10.20\" -> 21", angkaVersi("1.10.20"), 21)
    cek("kosong -> null", angkaVersi(""), null)
    cek("\"1.0\" dua bagian -> null", angkaVersi("1.0"), null)
    cek("\"1.0.3.4\" empat bagian -> null", angkaVersi("1.0.3.4"), null)
    cek("\"1.0.3-rc1\" -> null (belum rilis stabil)", angkaVersi("1.0.3-rc1"), null)
    cek("\"abc\" -> null", angkaVersi("abc"), null)
    cek("\"1.0.x\" -> null", angkaVersi("1.0.x"), null)
    cek("\"1.0.-1\" -> null", angkaVersi("1.0.-1"), null)
    cek("\"V1.0.3\" huruf besar -> null", angkaVersi("V1.0.3"), null)
    // trim() SETELAH removePrefix("v"): spasi setelah v terbuang, spasi
    // sebelum v membuat awalan v tak terpotong -> ditolak.
    cek("\"v 1.0.3\" -> 4 (trim setelah removePrefix)", angkaVersi("v 1.0.3"), 4)
    cek("\"  v1.0.3  \" -> null (v tak terpotong)", angkaVersi("  v1.0.3  "), null)
    // Grup tangkap hanya segmen ketiga, jadi panjang segmen lain tak penting.
    cek("\"9999999999.0.9\" -> 10 (hanya segmen ke-3 ditangkap)", angkaVersi("9999999999.0.9"), 10)
    cek("\"1.0.9999999999\" -> null (luapan Int)", angkaVersi("1.0.9999999999"), null)

    println()
    println("=== keputusan pemanggil: unduh hanya kalau angkaBaru > VERSI_KODE ===")
    println("    (KOTAK = BuildConfig.VERSI_KODE; null selalu ditolak)")
    fun tawar(tag: String, terpasang: Int) = angkaVersi(tag)?.let { it > terpasang } ?: false
    // Skenario nyata: APK 1.0.x terpasang, versiKode x+1.
    cek("terpasang 1.0.2 (kode 3), rilis v1.0.3 -> unduh", tawar("v1.0.3", 3), true)
    cek("terpasang 1.0.2 (kode 3), rilis v1.0.2 -> tidak (sama)", tawar("v1.0.2", 3), false)
    cek("terpasang 1.0.2 (kode 3), rilis v1.0.1 -> tidak (tua)", tawar("v1.0.1", 3), false)
    cek("terpasang 1.0.3 (kode 4), rilis v1.0.3 -> tidak (sama)", tawar("v1.0.3", 4), false)
    cek("terpasang 1.0.3 (kode 4), rilis v1.0.4 -> unduh", tawar("v1.0.4", 4), true)
    cek("tag ngawur \"vX\" -> tidak (aman)", tawar("vX", 3), false)
    cek("tag kosong -> tidak (aman)", tawar("", 3), false)
    cek("terpasang 1.0.0 (kode 1), rilis v1.0.0 -> tidak", tawar("v1.0.0", 1), false)
    // Naik 10 versi tetap terdeteksi (bukan cuma +1).
    cek("terpasang kode 3, rilis v1.0.12 -> unduh", tawar("v1.0.12", 3), true)

    println()
    if (gagal == 0) println("SEMUA LULUS") else println("GAGAL: $gagal")
    if (gagal != 0) kotlin.system.exitProcess(gagal)
}
