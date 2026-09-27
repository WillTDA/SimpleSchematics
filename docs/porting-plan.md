# Porting plan: one repository, NeoForge 1.21.1 now, Fabric later

A proposal, not a change that has been made. Nothing has moved yet; the decisions at the end
decide how the first step is done.

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

## Decisions needed

1. Stonecutter as above, or plain branches with the MultiLoader layout?
2. Which versions to keep long term. Every renderer era is real work, so Forge 1.20.1,
   NeoForge 1.21.1, Fabric 1.21.1 and the latest release is far cheaper than every version in
   between.
3. Replacing `ForgeConfigSpec` with the mod's own config file, settings carried over.
4. Kotlin or Groovy build scripts. The Stonecutter templates are Kotlin.

## Risks

- The renderer is where the time goes. Its special cases (sort buffer sizing, depth clearing in
  the preview, block entity stand-ins, the shader pack path) have to be re-proven on each era.
- Shader pack detection differs per loader and version: Oculus on Forge, Iris on NeoForge and
  Fabric.
- Build tooling for 26.x moves quickly (Loom 1.15 to 1.17 and Gradle 9.4 to 9.6 within 2026), so
  every node pins its versions.
