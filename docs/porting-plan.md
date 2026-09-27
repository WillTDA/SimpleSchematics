# Porting plan: one repository, NeoForge 1.21.1 now, Fabric later

Decided on 27 September 2026:

- **Stonecutter** for versions and loaders, with the renderer split by era.
- **Forge 1.20.1 and NeoForge 1.21.1** are the versions kept long term. Fabric and newer
  Minecraft versions stay possible later and nothing here rules them out, but no work goes
  into them until they are asked for.
- **The mod's own config file** replaces `ForgeConfigSpec`, with existing settings imported once.
- **Kotlin** build scripts.

The rest of this page is the reasoning behind those choices and the order of work.

## Where the code stands

One Forge 1.20.1 project on ForgeGradle 6, Mojang names, Java 17, about 14,000 lines of Java.
It is a client mod whose value is almost all in rendering, screens and input, so nearly every
file talks to Minecraft directly.

Measured by imports:

| Kind of code | Where | Size |
| --- | --- | --- |
| No Minecraft or loader types, and no dependency on classes that have them | `CuboidPlanner`, `PrintJournal`, `ResourceBudget`, `PrintConfirmLayout`, `ResourceListColumns`, `SectionVisibility`, `LitematicBitArray` | about 440 lines |
| Forge-only API (events, key registration, config, overlays, chat and sound hooks) | 16 files, mostly `SimpleSchematicsClient`, `InputHandler`, `Keybinds`, `SSConfig`, the two overlays, `PrintChat`, `PrintSounds` | spread thin |
| Reflection by Forge's SRG names | `PrintAcknowledgement` (two fields), `PrintPlacement` (one method) | 3 lookups |
| Raw rendering (GL state, `RenderSystem`, `BufferBuilder`, `VertexBuffer`, the core shader) | `render/`, `gui/SchematicPreview` | about 1,550 lines, and the part that changes most between Minecraft versions |

So there is a pure Java core, but it is small, about three per cent. It is worth pulling out
for testing and reuse, but it will not carry a port. The port is mostly about the vanilla API
surface, and above all the renderer.

## What differs between the targets

| | Forge 1.20.1 | NeoForge 1.21.1 | Fabric 1.21.x | Latest (26.3, September 2026) |
| --- | --- | --- | --- | --- |
| Gradle plugin | ForgeGradle 6, or ModDevGradle Legacy | ModDevGradle | Fabric Loom | Loom 1.17 without remapping, ModDevGradle |
| Names in production | SRG, reobfuscated | Mojang | Intermediary | Unobfuscated since 26.1 |
| Java | 17 | 21 | 21 | 25 |
| Item data | NBT tags | Data components | Data components | Data components |
| Building vertices | `vertex().endVertex()` | `addVertex()` and `MeshData` | Same, then render pipelines from 1.21.5 | Blaze3D, Vulkan capable from 26.2 |
| 3D in a screen | Draw with a scissor and clear depth | Same | Picture in picture from 1.21.6 | Picture in picture |
| Window and keys | GLFW | GLFW | GLFW | SDL from 26.3 |
| Events | Forge bus | NeoForge bus, renamed | Fabric API callbacks, plus mixins where nothing is cancellable | Same |
| Config | `ForgeConfigSpec` | `ModConfigSpec` | Nothing built in | Same |

Print specifically: the Creative carrier item's data path changes with item components, and
the player's offhand moves again in later versions, so `CreativePrinter`'s one data command
needs a per version form. The command and query ordering it relies on is vanilla behaviour and
carries over.

## The options

**A folder per loader and version** (`forge-1.20.1/`, `neoforge-1.21.1/`, later `fabric-…/`) with
a shared pure Java core. Easiest to understand and no new tooling. But the core is three per
cent of the code, so every fix, like this week's Print work, is done twice now and four or five
times later, and the copies drift.

**Architectury** (its Loom fork, with or without its API). Solves the loader axis well: one
common module against vanilla, thin Forge, NeoForge and Fabric modules. It does not solve the
Minecraft version axis on its own; each Architectury project is one Minecraft version. Its API
is a runtime dependency players would have to install, which is a poor fit for a small
client-only mod. The Loom fork has tended to trail brand new Minecraft releases.

**Stonecutter.** One source tree, built once per "node" (a Minecraft version and loader pair),
with comment conditions such as `//? if >=1.21 {` for the places that differ. Maintained
templates exist for Forge, NeoForge and Fabric together, with ModDevGradle for NeoForge and
Loom for Fabric. One history, one fix. The risk is files full of conditions where a subsystem
is rewritten between versions, which is exactly the renderer.

