pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
    }
}

plugins {
    // Builds the one source tree once per Minecraft version and loader.
    // https://stonecutter.kikugie.dev/
    id("dev.kikugie.stonecutter") version "0.9.8"
    // Lets Gradle fetch a missing JDK for a node's toolchain.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        /*
         * Each node is a Minecraft version and a loader, built from the shared src/ by
         * build.<loader>.gradle.kts into versions/<version>-<loader>/.
         * NeoForge 1.21.1 joins here once the code has been ported to it.
         */
        fun match(version: String, vararg loaders: String) {
            for (loader in loaders) version("$version-$loader", version).buildscript("build.$loader.gradle.kts")
        }

        match("1.20.1", "forge")
        vcsVersion = "1.20.1-forge"
    }
}

rootProject.name = "SimpleSchematics"
