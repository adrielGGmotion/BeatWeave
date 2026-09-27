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
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "org.metrolist.beatweave.analysis"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
}
