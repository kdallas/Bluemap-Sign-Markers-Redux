plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.2.2"
}

group = "net.immortalmc"
version = "1.0.1"

// ---------------------------------------------------------------------------
// Minecraft targets
//
//   ./gradlew build                 -> default target "1.21" (the live server)
//   ./gradlew build -PmcTarget=26   -> target "26"
//
// The Java toolchain stays at 25 for both: JDK 25 compiles either one, and
// options.release below selects the class-file version / JDK API surface
// (21 -> major 65, 25 -> major 69). No JDK 21 install is required.
//
// Everything that differs between targets lives in the table below. Adding a
// third target means adding one entry here and nothing else.
// ---------------------------------------------------------------------------

/** One Minecraft/Paper build target: the complete set of per-target values. */
data class McTarget(
    val id: String,
    /** Jar-name suffix: BluemapSignMarkers-<version>-<jarSuffix>.jar */
    val jarSuffix: String,
    val paperApi: String,
    val bluemapApi: String,
    /** Value substituted into plugin.yml `api-version`. */
    val pluginApiVersion: String,
    /** javac --release level. */
    val javaRelease: Int,
)

// Insertion-ordered so the "valid values" list in the error below is stable.
// jarSuffix is explicit rather than derived from id: "mc$id" would give "mc26"
// where "mc26.3" is wanted.
val mcTargets: Map<String, McTarget> = linkedMapOf(
    "1.21" to McTarget(
        id = "1.21",
        jarSuffix = "mc1.21",
        paperApi = "1.21.11-R0.1-SNAPSHOT",
        bluemapApi = "2.7.7",
        pluginApiVersion = "1.21",
        javaRelease = 21,
    ),
    "26" to McTarget(
        id = "26",
        jarSuffix = "mc26.3",
        paperApi = "26.3.build.+",
        bluemapApi = "2.8.1",
        pluginApiVersion = "26.3",
        javaRelease = 25,
    ),
)

val defaultMcTargetId: String = "1.21"

// Read through the provider API at configuration time, so the property becomes
// a tracked configuration-cache input: switching it invalidates the cached
// configuration rather than replaying the other target's coordinates.
val mcTargetId: String = providers.gradleProperty("mcTarget").getOrElse(defaultMcTargetId)

val mcTarget: McTarget = mcTargets[mcTargetId]
    ?: throw GradleException(
        "Unknown -PmcTarget='$mcTargetId'. Valid values: ${mcTargets.keys.joinToString(", ")}; " +
            "omit the property to build the default ('$defaultMcTargetId'). " +
            "Example: ./gradlew build -PmcTarget=26"
    )

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
    compileOnly("io.papermc.paper:paper-api:${mcTarget.paperApi}")
    compileOnly("de.bluecolored:bluemap-api:${mcTarget.bluemapApi}")
}

// withType(...).configureEach rather than tasks.compileJava, so that a future
// compileTestJava cannot silently keep targeting the toolchain's own level.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()

    // Gradle emits `--release N` and omits -source/-target entirely, so the
    // toolchain-derived compatibility defaults are bypassed, not conflicting.
    // Pinning release also pins the JDK API surface, so a call to a Java 22+
    // method fails here rather than on the server.
    options.release.set(mcTarget.javaRelease)
}

// Resolved at configuration time; `project` must not be reached from the
// per-file callback below, which runs at execution time.
val pluginVersion = version.toString()
val pluginName = name

// Tokens substituted into plugin.yml (and any other filtered resource).
val expandTokens: Map<String, String> = linkedMapOf(
    "version" to pluginVersion,
    "name" to pluginName,
    "apiVersion" to mcTarget.pluginApiVersion,
)

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching(listOf("plugin.yml", "**/*.yml", "**/*.yaml", "**/*.properties", "**/*.txt", "**/*.md")) {
        expand(expandTokens)
    }
    // plugin.yml is byte-identical between targets, so without this the task
    // would stay UP-TO-DATE on a target switch and ship the previous
    // api-version inside the new jar. Declaring the whole map keeps this in
    // step automatically if a token is added later.
    inputs.property("expandTokens", expandTokens)
}

tasks.shadowJar {
    // The target suffix becomes part of the file name:
    // BluemapSignMarkers-1.0.1-mc1.21.jar, BluemapSignMarkers-1.0.1-mc26.3.jar
    archiveClassifier.set(mcTarget.jarSuffix)
    mergeServiceFiles()

    // Plain locals so the action closes over no script or project state.
    val targetId = mcTarget.id
    val paperApi = mcTarget.paperApi
    val bluemapApi = mcTarget.bluemapApi
    val javaRelease = mcTarget.javaRelease
    val pluginApiVersion = mcTarget.pluginApiVersion
    doFirst {
        logger.lifecycle(
            "Building Minecraft target $targetId: paper-api $paperApi, bluemap-api $bluemapApi, " +
                "Java release $javaRelease, plugin.yml api-version '$pluginApiVersion'"
        )
    }
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