**The MultiLoader template with a branch per Minecraft version.** How several large mods work:
`common`, `forge`, `neoforge`, `fabric` subprojects, one branch per version, fixes merged
forward. Clean code, but merging every fix across three to five branches is overhead a solo
project should avoid.

## Recommendation

**Stonecutter for both axes, with the renderer split by era rather than by comment.**

- Nodes `1.20.1-forge` and `1.21.1-neoforge` first. `1.21.1-fabric` next, which shares its
  Minecraft code with the NeoForge node, so only the loader layer is new. Newer versions later,
  one era at a time.
- Plugins: ModDevGradle Legacy for Forge 1.20.1 (it replaces ForgeGradle 6 and still
  reobfuscates to SRG), ModDevGradle for NeoForge, Fabric Loom for Fabric. No Architectury API
  at runtime, so players install nothing extra.
- One `src/`. Loader code goes behind a small `platform` interface (events, key registration,
  overlays, the cancellable use and scroll hooks, sound and chat filtering, paths), with one
  implementation package per loader compiled only into its nodes.
- Small differences between versions are Stonecutter conditions. Wholesale differences, the
  hologram renderer and the library's 3D preview, sit behind interfaces with one source folder
  per era picked by the build script, so no file becomes a thicket of conditions.
- A plain Java `core` Gradle subproject for the code with no Minecraft in it, with JUnit tests
  that run in CI in seconds and gradually replace the hand-run scripts.
- Before any porting, remove the two biggest loader differences while still on Forge 1.20.1:
  replace the three SRG reflection lookups with Mixin accessors, which work on all three
  loaders, and replace `ForgeConfigSpec` storage with the mod's own small config file. The
  settings screen is already the mod's own, and existing settings are imported once.

This answers the rename question: rather than a hand-kept `forge-1.20.1/` folder, the Forge
1.20.1 build becomes the `1.20.1-forge` node generated from the shared source. If you would
still rather have plain folders, the fallback is the MultiLoader layout with one branch per
Minecraft version, not copies side by side.

## Progress

- **Phases 1 and 2 are done** (27 September 2026). The build is Stonecutter with Kotlin
  scripts and ModDevGradle Legacy, one node `1.20.1-forge`. Every class in the new jar is
  byte for byte the same as the ForgeGradle 6 jar; only the manifest gained Stonecutter's
  attributes. The headless checks and a dev client smoke test (Creative paste and Survival
  print) pass on it.
- **The spike worked.** A scratch copy with a `1.21.1-neoforge` node (ModDevGradle 2.0.147,
  NeoForge 21.1.251) set up Minecraft beside the Forge node in one build. The node is not
  committed yet, because the shared source does not compile for it until the port is done.
- **The size of the port, measured.** Compiling today's code against NeoForge 1.21.1 stops
  at about 600 errors in 29 files, and more will surface once those resolve. The biggest
  are `SSConfig` (164, which the own config file removes), `EntityBlockStandIn` (88) and
  `BakedSchematic` (50) in the renderer, `SimpleSchematicsClient` (72) and `InputHandler`
  (24) for events and startup, and `ConfigScreen` (24). Everything else is a handful each.

- **Phase 3, the seams, is done** (27 September 2026), still shipping as Forge 1.20.1 only.
  Settings are the mod's own `ConfigFile`, which reads and writes the same
  `simpleschematics-client.toml` Forge wrote; a Forge-written copy round trips byte for byte.
  Every Forge event and registration lives in `platform/forge/ForgeClient`, which only calls
  shared methods, and the few loader services shared code needs sit in `platform/Platform`.
  The three reflection lookups by SRG name are Mixin accessors. A dev client drove each path
  through Forge's own hooks (a key press, a use click, scrolling, chat, screens, a Creative
  paste and a Survival print) and a dedicated server started with the mod and loaded no
  client classes. The pure Java `core` subproject and the renderer interfaces wait for the
  port itself, where they will be shaped by what the second node actually needs.
- **The port after the seams.** Against NeoForge 1.21.1 the same code now stops at 338
  errors. 132 are in the three `platform` files, which get a NeoForge sibling rather than
  edits. Of the other 206, 138 are the renderer, `EntityBlockStandIn` (88) and
  `BakedSchematic` (50); every other file has a handful.
