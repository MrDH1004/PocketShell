import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

// PocketShell desktop client (macOS first; also runs on Windows/Linux for testing). The PC server stays Windows-only.
// Shares the Android app's pure Kotlin code (protocol, pairing, pinning, REST/WebSocket clients) from ../android.
plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.10.3"
}

// Single version source (DESIGN.md §11.1): repo-root version.properties.
val versionProps = Properties().apply { rootProject.file("../version.properties").inputStream().use { load(it) } }
val pkshellVersion: String = versionProps.getProperty("versionName") ?: error("version.properties: versionName missing")
version = pkshellVersion

kotlin {
    jvmToolchain(17)
}

val androidSrc = "../android/app/src/main/java"
val shared = listOf(
    "com/neststudio/pocketshell/core/**",
    "com/neststudio/pocketshell/data/Pairing.kt",
    "com/neststudio/pocketshell/net/ApiClient.kt",
    "com/neststudio/pocketshell/net/HostSelector.kt",
    "com/neststudio/pocketshell/net/Poster.kt",
    "com/neststudio/pocketshell/net/TerminalConnection.kt",
    "com/neststudio/pocketshell/net/TofuProbe.kt",
    "com/neststudio/pocketshell/net/WebTunnel.kt",
)

sourceSets {
    main {
        kotlin.srcDir(androidSrc)
        kotlin.include(shared + "com/neststudio/pocketshell/desktop/**")
        kotlin.exclude("com/neststudio/pocketshell/core/Reflow.kt", "com/neststudio/pocketshell/core/ConsoleHistory.kt") // Termux emulator types
        resources.srcDir(layout.buildDirectory.dir("generated/res"))
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303") // Android ships org.json; the shared code uses it
    implementation("org.jetbrains.jediterm:jediterm-core:3.76")
    implementation("org.jetbrains.jediterm:jediterm-ui:3.76")
    testImplementation(kotlin("test"))
}

// version.txt + the D2Coding font (from the Android app) as resources.
val generateRes by tasks.registering {
    val out = layout.buildDirectory.dir("generated/res")
    inputs.property("version", pkshellVersion)
    inputs.file("../android/app/src/main/res/font/d2coding.ttf")
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.mkdirs()
        File(dir, "version.txt").writeText(pkshellVersion)
        File("${projectDir}/../android/app/src/main/res/font/d2coding.ttf").copyTo(File(dir, "d2coding.ttf"), overwrite = true)
    }
}
tasks.named("processResources") { dependsOn(generateRes) }

tasks.test { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "com.neststudio.pocketshell.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "PocketShell"
            // jpackage on macOS needs MAJOR > 0: 0.2.146 -> 1.2.146 for the bundle only (the app shows version.txt).
            packageVersion = "1." + pkshellVersion.substringAfter('.')
            description = "PocketShell client: AI CLI sessions on your PC"
            vendor = "PocketShell"
            modules("java.instrument", "jdk.unsupported", "java.naming", "jdk.crypto.ec")
            macOS {
                bundleID = "com.neststudio.pocketshell.desktop"
                iconFile.set(project.file("icons/pocketshell.icns"))
                dockName = "PocketShell"
            }
            windows {
                iconFile.set(project.file("../assets/pocketshell.ico"))
                menuGroup = "PocketShell"
            }
        }
    }
}
