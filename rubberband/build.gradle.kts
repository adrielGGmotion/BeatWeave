plugins {
    kotlin("multiplatform")
    id("com.android.library")
    `maven-publish`
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(project(":analysis"))
        }
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}
android {
    namespace = "org.metrolist.beatweave.rubberband"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DBEATWEAVE_JNI=ON")
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild {
        cmake {
            path = file("native/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// Ordinary JVM tests exercise the native engine, including on a clean checkout.
// A prebuilt override is useful to test exactly the binary intended for shipping.
val nativeLibraryOverride = providers.gradleProperty("beatweaveNativeLibraryPath")
val hostNativeDir = layout.buildDirectory.dir("hostNative")
val hostNativeLibrary = hostNativeDir.map { it.file(System.mapLibraryName("beatweave_rubberband")) }
val jvmNativeLibrary = nativeLibraryOverride.map {
    file(it).resolve(System.mapLibraryName("beatweave_rubberband"))
}.orElse(hostNativeLibrary.map { it.asFile })
val cmake = providers.gradleProperty("beatweaveCmake").getOrElse("cmake")
val configureHostNative by tasks.registering(Exec::class) {
    commandLine(cmake, "-S", file("native").absolutePath,
        "-B", hostNativeDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release", "-DBEATWEAVE_JNI=ON")
    environment("JAVA_HOME", System.getProperty("java.home"))
    inputs.file("native/CMakeLists.txt")
    inputs.property("cmake", cmake)
    inputs.property("javaHome", System.getProperty("java.home"))
    outputs.file(hostNativeDir.map { it.file("CMakeCache.txt") })
}
val buildHostNative by tasks.registering(Exec::class) {
    dependsOn(configureHostNative)
    commandLine(cmake, "--build", hostNativeDir.get().asFile.absolutePath, "--config", "Release", "--parallel", "2")
    inputs.files(fileTree("native") { include("CMakeLists.txt", "**/*.cpp", "**/*.h", "**/*.c") })
    inputs.property("cmake", cmake)
    outputs.file(hostNativeLibrary)
}
val verifyBundledJvmNativeHost by tasks.registering {
    doLast {
        check(System.getProperty("os.name") == "Linux" &&
            System.getProperty("os.arch") in setOf("amd64", "x86_64")) {
            "Bundled JVM native library requires a Linux x86_64 host"
        }
    }
}
buildHostNative.configure {
    mustRunAfter(verifyBundledJvmNativeHost)
}
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    if (name.endsWith("SourcesJar", ignoreCase = true)) {
        from("native") { into("native") }
        from("THIRD_PARTY.md") { into("META-INF") }
    }
}
tasks.named<org.gradle.jvm.tasks.Jar>("jvmJar") {
    dependsOn(verifyBundledJvmNativeHost)
    if (nativeLibraryOverride.isPresent) {
        doFirst {
            val library = jvmNativeLibrary.get()
            check(library.isFile) { "Prebuilt native library not found: ${library.absolutePath}" }
        }
    } else {
        dependsOn(buildHostNative)
    }
    from(jvmNativeLibrary) { into("META-INF/native/linux-x86_64") }
}
tasks.withType<Test>().configureEach {
    if (!nativeLibraryOverride.isPresent) dependsOn(buildHostNative)
    systemProperty("java.library.path", nativeLibraryOverride.getOrElse(hostNativeDir.get().asFile.absolutePath))
}
