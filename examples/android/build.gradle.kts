plugins {
 id("com.android.application") version "8.7.3"
 kotlin("android") version "2.1.21"
}
val modelFile = file(providers.gradleProperty("beatweaveModel").get())
require(modelFile.name.matches(Regex("beat-this-(small0|final0)\\.onnx")))
android {
 namespace = "org.metrolist.beatweave.consumer"
 compileSdk = 35
 defaultConfig {
  applicationId = "org.metrolist.beatweave.consumer"
  minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1"
  buildConfigField("String", "MODEL_ASSET", "\"${modelFile.name}\"")
  ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
  testInstrumentationRunner = "org.metrolist.beatweave.consumer.SmokeInstrumentation"
 }
 buildFeatures { buildConfig = true }
 buildTypes {
  release {
   isMinifyEnabled = true
   signingConfig = signingConfigs.getByName("debug")
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
  }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
val copyModel by tasks.registering(Copy::class) {
    from(modelFile)
    into(layout.buildDirectory.dir("generated/model-assets"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/model-assets"))
tasks.named("preBuild").configure { dependsOn(copyModel) }
val beatweaveVersion = providers.gradleProperty("beatweaveVersion").get()
val beatweaveGroup = providers.gradleProperty("beatweaveGroup").orElse("io.github.adrielggmotion.beatweave").get()
dependencies {
 implementation("$beatweaveGroup:analysis:$beatweaveVersion")
 implementation("$beatweaveGroup:rubberband:$beatweaveVersion")
 implementation("$beatweaveGroup:learned-beats:$beatweaveVersion")
}
tasks.register("recordResolvedArtifacts") {
 doLast {
  val artifacts = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
  layout.buildDirectory.file("resolved-artifacts.tsv").get().asFile.writeText(artifacts.joinToString("\n") {
   "${it.moduleVersion.id}\t${it.file.absolutePath}"
  } + "\n")
 }
}
