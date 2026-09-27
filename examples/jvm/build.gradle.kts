import java.security.MessageDigest
plugins { kotlin("jvm") version "2.1.21"; application }
val beatweaveRoot = file(providers.gradleProperty("beatweaveRoot").orElse("../..").get())
val beatweaveVersion = providers.gradleProperty("beatweaveVersion").orElse("0.10.0").get()
val beatweaveGroup = providers.gradleProperty("beatweaveGroup").orElse("io.github.adrielggmotion.beatweave").get()
repositories {
    maven { url = uri(beatweaveRoot.resolve("dist/maven")) }
    mavenCentral()
}
dependencies {
    implementation("$beatweaveGroup:analysis:$beatweaveVersion")
    implementation("$beatweaveGroup:rubberband:$beatweaveVersion")
    implementation("$beatweaveGroup:learned-beats:$beatweaveVersion")
}
application {
    mainClass.set("ConsumeKt")
    applicationDefaultJvmArgs = listOf("-Dbeatweave.version=$beatweaveVersion")
}
kotlin { jvmToolchain(17) }
tasks.register<JavaExec>("nativeSmoke") {
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ConsumeKt")
    val cache = layout.buildDirectory.dir("native-smoke").get().asFile
    args("--native-only", cache.resolve("audio").absolutePath)
    systemProperty("java.library.path", cache.resolve("empty-library-path").absolutePath)
    systemProperty("java.io.tmpdir", cache.absolutePath)
    doFirst { cache.mkdirs() }
    doLast { check(cache.listFiles().orEmpty().none { it.name.endsWith(".so") }) }
}
tasks.register("checkPublishedJars") {
    doLast {
        val artifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        for (module in listOf("analysis-jvm", "rubberband-jvm", "learned-beats-jvm")) {
            val resolved = artifacts.single { it.moduleVersion.id.group == beatweaveGroup && it.moduleVersion.id.name == module }.file
            val expected = beatweaveRoot.resolve("dist/maven/${beatweaveGroup.replace('.', '/')}/$module/$beatweaveVersion/$module-$beatweaveVersion.jar")
            fun digest(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            check(digest(resolved) == digest(expected)) { "Stale published artifact: $module" }
            println("PASS Maven $module:$beatweaveVersion SHA256=${digest(resolved)}")
        }
        check(artifacts.any { it.moduleVersion.id.group == "com.microsoft.onnxruntime" })
        println("PASS ONNX runtime resolved transitively")
    }
}
