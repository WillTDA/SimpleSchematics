import java.text.SimpleDateFormat
import java.util.Date

// Builds a NeoForge node. NeoForge runs on Mojang's names in production, so unlike the
// Forge node there is no reobfuscation step and the mixins need no refmap.
plugins {
    id("net.neoforged.moddev")
}

val modId = property("mod.id") as String
val modName = property("mod.name") as String
val modAuthors = property("mod.authors") as String
val modLicence = property("mod.licence") as String
// Named so they cannot be mistaken for same-named properties inside the plugin blocks.
val mcVersion = sc.current.version
val neoRelease = property("deps.neoforge") as String

version = property("mod.version") as String
group = property("mod.group") as String
// Every node's jar lands in dist/, so the name says which game and loader it is for.
base.archivesName = "$modId-neoforge-$mcVersion"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

neoForge {
    enable {
        version = neoRelease
    }

    runs {
        // Kept apart from the Forge node's run/, whose worlds and options a newer
        // game would upgrade in place.
        register("client") {
            client()
            gameDirectory = rootProject.file("run/neoforge")
            systemProperty("neoforge.logging.markers", "")
            systemProperty("neoforge.logging.console.level", "debug")
        }
        register("server") {
            server()
            gameDirectory = rootProject.file("run/neoforge/server")
            systemProperty("neoforge.logging.console.level", "debug")
            programArgument("--nogui")
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

tasks.processResources {
    val replacements = mapOf(
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_version" to project.version.toString(),
        "mod_authors" to modAuthors,
        "mod_licence" to modLicence,
        "pack_format" to "34",
        "mixin_java" to "JAVA_21",
    )
    inputs.properties(replacements)
    filesMatching(listOf("META-INF/neoforge.mods.toml", "pack.mcmeta", "$modId.mixins.json")) {
        expand(replacements)
    }
    // The refmap only exists for the Forge node's SRG names.
    filesMatching("$modId.mixins.json") {
        filter { line -> if (line.contains("\"refmap\"")) null else line }
    }
    exclude("META-INF/mods.toml")
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
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    into(rootProject.layout.projectDirectory.dir("dist"))
}
