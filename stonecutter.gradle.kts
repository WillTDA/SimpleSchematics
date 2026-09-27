plugins {
    id("dev.kikugie.stonecutter")
    // Declared once here so every node shares one plugin classpath.
    id("net.neoforged.moddev.legacyforge") version "2.0.147" apply false
    id("net.neoforged.moddev") version "2.0.147" apply false
}

// The node the sources on disk are written for. The others are generated from it.
stonecutter active "1.20.1-forge"

// See https://stonecutter.kikugie.dev/wiki/config/params
stonecutter parameters {
    val loader = current.project.substringAfterLast('-')

    // Version and loader sections of stonecutter.properties.toml apply to their nodes.
    properties {
        tags(current.version, loader)
    }

    // Makes `//? if forge {` and `//? if neoforge {` available in source comments.
    constants {
        match(loader, "forge", "neoforge")
    }
}
