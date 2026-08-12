plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.scripting") version "2.3.20"
    id("fabric-loom") version "1.17-SNAPSHOT"
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
    implementation(kotlin("scripting-common"))
    implementation(kotlin("scripting-jvm"))
    implementation(kotlin("stdlib"))
    implementation(
        fileTree("libs/") {
            include("jetscript-definition-*.jar")
        },
    )

    modImplementation(
        fileTree("libs/") {
            include("jetscript-*.jar")
            exclude("jetscript-definition-*.jar")
        },
    )
    modImplementation(
        fileTree("libs/") {
            include("*.jar")
            exclude("jetscript*.jar")
        },
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
