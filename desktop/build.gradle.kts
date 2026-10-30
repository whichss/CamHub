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

tasks.test {
    // Unit tests render images offscreen and must not initialize a native desktop session.
    systemProperty("java.awt.headless", "true")
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
val macRuntimeStagingDirectory = layout.buildDirectory.dir("package/macos/runtime-staging")

tasks.register("packageMacApp") {
    group = "distribution"
    description = "Builds a self-contained macOS .app bundle."
    dependsOn(tasks.named("installDist"))

    doFirst {
        require(System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
            "macOS app packaging requires a macOS host."
        }
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

        // Gradle may itself be running from the previous app bundle's embedded runtime. Stage the
        // runtime outside the bundle before replacing it, otherwise deleting appDirectory also
        // deletes the Java installation that this task is about to copy.
        val stagedRuntime = macRuntimeStagingDirectory.get().asFile
        delete(stagedRuntime)
        copy {
            from(javaHome)
            into(stagedRuntime)
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
            from(stagedRuntime)
            into(runtimeDirectory)
        }
        delete(stagedRuntime)

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
val detectDesktopRuntimeModules: (File) -> String = { inputDirectory ->
    val jdepsName = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        "jdeps.exe"
    } else {
        "jdeps"
    }
    val jdeps = file(System.getProperty("java.home")).resolve("bin/$jdepsName")
    require(jdeps.isFile) { "$jdepsName was not found. Build with a full JDK 17 or newer." }
    val execution = providers.exec {
        commandLine(
            jdeps.absolutePath,
            "--recursive",
            "--print-module-deps",
            "--multi-release", "17",
            "--ignore-missing-deps",
            "--class-path", "${inputDirectory.absolutePath}/*",
            inputDirectory.resolve("desktop.jar").absolutePath,
        )
    }
    execution.result.get().assertNormalExitValue()
    execution.standardOutput.asText.get().trim().also { modules ->
        require(modules.matches(Regex("[A-Za-z0-9_.]+(,[A-Za-z0-9_.]+)*"))) {
            "jdeps returned an invalid runtime module list: $modules"
        }
    }
}

tasks.register("verifyDesktopRuntimeModules") {
    group = "verification"
    description = "Checks that the packaged desktop runtime includes every module used by the app."
    dependsOn(tasks.named("installDist"))
    doLast {
        val inputDirectory = layout.buildDirectory
            .dir("install/$macAppName/lib")
            .get()
            .asFile
        val modules = detectDesktopRuntimeModules(inputDirectory).split(',').toSet()
        val requiredModules = setOf("java.prefs", "jdk.httpserver", "jdk.unsupported")
        val missing = requiredModules - modules
        check(missing.isEmpty()) {
            "Runtime module analysis missed expected modules: ${missing.joinToString()}; found ${modules.joinToString()}"
        }
        logger.lifecycle("Desktop runtime modules: ${modules.sorted().joinToString()}")
    }
}

tasks.register("packageWindowsExe") {
    group = "distribution"
    description = "Builds a self-contained Windows .exe installer."
    dependsOn(tasks.named("installDist"))

    doFirst {
        require(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "Windows installer packaging requires a Windows host with WiX."
        }
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
        val requiredModules = detectDesktopRuntimeModules(inputDirectory)

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
                "--add-modules", requiredModules,
                "--win-dir-chooser",
                "--win-menu",
                "--win-shortcut",
                "--dest", outputDirectory.absolutePath,
            )
        }.result.get().assertNormalExitValue()

        logger.lifecycle("Windows installer created in: ${outputDirectory.absolutePath}")
    }
}