- **Phase 4, NeoForge 1.21.1, is done** (27 September 2026). The `1.21.1-neoforge` node builds
  beside `1.20.1-forge` from the same `src/`, with about fifty Stonecutter conditions in all:
  the renderer's buffer handling and vertex calls (`BakedSchematic`, `EntityBlockStandIn`,
  `WorldRenderer`), the Creative paste's carrier path and 1.21 key names, and a handful of
  small ones, most of them inside helpers (`util/Ids`, `gui/Screens`,
  `MaterialResolver.plantIn`, `PrintInventory.carriesState`). The NeoForge listener,
  `platform/neoforge/NeoForgeClient`, mirrors the Forge one event for event. Schematics carry
  their data version and are brought up to the running game through its own data fixers,
  so a 1.20.1 schematic pastes on 1.21.1 with its items in the new layout.
- **Checked in a NeoForge dev client**: the M key, use click, scroll and chat through
  NeoForge's own hooks; every screen drawn over 1.21's blurred backdrop; a scan of chests,
  signs, a patterned banner, a player head, a decorated pot with sherds, a bed, a shulker
  box, a conduit, a hanging sign, a beehive, a painting and an item frame, pasted in Creative
  with every block and block entity identical; a 1.20.1-format schematic upgraded and
  pasted with its named items intact; a Survival print; and the holograms and library
  preview on screen. The shader pack path is not checked, since Iris was not available.
- **Found on the way, and fixed on both nodes**: signs, banners, heads and decorated pots
  had no ghost at all, because the bake skipped every block with an invisible render shape
  before the stand-ins were asked. The paste also counted text as not copied when the
  server wrote the same JSON another way.
- **The renderer stays one file per class.** At 1.21.1 the difference is who owns the buffer
  memory and the names of the vertex calls, which conditions carry without crowding the
  code. The split by era waits for 1.21.5's render pipelines, where the change is wholesale.

## Phases

1. **Spike, a day or so.** An empty mod built by Stonecutter for `1.20.1-forge` (ModDevGradle
   Legacy), `1.21.1-neoforge` (ModDevGradle) and `1.21.1-fabric` (Loom) in one Gradle build,
   each reaching the title screen, with GitHub Actions building every node. The one thing to
   prove is ModDevGradle Legacy, ModDevGradle and Loom living in the same build; the published
   templates show NeoForge and Fabric together.
2. **Move without changing behaviour.** Today's code becomes the `1.20.1-forge` node; ForgeGradle
   6 gives way to ModDevGradle Legacy; the headless client smoke test used for this Print work
   confirms nothing changed. `CLAUDE.md`, `README.md` and the scripts follow the new paths.
3. **Seams, still Forge only.** The platform interface, Mixin accessors, the own config file, the
   `core` subproject with tests, and the renderer and preview behind interfaces.
4. **NeoForge 1.21.1.** Item components and the carrier path, `ResourceLocation` factories, the
   new vertex API in `BakedSchematic`, `EntityBlockStandIn` and `WorldRenderer`, NeoForge events
   and GUI layers, Iris in place of Oculus for the shader pack path, `neoforge.mods.toml`, Java
   21. The re-sort buffer, depth clearing and shader pack traps in `CLAUDE.md` are re-proven in
   game on this version.
5. **Fabric 1.21.1.** Only the loader layer: Fabric API callbacks, Mixins for the cancellable use
   key, scroll and sound, and Mod Menu for the settings screen as an optional extra.
6. **Newer versions, one era at a time.** 1.21.5 and later (render pipelines, picture in picture
   previews), 26.1 and later (unobfuscated, Java 25, Loom without remapping), 26.2 and later
   (Vulkan capable Blaze3D), 26.3 (SDL key constants). Each is mostly a new renderer and preview
   folder plus conditions.

## Decisions

1. Stonecutter as above, not plain branches. **Decided: Stonecutter.**
2. Versions kept long term. **Decided: Forge 1.20.1 and NeoForge 1.21.1.** Phases 5 and 6
   wait until Fabric or a newer version is asked for.
3. The mod's own config file in place of `ForgeConfigSpec`. **Decided: yes.**
4. Kotlin or Groovy build scripts. **Decided: Kotlin.**

## Risks

- The renderer is where the time goes. Its special cases (sort buffer sizing, depth clearing in
  the preview, block entity stand-ins, the shader pack path) have to be re-proven on each era.
- Shader pack detection differs per loader and version: Oculus on Forge, Iris on NeoForge and
  Fabric.
- Build tooling for 26.x moves quickly (Loom 1.15 to 1.17 and Gradle 9.4 to 9.6 within 2026), so
  every node pins its versions.
