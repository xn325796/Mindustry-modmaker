plugins {
    application
}

group = "dev.modmaker"
version = "0.1.0"

repositories {
    mavenCentral()
}

val javafxVersion = "25.0.5"
// JavaFX publishes one artifact per platform. Derive it from the machine running the build so a
// checkout builds on Windows, macOS and Linux alike; -PjavafxPlatform=<classifier> overrides it.
val javafxPlatform: String = providers.gradleProperty("javafxPlatform").orNull ?: run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        os.contains("win") -> "win"
        os.contains("mac") -> if (arch.contains("aarch64")) "mac-aarch64" else "mac"
        else -> if (arch.contains("aarch64")) "linux-aarch64" else "linux"
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.platform:junit-platform-launcher:1.10.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    // Launcher does not extend Application, which keeps JavaFX usable straight from the classpath.
    mainClass.set("dev.modmaker.Launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

// Ship the schema data inside the jar so a packaged build does not depend on the working directory.
tasks.processResources {
    from("schemas") {
        into("schemas")
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
    // The security-hardening tests build a 257 MiB zip and 20 000-entry/directory/file floods, and
    // reading one oversized entry materialises a 256 MiB byte[]. With the default 512 MiB worker
    // heap Gradle kills the worker partway through core.io, so the json/noise/schema/validate/ui
    // classes never run and the build reports a bogus "Test process encountered an unexpected
    // problem". 2 GiB keeps the whole suite in one worker (491 tests, 0 failures, 28 skipped).
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

// Scans the Mindustry sources for content-class fields and vanilla content names,
// writing schemas/fields.json + schemas/vanilla-content.json.
tasks.register<JavaExec>("schemaBootstrap") {
    group = "modmaker"
    description = "Regenerate schemas/fields.json and schemas/vanilla-content.json from Mindustry sources"
    mainClass.set("dev.modmaker.tools.SchemaBootstrap")
    classpath = sourceSets["main"].runtimeClasspath
    // The tool finds the source tree itself (argument, -Dmodmaker.mindustrySrc/MINDUSTRY_SRC, the
    // usual workspace layout, then a shallow scan); -PmindustrySrc only overrides that search.
    project.findProperty("mindustrySrc")?.let { source -> args = listOf(source.toString()) }
}
