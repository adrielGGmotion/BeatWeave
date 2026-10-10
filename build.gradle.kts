import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    kotlin("multiplatform") version "2.1.21" apply false
    id("com.android.library") version "8.7.3" apply false
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}

val centralRelease = providers.gradleProperty("centralRelease").orNull == "true"
val repositoryUrl = providers.gradleProperty("beatweaveRepositoryUrl")
    .map { it.trimEnd('/').removeSuffix(".git") }
if (centralRelease) {
    require(repositoryUrl.orNull?.let { it.startsWith("https://") && java.net.URI(it).host != null } == true) {
        "Set beatweaveRepositoryUrl to the public source repository URL before a Central release."
    }
    require(providers.gradleProperty("enableAppleTargets").orNull != "true") {
        "Central releases currently support Android and JVM only."
    }
}

allprojects {
    group = providers.gradleProperty("beatweaveGroup").get()
    version = providers.gradleProperty("beatweaveVersion").get()
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        jvmArgs("-XX:-UsePerfData")
    }
}

subprojects {
    val moduleName = name
    plugins.withId("maven-publish") {
        apply(plugin = "com.vanniktech.maven.publish")
        extensions.configure<MavenPublishBaseExtension> {
            if (centralRelease) {
                publishToMavenCentral(automaticRelease = false)
                signAllPublications()
            }
            pom {
                name.set("BeatWeave $moduleName")
                description.set(when (moduleName) {
                    "analysis" -> "Offline music analysis and beat-matching interfaces for Kotlin."
                    "learned-beats" -> "Local Beat This! inference and musical transition planning."
                    else -> "GPL-licensed Rubber Band pitch-preserving time stretching."
                })
                inceptionYear.set("2026")
                url.set(repositoryUrl)
                licenses {
                    license {
                        val gpl = moduleName == "rubberband"
                        name.set(if (gpl) "GPL-2.0-or-later" else "MIT")
                        url.set(if (gpl) "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html"
                            else "https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        name.set("BeatWeave contributors")
                        url.set(repositoryUrl)
                    }
                }
                scm {
                    url.set(repositoryUrl)
                    connection.set(repositoryUrl.map { "scm:git:$it.git" })
                    developerConnection.set(repositoryUrl.map { "scm:git:$it.git" })
                }
            }
        }
        tasks.withType<Zip>().configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            from(if (moduleName == "rubberband") file("COPYING") else rootProject.file("LICENSE")) {
                into("META-INF")
            }
            if (moduleName == "learned-beats") {
                from(file("licenses")) { into("META-INF/licenses") }
            }
            if (name.endsWith("JavadocJar")) {
                from(rootProject.file("README.md"))
                from(rootProject.file("docs")) { into("docs") }
            }
        }
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "Local"
                    url = uri(rootProject.layout.projectDirectory.dir("dist/maven"))
                }
            }
        }
    }
}

val checkModels by tasks.registering(Exec::class) {
    commandLine("python3", "tools/check-models.py")
}

tasks.register<Zip>("modelDistribution") {
    dependsOn(checkModels)
    archiveFileName.set("BeatWeave-models-${project.version}.zip")
    destinationDirectory.set(layout.projectDirectory.dir("dist"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from("learned-beats/models") {
        include("*.onnx", "*provenance.json", "distribution.json")
        into("learned-beats/models")
    }
    from("learned-beats/licenses") { into("learned-beats/licenses") }
}
