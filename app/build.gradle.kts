import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Keystore produksi dibaca dari app/keystore.properties yang TIDAK masuk git.
// Kalau berkasnya tidak ada (mis. mesin kontributor lain), jatuh ke keystore
// debug supaya proyek tetap bisa dibangun — lihat blok buildTypes.
//
// Import di atas wajib: di dalam blok `android { }` nama `java` sudah dipakai
// ekstensi AGP, jadi `java.util.Properties()` di situ gagal dengan
// "Unresolved reference: util".
val sifatKeystore = Properties().apply {
    val f = file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.zrooms.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zrooms.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val ks = sifatKeystore.getProperty("storeFile")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = sifatKeystore.getProperty("storePassword")
                keyAlias = sifatKeystore.getProperty("keyAlias")
                keyPassword = sifatKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Keystore produksi kalau ada; kalau tidak, keystore debug supaya
            // proyek masih bisa dibangun tanpa berkas rahasia.
            // ponytail: rilis dari mesin tanpa keystore TIDAK bisa dipasang
            // menimpa versi resmi — Android menolak tanda tangan berbeda.
            signingConfig = if (sifatKeystore.getProperty("storeFile") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Tanpa Compose: WebView cukup, dan tanpa Compose APK jauh lebih kecil.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")

    // AGP 8.x menarik kotlin-stdlib 1.8.22, tapi webkit/appcompat masih
    // membawa kotlin-stdlib-jdk7/jdk8 1.6.21 yang kelasnya kini sudah
    // menyatu ke stdlib → tabrakan kelas. Sejak Kotlin 1.8 keduanya kosong,
    // jadi ditahan ke versi stdlib yang dipakai AGP.
    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22")
    }
}
