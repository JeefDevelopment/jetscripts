import groovy.json.JsonSlurper
import java.io.File
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.scripting") version "2.3.20"
    id("fabric-loom") version "1.17-SNAPSHOT"
}

val allLocalModJars = fileTree("libs") {
    include("*.jar")
}

data class LocalModJar(
    val file: File,
    val id: String?,
    val version: String?,
)

fun compareVersionParts(left: String, right: String): Int {
    val tokenPattern = Regex("[0-9]+|[A-Za-z]+")
    val leftParts = tokenPattern.findAll(left).map { it.value }.toList()
    val rightParts = tokenPattern.findAll(right).map { it.value }.toList()

    for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
        val leftPart = leftParts.getOrNull(index) ?: return -1
        val rightPart = rightParts.getOrNull(index) ?: return 1
        val leftNumber = leftPart.toBigIntegerOrNull()
        val rightNumber = rightPart.toBigIntegerOrNull()

        val comparison = when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> 1
            rightNumber != null -> -1
            else -> leftPart.compareTo(rightPart, ignoreCase = true)
        }
        if (comparison != 0) return comparison
    }

    return left.compareTo(right, ignoreCase = true)
}

fun readFabricMod(jar: File): LocalModJar {
    return try {
        ZipFile(jar).use { zip ->
            val metadataEntry = zip.getEntry("fabric.mod.json")
                ?: return LocalModJar(jar, null, null)
            val metadata = zip.getInputStream(metadataEntry).bufferedReader().use {
                @Suppress("UNCHECKED_CAST")
                JsonSlurper().parse(it) as Map<String, Any?>
            }
            LocalModJar(jar, metadata["id"]?.toString(), metadata["version"]?.toString())
        }
    } catch (_: Exception) {
        // Non-Fabric or malformed jars remain available; they simply cannot be
        // deduplicated by Fabric mod identity.
        LocalModJar(jar, null, null)
    }
}

val discoveredLocalMods = allLocalModJars.files
    .sortedBy { it.name }
    .map(::readFabricMod)

val selectedLocalMods = buildList {
    addAll(discoveredLocalMods.filter { it.id.isNullOrBlank() })
    discoveredLocalMods
        .filter { !it.id.isNullOrBlank() }
        .groupBy { it.id }
        .values
        .forEach { versions ->
            add(
                versions.maxWithOrNull { left, right ->
                    compareVersionParts(left.version.orEmpty(), right.version.orEmpty())
                        .takeIf { it != 0 }
                        ?: left.file.name.compareTo(right.file.name, ignoreCase = true)
                }!!,
            )
        }
}

val selectedLocalModFiles = selectedLocalMods.map { it.file }.toSet()
val supersededLocalMods = discoveredLocalMods.filter { it.file !in selectedLocalModFiles }
val localModJars = files(selectedLocalModFiles)

tasks.register("reportLocalModSelection") {
    description = "Shows which duplicate local Fabric mods are excluded from the classpath"
    group = "help"
    doLast {
        if (supersededLocalMods.isEmpty()) {
            logger.lifecycle("No duplicate Fabric mod IDs were found.")
        } else {
            logger.lifecycle("Excluded ${supersededLocalMods.size} superseded local mod jars:")
            supersededLocalMods.sortedWith(compareBy({ it.id }, { it.version })).forEach {
                logger.lifecycle("  ${it.id} ${it.version}: ${it.file.name}")
            }
        }
    }
}

val nestedModJarsDirectory = layout.buildDirectory.dir("generated/nested-mod-jars")
val extractNestedModJars = tasks.register("extractNestedModJars") {
    description = "Extracts Fabric jar-in-jar dependencies for the compile classpath"
    group = "build setup"

    inputs.files(localModJars)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(nestedModJarsDirectory)

    doLast {
        val outputRoot = nestedModJarsDirectory.get().asFile
        delete(outputRoot)
        outputRoot.mkdirs()

        localModJars.files.sortedBy { it.name }.forEach { outerJar ->
            ZipFile(outerJar).use { zip ->
                zip.entries().asSequence()
                    .filter { entry ->
                        !entry.isDirectory &&
                            entry.name.startsWith("META-INF/jars/") &&
                            entry.name.endsWith(".jar", ignoreCase = true)
                    }
                    .forEach { entry ->
                        val safeOuterName = outerJar.nameWithoutExtension
                            .replace(Regex("[^A-Za-z0-9._-]"), "_")
                        val safeNestedPath = entry.name
                            .removePrefix("META-INF/jars/")
                            .replace(Regex("[^A-Za-z0-9._/-]"), "_")
                        val outputFile = outputRoot.resolve(safeOuterName).resolve(safeNestedPath)

                        outputFile.parentFile.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            outputFile.outputStream().use(input::copyTo)
                        }
                    }
            }
        }
    }
}

val nestedModJars = files(
    fileTree(nestedModJarsDirectory) {
        include("**/*.jar")
    },
).builtBy(extractNestedModJars)

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.impactdev.net/repository/development/")
    maven("https://maven.impactdev.net/repository/releases/")
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.1")
    mappings("net.fabricmc:yarn:${project.property("minecraft_version")}+build.3:v2")
    runtimeOnly("org.jetbrains.kotlin:kotlin-scripting-ide-services")
    modImplementation("net.fabricmc:fabric-loader:0.19.3")
    implementation(kotlin("scripting-common"))
    implementation(kotlin("scripting-jvm"))
    implementation(kotlin("stdlib"))
    // Fabric mods commonly bundle API/library dependencies under
    // META-INF/jars. The JVM compiler cannot see inside nested jars, so expose
    // the generated extraction as compile-only without packaging it again.
    compileOnly(nestedModJars)
    implementation(
        files(selectedLocalModFiles.filter { it.name.startsWith("jetscript-definition-") }),
    )

    modImplementation(
        files(selectedLocalModFiles.filter {
            it.name.startsWith("jetscript-") && !it.name.startsWith("jetscript-definition-")
        }),
    )
    modImplementation(
        files(selectedLocalModFiles.filter { !it.name.startsWith("jetscript") }),
    )
    implementation(kotlin("stdlib-jdk8"))
}
kotlin {
    jvmToolchain(21)
}

java {
    // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
    // if it is present.
    // If you remove this line, sources will not be generated.
    withSourcesJar()
}
