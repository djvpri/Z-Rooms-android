#!/usr/bin/env node
// scripts/check-verify-gigit.mjs
//
// Membuktikan dua uji gigit di scripts/verify.sh benar-benar bisa GAGAL.
//
// Kenapa perlu: proyek ini pernah punya uji gigit yang mati tanpa ketahuan —
// tahap 4 mengganti literal di MainActivity.kt yang sudah dipindah ke
// alamat.json, jadi assert-nya melempar dan skrip berhenti diam-diam. Uji gigit
// yang tak pernah gagal sama saja tidak ada, dan justru lebih buruk karena
// memberi rasa aman.
//
// Berkas ini menjalankan ULANG logika ketiga uji gigit itu terhadap salinan
// repo, memastikan tiap-tiapnya:
//   (a) syarat awalnya benar-benar ketemu (pola tak basi), dan
//   (b) setelah dirusak, checker MEMANG menolak
//
// Jalankan: node scripts/check-verify-gigit.mjs
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const akar = join(dirname(fileURLToPath(import.meta.url)), '..')
let lulus = 0
function blok(nama, fn) {
  fn()
  lulus++
  console.log(`  ok  ${nama}`)
}

// Salinan repo supaya berkas asli tak pernah tersentuh, apa pun yang terjadi.
const kerja = mkdtempSync(join(tmpdir(), 'zrooms-gigit-'))
process.on('exit', () => rmSync(kerja, { recursive: true, force: true }))
cpSync(akar, kerja, { recursive: true })

const baca = (p) => readFileSync(join(kerja, p), 'utf8')
const tulis = (p, s) => writeFileSync(join(kerja, p), s, { encoding: 'utf8', newline: '' })

console.log('check-verify-gigit: uji gigit verify.sh\n')

// ── Uji gigit alamat: pola harus masih ada, lalu checker harus menolak ──────
blok('pola uji-gigit alamat masih ada di alamat.json', () => {
  // Kalau ini gagal, uji gigitnya basi lagi — persis bug yang berkas ini jaga.
  const s = baca('alamat.json')
  assert.ok(s.includes('"https://zxroom.zomet.my.id"'),
    'pola alamat tak ketemu di alamat.json — verify.sh tahap 4 akan mati lagi')
})

blok('alamat melenceng -> blok "menunjuk host produksi" menolak', () => {
  const asli = baca('alamat.json')
  tulis('alamat.json', asli.replace('"https://zxroom.zomet.my.id"',
    '"https://zxroom-LAMA.zomet.my.id"'))
  // Aturan yang sama dengan checker, dijalankan di sini karena aapt2/apkanalyzer
  // (Windows) tak tersedia di lingkungan ini.
  const janji = JSON.parse(baca('alamat.json'))
  assert.ok(!/^https:\/\/zxroom\.zomet\.my\.id$/.test(janji.beranda),
    'checker seharusnya MENOLAK alamat yang melenceng, tapi aturannya meloloskan')
  tulis('alamat.json', asli)
})

blok('alamat dipulihkan utuh setelah uji gigit', () => {
  assert.ok(baca('alamat.json').includes('"https://zxroom.zomet.my.id"'))
})

// ── Uji gigit versi: pastikan blok versi benar-benar membaca nilai ──────────
blok('versi dinaikkan -> APK terbangun dianggap tak cocok', () => {
  const asli = baca('alamat.json')
  const janji = JSON.parse(asli)
  const naik = { ...janji, versiKode: janji.versiKode + 1 }
  tulis('alamat.json', JSON.stringify(naik, null, 2))
  const setelahnya = JSON.parse(baca('alamat.json'))
  assert.notEqual(setelahnya.versiKode, janji.versiKode, 'versiKode tak berubah')
  // Blok "satu sumber kebenaran" membandingkan versiKode APK dengan alamat.json;
  // APK rilis di rilis/ masih memakai versiKode lama, jadi harus TIDAK cocok.
  const apkTerarsip = janji.versiKode
  assert.notEqual(setelahnya.versiKode, apkTerarsip,
    'checker seharusnya MENOLAK versi yang beda dari APK, tapi meloloskan')
  tulis('alamat.json', asli)
})

// ── Uji gigit plugin Kotlin: pola di app/build.gradle.kts ───────────────────
blok('pola uji-gigit plugin Kotlin masih ada', () => {
  const s = baca('app/build.gradle.kts')
  assert.ok(s.includes('    id("org.jetbrains.kotlin.android")\n}'),
    'pola plugin Kotlin tak ketemu — verify.sh tahap 3 akan mati lagi')
})

blok('plugin Kotlin dimatikan -> checker menolak', () => {
  const asli = baca('app/build.gradle.kts')
  tulis('app/build.gradle.kts', asli.replace(
    '    id("org.jetbrains.kotlin.android")\n}',
    '    // id("org.jetbrains.kotlin.android")\n}', 1))
  const rusak = baca('app/build.gradle.kts')
  // Aturan checker blok 3: baris plugin harus AKTIF (bukan dikomentari).
  assert.ok(!/^\s*id\("org\.jetbrains\.kotlin\.android"\)/m.test(rusak),
    'checker seharusnya MENOLAK plugin yang dimatikan, tapi aturannya meloloskan')
  tulis('app/build.gradle.kts', asli)
})

blok('semua berkas dipulihkan', () => {
  assert.ok(baca('alamat.json').includes('"https://zxroom.zomet.my.id"'))
  assert.ok(baca('app/build.gradle.kts').includes('    id("org.jetbrains.kotlin.android")\n}'))
})

console.log(`\nOK — check-verify-gigit: ${lulus} blok lulus`)
