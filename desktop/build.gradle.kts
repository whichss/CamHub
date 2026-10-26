import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.camhub.desktop.MainKt")
    applicationName = "CamHub Desktop"
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jmdns:jmdns:3.5.12")
    implementation("org.bytedeco:javacv:1.5.11") {
        isTransitive = false
    }
    implementation("org.bytedeco:javacpp:1.5.11")
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11") {
        isTransitive = false
    }
    runtimeOnly("org.bytedeco:javacpp:1.5.11:${desktopNativePlatform()}")
    runtimeOnly("org.bytedeco:ffmpeg:7.1-1.5.11:${desktopNativePlatform()}")
    testImplementation(libs.junit)
}

fun desktopNativePlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val architecture = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("mac") && architecture in setOf("aarch64", "arm64") -> "macosx-arm64"
        os.contains("mac") -> "macosx-x86_64"
        os.contains("win") -> "windows-x86_64"
        architecture in setOf("aarch64", "arm64") -> "linux-arm64"
        else -> "linux-x86_64"
    }
}

val macAppName = "CamHub Desktop"
val macAppDirectory = layout.buildDirectory.dir("package/macos/$macAppName.app")

tasks.register("packageMacApp") {
    group = "distribution"
    description = "Builds a self-contained macOS .app bundle."
    dependsOn(tasks.named("installDist"))

    onlyIf {
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    }

    doLast {
        val appDirectory = macAppDirectory.get().asFile
        val contentsDirectory = appDirectory.resolve("Contents")
        val executableDirectory = contentsDirectory.resolve("MacOS")
        val resourcesDirectory = contentsDirectory.resolve("Resources")
        val librariesDirectory = contentsDirectory.resolve("app/lib")
        val runtimeDirectory = contentsDirectory.resolve("runtime")
        val javaHome = file(System.getProperty("java.home"))

        require(javaHome.resolve("bin/java").isFile) {
            "Java was not found in the Gradle runtime: ${javaHome.absolutePath}"
        }

        delete(appDirectory)
        executableDirectory.mkdirs()
        resourcesDirectory.mkdirs()
        librariesDirectory.mkdirs()

        copy {
            from(layout.buildDirectory.dir("install/$macAppName/lib"))
            into(librariesDirectory)
        }
        copy {
            from("src/package/macos/Info.plist")
            into(contentsDirectory)
        }
        copy {
            from("src/package/macos/$macAppName")
            into(executableDirectory)
        }
        copy {
            from(javaHome)
            into(runtimeDirectory)
        }

        executableDirectory.resolve(macAppName).setExecutable(true)
        runtimeDirectory.resolve("bin/java").setExecutable(true)

        providers.exec {
            commandLine(
                "/usr/bin/codesign",
                "--force",
                "--deep",
                "--sign", "-",
                appDirectory.absolutePath,
            )
        }.result.get().assertNormalExitValue()

        logger.lifecycle("macOS app created: ${appDirectory.absolutePath}")
    }
}

val windowsInstallerDirectory = layout.buildDirectory.dir("package/windows")

tasks.register("packageWindowsExe") {
    group = "distribution"
    description = "Builds a self-contained Windows .exe installer."
    dependsOn(tasks.named("installDist"))

    onlyIf {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }

    doLast {
        val outputDirectory = windowsInstallerDirectory.get().asFile
        val inputDirectory = layout.buildDirectory
            .dir("install/$macAppName/lib")
            .get()
            .asFile
        val jpackage = file(System.getProperty("java.home")).resolve("bin/jpackage.exe")

        require(jpackage.isFile) {
            "jpackage.exe was not found. Build with a full Windows JDK 17 or newer."
        }

        delete(outputDirectory)
        outputDirectory.mkdirs()

        providers.exec {
            commandLine(
                jpackage.absolutePath,
                "--type", "exe",
                "--name", macAppName,
                "--app-version", "0.1.0",
                "--vendor", "CamHub",
                "--description", "Low-latency CamHub desktop director",
                "--input", inputDirectory.absolutePath,
                "--main-jar", "desktop.jar",
                "--main-class", "com.camhub.desktop.MainKt",
                "--add-modules", "java.desktop,java.logging,java.management,jdk.unsupported",
                "--win-dir-chooser",
                "--win-menu",
                "--win-shortcut",
                "--dest", outputDirectory.absolutePath,
            )
        }.result.get().assertNormalExitValue()

        logger.lifecycle("Windows installer created in: ${outputDirectory.absolutePath}")
    }
}
