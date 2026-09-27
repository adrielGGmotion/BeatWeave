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

    if (providers.gradleProperty("enableAppleTargets").orNull == "true") {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":analysis"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val ortMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain {
            dependsOn(ortMain)
            dependencies {
                implementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
            }
        }
        androidMain {
            dependsOn(ortMain)
            dependencies {
                implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
            }
        }
    }
}

android {
    namespace = "org.metrolist.beatweave.learned"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        // ORT's JNI resolves these classes and members by their original names.
        consumerProguardFiles("consumer-rules.pro")
    }
}

val modelTestClass = "org.metrolist.beatweave.learned.OrtBeatThisBackendTest"
val jvmTests = tasks.named<Test>("jvmTest") {
    filter.excludeTestsMatching(modelTestClass)
}

tasks.register<Test>("modelIntegrationTest") {
    group = "verification"
    description = "Runs ONNX inference tests; requires both models in learned-beats/models."
    dependsOn("jvmTestClasses")
    testClassesDirs = jvmTests.get().testClassesDirs
    classpath = jvmTests.get().classpath
    filter.includeTestsMatching(modelTestClass)
    workingDir = projectDir
    inputs.files("models/beat-this-small0.onnx", "models/beat-this-final0.onnx")
    doFirst {
        for (name in listOf("beat-this-small0.onnx", "beat-this-final0.onnx")) {
            require(file("models/$name").isFile) {
                "Missing models/$name. See docs/models.md for model setup."
            }
        }
    }
}
