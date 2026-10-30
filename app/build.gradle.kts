import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// App version with one decimal place. CI passes the next number (-PappVersion=1.1);
// local builds default to 1.0.
val appVersion = (findProperty("appVersion") as String?) ?: "1.0"
require(Regex("""\d+\.\d""").matches(appVersion)) { "appVersion must look like 1.0, got $appVersion" }
val appVersionCode = appVersion.split(".").let { (major, minor) -> major.toInt() * 10 + minor.toInt() }

// Library versions in one place: used for the dependencies and shown on the Info page.
val v = mapOf(
    "material3" to "1.5.0-beta01",
    "media3" to "1.8.0",
    "mediaCompat" to "1.7.1",
    "webkit" to "1.14.0",
    "activityCompose" to "1.11.0",
    "coroutines" to "1.10.2",
)

android {
    namespace = "com.aayoutube.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aayoutube.app"
        minSdk = 26
        targetSdk = 37
        // One decimal place: 1.0, 1.1 … 1.9, 2.0. versionCode 1.4 -> 14 (always goes up).
        versionName = appVersion + ((findProperty("appVersionSuffix") as String?) ?: "")
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: appVersionCode
        v.forEach { (name, version) -> buildConfigField("String", "LIB_" + name.uppercase(), "\"$version\"") }
    }

    buildTypes {
        getByName("debug") { versionNameSuffix = "-debug" }
        getByName("release") {
            // R8 removes unused code (most of Compose and the icon set) and resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Left unsigned here: CI signs it with the private key (see .github/workflows/build.yml).
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Use the exact archived projection AAR consumed by AABrowser (v4/aauto.aar).
// Pinned to upstream commit + Git blob SHA; never silently take a newer proprietary binary.
// The SDK is downloaded into build/ (not committed to our repository), and its provenance
// must be reviewed before distributing a release APK outside personal testing.
val legacySdkFile = layout.buildDirectory.file("aabrowser-projection/aauto.aar")
val fetchLegacySdk = tasks.register("fetchAABrowserProjectionSdk") {
    group = "build setup"
    description = "Fetch the exact upstream AABrowser legacy projection SDK artifact"
    outputs.file(legacySdkFile)
    doLast {
        val target = legacySdkFile.get().asFile
        val expectedGitBlob = "678f4afb0434ceb97b23c2237ce4489bc52b4394"
        val digest = MessageDigest.getInstance("SHA-1")
        fun shaGitBlob(bytes: ByteArray): String {
            digest.reset()
            digest.update("blob ${bytes.size}".toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(bytes)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (target.isFile && shaGitBlob(target.readBytes()) == expectedGitBlob) return@doLast
        val source = URL(
            "https://raw.githubusercontent.com/fcaronte/AABrowser/" +
                "dca3f543c83445ee4b38761eed824eb92208109f/browser/libs/v4/aauto.aar"
        )
        val conn = source.openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        val bytes = conn.getInputStream().use { it.readBytes() }
        check(bytes.size == 313859 && shaGitBlob(bytes) == expectedGitBlob) {
            "Upstream AABrowser legacy SDK does not match the pinned artifact"
        }
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
    }
}
val aabrowserProjectionSdk = files(legacySdkFile).builtBy(fetchLegacySdk)

dependencies {
    // AABrowser's exact legacy Android Auto projection integration (CarActivityService).
    implementation(aabrowserProjectionSdk)
    // Material 3 Expressive (MaterialExpressiveTheme, expressive shapes/motion/components)
    implementation("androidx.compose.material3:material3:${v["material3"]}")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("androidx.activity:activity-compose:${v["activityCompose"]}")

    // Media session for the notification, lock screen, Bluetooth and Android Auto
    implementation("androidx.media3:media3-session:${v["media3"]}")
    // AABrowser-compatible MediaBrowserServiceCompat migration dependency.
    implementation("androidx.media:media:${v["mediaCompat"]}")

    // WebView extras: script injection at document start, header control
    implementation("androidx.webkit:webkit:${v["webkit"]}")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${v["coroutines"]}")
    testImplementation("junit:junit:4.13.2")
}

 // Opt-in compile-only shared library evaluation; no production runtime cutover.
dependencies {
    implementation("dev.n2k02:theme:0.1.4")
        implementation("dev.n2k02:updater:0.1.19")
}

 
// Shared updater compatibility contract: every Android build must use a pinned
// dev.n2k02:updater version >= 0.1.19. This task is also available standalone.
val verifySharedUpdaterVersion = tasks.register("verifySharedUpdaterVersion") {
    group = "verification"
    description = "Reject missing, dynamic, or outdated shared Android updater dependencies"
    doLast {
        val updaterDependencies = configurations
            .filter { it.name == "implementation" || it.name == "api" || it.name.endsWith("Implementation") }
            .flatMap { it.dependencies }
            .filter { it.group == "dev.n2k02" && it.name == "updater" }
        check(updaterDependencies.isNotEmpty()) {
            "Missing dev.n2k02:updater dependency. Require a pinned version >= 0.1.19."
        }
        updaterDependencies.forEach { dependency ->
            val version = dependency.version ?: error("dev.n2k02:updater must have a pinned version >= 0.1.19")
            val segments = version.split('.').map { it.toIntOrNull() }
            check(segments.size >= 3 && segments.all { it != null }) {
                "dev.n2k02:updater:$version is not a pinned numeric release version."
            }
            val different = segments.filterNotNull().zip(listOf(0, 1, 19))
                .firstOrNull { (actual, minimum) -> actual != minimum }
            check(different == null || different.first > different.second) {
                "dev.n2k02:updater:$version is outdated; require >= 0.1.19."
            }
        }
    }
}
// preBuild precedes both release and debug APK builds; check covers verification.
tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
    dependsOn(verifySharedUpdaterVersion)
}
