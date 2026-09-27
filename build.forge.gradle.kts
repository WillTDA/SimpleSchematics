import java.text.SimpleDateFormat
import java.util.Date

// Builds a Minecraft Forge node. ModDevGradle Legacy covers Forge 1.17 to 1.20.1 and
// reobfuscates the jar to SRG names, which is what Forge 1.20.1 loads in production.
plugins {
    id("net.neoforged.moddev.legacyforge")
}

val modId = property("mod.id") as String
val modName = property("mod.name") as String
val modAuthors = property("mod.authors") as String
val modLicence = property("mod.licence") as String
// Named so they cannot be mistaken for same-named properties inside the plugin blocks.
val mcVersion = sc.current.version
val forgeRelease = property("deps.forge") as String

version = property("mod.version") as String
group = property("mod.group") as String
// Every node's jar lands in dist/, so the name says which game and loader it is for.
base.archivesName = "$modId-forge-$mcVersion"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

legacyForge {
    // "official" mappings keep the build self contained. If you prefer readable
    // parameter names, add Parchment through `parchment { ... }`.
    enable {
        forgeVersion = "$mcVersion-$forgeRelease"
    }

    runs {
        register("client") {
            client()
            gameDirectory = rootProject.file("run")
            systemProperty("forge.logging.markers", "")
            systemProperty("forge.logging.console.level", "debug")
        }
        // Simple Schematics is client only, but a server run helps verify that
        // the mod never touches server side code paths.
        register("server") {
            server()
            gameDirectory = rootProject.file("run/server")
            systemProperty("forge.logging.console.level", "debug")
            programArgument("--nogui")
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

// Accessor mixins stand in for reflection by SRG name. The annotation processor
// writes the refmap that maps their Mojang names to SRG in the reobfuscated jar.
mixin {
    add(sourceSets.main.get(), "$modId.refmap.json")
    config("$modId.mixins.json")
}

dependencies {
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
}

tasks.processResources {
    val replacements = mapOf(
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_version" to project.version.toString(),
        "mod_authors" to modAuthors,
        "mod_licence" to modLicence,
        "forge_version" to forgeRelease,
        "minecraft_version" to mcVersion,
        "pack_format" to "15",
        "mixin_java" to "JAVA_17",
    )
    inputs.properties(replacements)
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta", "$modId.mixins.json")) {
        expand(replacements)
    }
    exclude("META-INF/neoforge.mods.toml")
}

tasks.jar {
    manifest {
        attributes(
            "Specification-Title" to modId,
            "Specification-Vendor" to modAuthors,
            "Specification-Version" to "1",
            "Implementation-Title" to modName,
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to modAuthors,
            "Implementation-Timestamp" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(Date()),
            "MixinConfigs" to "$modId.mixins.json",
        )
    }
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("NOTICE")) { into("META-INF") }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Minecraft is set up from the generated sources, so they have to exist first.
tasks.named("createMinecraftArtifacts") {
    dependsOn("stonecutterGenerate")
}

tasks.register<Copy>("dist") {
    group = "distribution"
    description = "Builds the mod and copies the final jar into the dist/ folder in the project root."
    from(tasks.named<Jar>("reobfJar").flatMap { it.archiveFile })
    into(rootProject.layout.projectDirectory.dir("dist"))
}

// Writes the runtime classpath for the headless checks in scripts/, without starting a client.
tasks.register("writePrintTestClasspath") {
    group = "verification"
    description = "Writes the classpath the scripts/ checks compile and run against."
    val classpath = sourceSets.main.get().runtimeClasspath
    val target = rootProject.layout.buildDirectory.file("verification/print/runtime-classpath.txt")
    inputs.files(classpath)
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
        file.writeText(classpath.files.joinToString("\n") { it.absolutePath })
    }
}
