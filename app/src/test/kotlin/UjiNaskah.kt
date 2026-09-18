// Uji penguraian naskah ESC/POS Z-Rooms.
//
// DUA fungsi di bawah SALINAN PERSIS dari PrinterBluetooth.kt (companion):
// `bacaPerintah` dan `uraikanNaskah`. Yang diuji harus fungsi yang benar-benar
// dipakai APK, bukan terjemahan ke bahasa lain yang bisa menyimpang diam-diam —
// menyalinnya persis membuat penyimpangan itu terlihat saat ditinjau.
//
// Jalankan: kotlinc UjiNaskah.kt -include-runtime -d uji.jar && java -jar uji.jar
//
// KONTRAK YANG DIJAGA
// Naskah dari web berbentuk teks dengan perintah sebagai penanda `<27,64>`.
// Yang paling mudah salah adalah BATAS antara perintah dan teks biasa: nota
// boleh memuat tanda `<` atau `>` (mis. "Harga <seratus>"), dan memperlakukannya
// sebagai perintah akan merusak isi nota. Sebaliknya, perintah yang lolos
// sebagai teks akan tampil di kertas sebagai simbol aneh alih-alih memotong.

import java.io.ByteArrayOutputStream

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

private fun uraikanNaskah(naskah: String): ByteArray {
    val keluaran = ByteArrayOutputStream()
    for (baris in naskah.split("\n")) {
        val perintah = bacaPerintah(baris)
        if (perintah != null) {
            keluaran.write(perintah)
        } else {
            keluaran.write(baris.toByteArray(Charsets.ISO_8859_1))
            keluaran.write(0x0a)
        }
    }
    return keluaran.toByteArray()
}

var gagal = 0
fun cek(nama: String, dapat: Any?, harus: Any?) {
    if (dapat == harus) println("  ok  $nama")
    else { println("  GAGAL $nama  <- dapat=$dapat harus=$harus"); gagal++ }
}

fun main() {
    println("Uji penguraian naskah ESC/POS")

    // ── Perintah jadi byte mentah, tanpa baris baru tambahan ──
    val a = uraikanNaskah("<27,64>\nHalo")
    cek("perintah+teks: panjang", a.size, 4 + 5)
    cek("perintah+teks: byte pertama ESC", a[0].toInt(), 27)
    cek("perintah+teks: byte kedua @", a[1].toInt(), 64)
    cek("perintah+teks: isi teks", String(a, 2, 4), "Halo")
    cek("perintah+teks: teks diakhiri LF", a[6].toInt(), 10)

    // ── Perintah POTONG (GS V 66 0) — inilah yang membuat kertas terpotong ──
    val p = uraikanNaskah("<29,86,66,0>")
    cek("potong: panjang 4 byte", p.size, 4)
    cek("potong: byte GS", p[0].toInt(), 29)
    cek("potong: byte V", p[1].toInt(), 86)
    cek("potong: byte 66", p[2].toInt(), 66)

    // ── Teks yang KEBETULAN memuat kurung siku tetap jadi teks ──
    // Ini yang paling mudah salah, dan salahnya merusak isi nota.
    cek("teks berkurung: bukan perintah", uraikanNaskah("Harga <seratus>").size, "Harga <seratus>".length + 1)
    cek("teks berkurung: isi utuh", String(uraikanNaskah("Harga <seratus>"), 0, 15), "Harga <seratus>")
    // Angka di luar 0-255 tak sah sebagai byte -> teks biasa.
    cek("angka 300: jadi teks", String(uraikanNaskah("<300>"), 0, 5), "<300>")
    cek("angka negatif: jadi teks", String(uraikanNaskah("<-1>"), 0, 4), "<-1>")
    cek("huruf: jadi teks", String(uraikanNaskah("<abc>"), 0, 5), "<abc>")
    cek("kurung kosong: jadi teks", String(uraikanNaskah("<>"), 0, 2), "<>")
    cek("koma tanpa angka: jadi teks", String(uraikanNaskah("<,>"), 0, 3), "<,>")

    // Spasi di dalam perintah tetap sah (toleransi untuk naskah tulisan tangan).
    cek("perintah berspasi: jadi byte", uraikanNaskah("< 27 , 64 >").size, 2)

    // ── Satu karakter = satu byte (ISO-8859-1) ──
    // UTF-8 akan mengubah karakter beraksen jadi dua byte dan menggeser seluruh
    // baris nota — kolom angka tak lagi jatuh di tempatnya.
    cek("aksen: satu byte per karakter", uraikanNaskah("café").size, 5)
    cek("panjang baris: 32 kolom = 33 byte", uraikanNaskah("X".repeat(32)).size, 33)

    // ── Kasus tepi tak boleh melempar ──
    cek("naskah kosong", uraikanNaskah("").size, 1)
    cek("hanya baris baru", uraikanNaskah("\n\n").size, 3)
    cek("banyak baris", uraikanNaskah("a\nb\nc").size, 6)

    // ── Nota nyata: perintah + teks bercampur ──
    val nota = uraikanNaskah("<27,64>\n" + "=".repeat(32) + "\nTotal ........ 50.000\n<29,86,66,0>")
    cek("nota: diawali ESC @", nota[0].toInt(), 27)
    cek("nota: diakhiri byte potong",
        listOf(nota[nota.size - 4].toInt(), nota[nota.size - 3].toInt(), nota[nota.size - 2].toInt(), nota[nota.size - 1].toInt()),
        listOf(29, 86, 66, 0))
    cek("nota: memuat angka harga", String(nota, Charsets.ISO_8859_1).contains("50.000"), true)

    println(if (gagal == 0) "\nSEMUA LULUS" else "\n$gagal GAGAL")
    if (gagal > 0) kotlin.system.exitProcess(1)
}
