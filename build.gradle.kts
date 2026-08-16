import groovy.json.JsonSlurper
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
data class NestedJarCandidate(
    val bytes: ByteArray,
    val origin: String,
    val id: String?,
    val version: String?,
    val touchesMinecraft: Boolean,
    val suppliesImportedApi: Boolean,
    val hash: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NestedJarCandidate

        if (touchesMinecraft != other.touchesMinecraft) return false
        if (suppliesImportedApi != other.suppliesImportedApi) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (origin != other.origin) return false
        if (id != other.id) return false
        if (version != other.version) return false
        if (hash != other.hash) return false

        return true
    }

    override fun hashCode(): Int {
        var result = touchesMinecraft.hashCode()
        result = 31 * result + suppliesImportedApi.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + origin.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + hash.hashCode()
        return result
    }
}

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

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

data class NestedJarContents(
    val id: String?,
    val version: String?,
    val children: List<Pair<String, ByteArray>>,
)

fun readNestedJarContents(bytes: ByteArray): NestedJarContents {
    var metadata: Map<String, Any?>? = null
    val jarEntries = linkedMapOf<String, ByteArray>()

    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            when {
                entry.name == "fabric.mod.json" -> {
                    @Suppress("UNCHECKED_CAST")
                    metadata = JsonSlurper().parseText(
                        String(zip.readBytes(), StandardCharsets.UTF_8),
                    ) as Map<String, Any?>
                }
                !entry.isDirectory && entry.name.endsWith(".jar", ignoreCase = true) -> {
                    jarEntries[entry.name] = zip.readBytes()
                }
            }
        }
    }

    val childPaths = (metadata?.get("jars") as? Collection<*>)
        .orEmpty()
        .mapNotNull { (it as? Map<*, *>)?.get("file")?.toString() }
    return NestedJarContents(
        metadata?.get("id")?.toString(),
        metadata?.get("version")?.toString(),
        childPaths.mapNotNull { path -> jarEntries[path]?.let { path to it } },
    )
}

val nestedCandidates = mutableListOf<NestedJarCandidate>()
val visitedNestedHashes = mutableSetOf<String>()

fun visitNestedJar(bytes: ByteArray, origin: String) {
    val hash = sha256(bytes)
    if (!visitedNestedHashes.add(hash)) return

    val contents = readNestedJarContents(bytes)
    val classes = listNestedClasses(bytes)
    nestedCandidates += NestedJarCandidate(
        bytes = bytes,
        origin = origin,
        id = contents.id,
        version = contents.version,
        touchesMinecraft = nestedJarTouchesMinecraft(bytes),
        suppliesImportedApi = nestedJarSuppliesImportedApi(classes),
        hash = hash,
    )
    contents.children.forEach { (path, childBytes) ->
        visitNestedJar(childBytes, "$origin!/$path")
    }
}

selectedLocalMods
    // Fabric API is supplied from its published Maven module and already
    // carries its complete component dependency graph.
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
            nestedPaths.forEach { path ->
                val entry = outerZip.getEntry(path) ?: return@forEach
                val bytes = outerZip.getInputStream(entry).use { it.readBytes() }
                visitNestedJar(bytes, "${outerMod.file.name}!/$path")
            }
        }
    }

val selectedOuterModIds = selectedLocalMods.mapNotNull { it.id }.toSet()
val selectedNestedCandidates = nestedCandidates
    .filter {
        (it.touchesMinecraft || it.suppliesImportedApi) && it.id !in selectedOuterModIds
    }
    .groupBy { it.id ?: it.hash }
    .values
    .map { versions ->
        versions.maxWithOrNull { left, right ->
            compareVersionParts(left.version.orEmpty(), right.version.orEmpty())
                .takeIf { it != 0 }
                ?: left.hash.compareTo(right.hash)
        }!!
    }

val discoveredNestedApis = buildList {
    selectedNestedCandidates.forEach { candidate ->
        val identity = candidate.id.orEmpty()
            .ifBlank { candidate.origin.substringBefore("!/").substringBeforeLast('.') }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val jarName = candidate.origin.substringAfterLast('/').ifBlank { "$identity.jar" }
        val outputDirectory = layout.buildDirectory
            .dir("generated/imported-nested-apis/$identity")
            .get().asFile
        val outputJar = outputDirectory.resolve(jarName)
        val stampFile = outputDirectory.resolve("${outputJar.name}.stamp")

        if (!outputJar.isFile || !stampFile.isFile || stampFile.readText() != candidate.hash) {
            outputDirectory.mkdirs()
            outputJar.writeBytes(candidate.bytes)
            stampFile.writeText(candidate.hash)
        }
        add(DiscoveredNestedApi(outputJar, candidate.touchesMinecraft))
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
