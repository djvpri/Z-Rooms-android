
// Uji logika versi pembaruan mandiri Z-Rooms.
//
// angkaVersi() di bawah adalah SALINAN PERSIS dari PemeriksaPembaruan.kt.
// Kalau blok itu berubah, uji ini ikut berubah — memang itu tujuannya: yang
// diuji harus fungsi yang benar-benar dipakai APK, bukan terjemahan ke bahasa
// lain yang bisa menyimpang diam-diam.
//
// Jalankan (tanpa Gradle, cukup kotlinc + java):
//   kotlinc UjiVersi.kt -include-runtime -d uji.jar && java -jar uji.jar
fun angkaVersi(tag: String): Int {
    val bersih = tag.removePrefix("v").trim()
    val cocok = Regex("^\\d+\\.\\d+\\.(\\d+)$").find(bersih) ?: return -1
    return cocok.groupValues[1].toIntOrNull() ?: -1
}

var gagal = 0
fun cek(nama: String, dapat: Any, harus: Any) {
    if (dapat == harus) println("  ok  $nama")
    else { println("  GAGAL $nama  <- dapat=$dapat harus=$harus"); gagal++ }
}

fun main() {
    println("=== angkaVersi (fungsi Kotlin asli, dikompilasi) ===")
    cek("\"1.0.3\" -> 3", angkaVersi("1.0.3"), 3)
    cek("\"v1.0.3\" awalan v diabaikan", angkaVersi("v1.0.3"), 3)
    cek("\"V1.0.3\" huruf besar", angkaVersi("V1.0.3") , -1)
    cek("\"2.0.0\" -> 0", angkaVersi("2.0.0"), 0)
    cek("\"1.10.20\" -> 20", angkaVersi("1.10.20"), 20)
    cek("kosong -> -1", angkaVersi(""), -1)
    cek("\"1.0\" -> -1", angkaVersi("1.0"), -1)
    cek("\"1.0.3.4\" -> -1", angkaVersi("1.0.3.4"), -1)
    cek("\"1.0.3-rc1\" -> -1", angkaVersi("1.0.3-rc1"), -1)
    cek("\"abc\" -> -1", angkaVersi("abc"), -1)
    cek("\"1.0.x\" -> -1", angkaVersi("1.0.x"), -1)
    cek("\"1.0.-1\" -> -1", angkaVersi("1.0.-1"), -1)
    // trim() dijalankan SETELAH removePrefix("v"), jadi spasi setelah v tetap
    // terbuang; spasi sebelum v membuat awalan v tak terpotong -> ditolak.
    cek("\"v 1.0.3\" -> 3 (trim setelah removePrefix)", angkaVersi("v 1.0.3"), 3)
    cek("\"  v1.0.3  \" -> -1 (v tak terpotong)", angkaVersi("  v1.0.3  "), -1)
    // grup tangkap hanya segmen ketiga, jadi panjang segmen lain tak berpengaruh.
    cek("\"9999999999.0.9\" -> 9 (hanya segmen ke-3 ditangkap)", angkaVersi("9999999999.0.9"), 9)
    cek("\"1.0.9999999999\" -> -1 (segmen ke-3 luapan Int)", angkaVersi("1.0.9999999999"), -1)

    println()
    println("=== keputusan: tawarkan pembaruan? (t > 0 && t > terpasang) ===")
    fun tawar(tag: String, terpasang: Int) = angkaVersi(tag).let { it > 0 && it > terpasang }
    cek("v1.0.4 vs 3 -> true", tawar("v1.0.4", 3), true)
    cek("v1.0.3 vs 3 -> false (sama)", tawar("v1.0.3", 3), false)
    cek("v1.0.2 vs 3 -> false (tua)", tawar("v1.0.2", 3), false)
    cek("vX     vs 3 -> false (ngawur, aman)", tawar("vX", 3), false)
    cek("v1.0.0 vs 0 -> false (tak tawarkan kode 0)", tawar("v1.0.0", 0), false)
    cek("v1.0.1 vs 1 -> false (sama)", tawar("v1.0.1", 1), false)

    println()
    if (gagal == 0) println("SEMUA LULUS") else println("GAGAL: $gagal")
    if (gagal != 0) kotlin.system.exitProcess(gagal)
}
