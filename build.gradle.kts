plugins {
    id("fabric-loom") version "1.16-SNAPSHOT"
    kotlin("jvm") version "2.3.20"
}

version = "1.0.0"
group = "dev.krister"

base {
    archivesName.set("DungeonTerminalMap")
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    minecraft("com.mojang:minecraft:26.1.2")
    mappings(loom.layered {
        mappings(file("mappings/empty.tiny"))
    })
    modImplementation("net.fabricmc:fabric-loader:0.19.3")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.150.0+26.1.2")
    modImplementation("net.fabricmc:fabric-language-kotlin:1.13.10+kotlin.2.3.20")
    modApi(files("libs/devonian-1.25.9.jar"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("25"))
    }
}
