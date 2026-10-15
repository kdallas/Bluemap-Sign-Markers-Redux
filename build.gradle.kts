plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.2.2"
}

group = "net.immortalmc"
version = "1.0.1"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.bluecolored.de/releases")
}

dependencies {
    implementation("net.kyori:adventure-text-minimessage:4.26.1")
    implementation("net.kyori:adventure-text-serializer-legacy:4.26.1")
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("de.bluecolored:bluemap-api:2.8.1")
}

tasks.compileJava {
    options.encoding = Charsets.UTF_8.name()
}

// Resolved at configuration time; `project` must not be reached from the
// per-file callback below, which runs at execution time.
val pluginVersion = version.toString()
val pluginName = name

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching(listOf("plugin.yml", "**/*.yml", "**/*.yaml", "**/*.properties", "**/*.txt", "**/*.md")) {
        expand(
            mapOf(
                "version" to pluginVersion,
                "name" to pluginName
            )
        )
    }
    inputs.property("version", pluginVersion)
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}