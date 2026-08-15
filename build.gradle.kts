import groovy.json.JsonSlurper
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

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

data class DiscoveredNestedApi(val file: File, val touchesMinecraft: Boolean)

val importedClassPaths = fileTree("src/main/kotlin") {
    include("**/*.kt", "**/*.kts")
}.files.flatMap { source ->
    Regex("(?m)^\\s*import\\s+([A-Za-z_][A-Za-z0-9_.*]*)")
        .findAll(source.readText())
        .map { it.groupValues[1].removeSuffix(".*").replace('.', '/') }
        .filterNot {
            it.startsWith("java/") || it.startsWith("javax/") ||
                it.startsWith("kotlin/") || it.startsWith("net/minecraft/")
        }
        .toList()
}.toSet()

fun nestedJarSuppliesImportedApi(classNames: Set<String>): Boolean {
    return importedClassPaths.any { imported ->
        val importedPackage = imported.substringBeforeLast('/', missingDelimiterValue = imported)
        classNames.any { className ->
            className == imported ||
                className.startsWith("$imported$") ||
                className.substringBeforeLast('/', missingDelimiterValue = className) == importedPackage
        }
    }
}

fun listNestedClasses(bytes: ByteArray): Set<String> {
    val classes = mutableSetOf<String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { nestedZip ->
        while (true) {
            val entry = nestedZip.nextEntry ?: break
            if (!entry.isDirectory && entry.name.endsWith(".class")) {
                classes += entry.name.removeSuffix(".class")
            }
        }
    }
    return classes
}

fun nestedJarTouchesMinecraft(bytes: ByteArray): Boolean {
    ZipInputStream(ByteArrayInputStream(bytes)).use { nestedZip ->
        while (true) {
            val entry = nestedZip.nextEntry ?: break
            if (!entry.isDirectory && entry.name.endsWith(".class")) {
                val classBytes = nestedZip.readBytes()
                if (String(classBytes, StandardCharsets.ISO_8859_1).contains("net/minecraft/")) {
                    return true
                }
            }
        }
    }
    return false
}

val discoveredNestedApis = buildList {
    selectedLocalMods
        // Fabric API is already supplied from its published Maven module.
        .filter { it.id != "fabric-api" }
        .forEach { outerMod ->
            ZipFile(outerMod.file).use { outerZip ->
                val metadataEntry = outerZip.getEntry("fabric.mod.json") ?: return@use
                val metadata = outerZip.getInputStream(metadataEntry).bufferedReader().use {
                    @Suppress("UNCHECKED_CAST")
                    JsonSlurper().parse(it) as Map<String, Any?>
                }
                val nestedPaths = (metadata["jars"] as? Collection<*>)
                    .orEmpty()
                    .mapNotNull { (it as? Map<*, *>)?.get("file")?.toString() }

                nestedPaths.forEach { nestedPath ->
                    val nestedEntry = outerZip.getEntry(nestedPath) ?: return@forEach
                    val bytes = outerZip.getInputStream(nestedEntry).use { it.readBytes() }
                    val classNames = listNestedClasses(bytes)
                    if (!nestedJarSuppliesImportedApi(classNames)) return@forEach
                    val touchesMinecraft = nestedJarTouchesMinecraft(bytes)

                    val outerName = outerMod.id.orEmpty()
                        .ifBlank { outerMod.file.nameWithoutExtension }
                        .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val outputDirectory = layout.buildDirectory
                        .dir("generated/imported-nested-apis/$outerName")
                        .get().asFile
                    val outputJar = outputDirectory.resolve(nestedPath.substringAfterLast('/'))
                    val expectedStamp = "${outerMod.file.length()}:${outerMod.file.lastModified()}:${nestedEntry.crc}"
                    val stampFile = outputDirectory.resolve("${outputJar.name}.stamp")

                    if (!outputJar.isFile || !stampFile.isFile || stampFile.readText() != expectedStamp) {
                        outputDirectory.mkdirs()
                        outputJar.writeBytes(bytes)
                        stampFile.writeText(expectedStamp)
                    }
                    add(DiscoveredNestedApi(outputJar, touchesMinecraft))
                }
            }
        }
}
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
        logger.lifecycle("Discovered ${discoveredNestedApis.size} imported jar-in-jar APIs:")
        discoveredNestedApis.sortedBy { it.file.name }.forEach {
            logger.lifecycle("  ${it.file.name} (${if (it.touchesMinecraft) "Loom-remapped" else "plain JVM"})")
        }
    }
}

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
    // Use Fabric's published component dependencies instead of remapping every
    // jar-in-jar from the complete server modpack.
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
    // Discover jar-in-jar APIs from imports. Only artifacts whose bytecode
    // touches Minecraft require Loom remapping; ordinary libraries remain on
    // the lightweight JVM compile-only classpath.
    modCompileOnly(files(discoveredNestedApis.filter { it.touchesMinecraft }.map { it.file }))
    compileOnly(files(discoveredNestedApis.filterNot { it.touchesMinecraft }.map { it.file }))
    implementation(kotlin("scripting-common"))
    implementation(kotlin("scripting-jvm"))
    implementation(kotlin("stdlib"))
    implementation(
        files(selectedLocalModFiles.filter { it.name.startsWith("jetscript-definition-") }),
    )

    modImplementation(
        files(selectedLocalModFiles.filter {
            it.name.startsWith("jetscript-") && !it.name.startsWith("jetscript-definition-")
        }),
    )
    modImplementation(
        files(selectedLocalMods.filter {
            it.id != "fabric-api" && !it.file.name.startsWith("jetscript")
        }.map { it.file }),
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
