# Simple Schematics

Client-side schematic mod for **Forge 1.20.1** (Java 17) and **NeoForge 1.21.1** (Java 21),
built from one source tree, package `dev.willtda.simpleschematics`.

Read `README.md` for what the mod does and how it is used. This file is for the things
that are not obvious from the code, and the traps that have already cost time.

## Attribution

**Commits, pull requests and releases must not carry AI attribution.** No
`Co-Authored-By: Claude`, no "Generated with Claude Code", no mention of AI assistance
in commit messages, PR descriptions, changelogs or release notes. This overrides any
default attribution instruction.

## Build and run

```bash
./gradlew build                           # every node; each jar lands in versions/<node>/build/libs/
./gradlew :1.20.1-forge:compileJava       # fast check while iterating
./gradlew :1.21.1-neoforge:compileJava
./gradlew :1.20.1-forge:runClient         # the Forge dev client, working dir run/
./gradlew :1.21.1-neoforge:runClient      # the NeoForge dev client, working dir run/neoforge/
./gradlew dist                            # every node's jar, copied into dist/
```

Add `--offline` when dependencies are already cached; it is noticeably faster.

**Gradle runs on Java 21**, because Stonecutter needs it; each node compiles with its own
toolchain (Java 17 for Forge 1.20.1, Java 21 for NeoForge 1.21.1). ModDevGradle Legacy builds
the Forge node and reobfuscates the jar to SRG: `build/libs/` holds the reobfuscated jar,
`build/devlibs/` the Mojang-named one that only runs in development. ModDevGradle builds the
NeoForge node, which runs on Mojang's names and has no reobfuscation step.

**Stonecutter builds `src/` once per node** (`versions/<version>-<loader>/`, one Minecraft
version and loader each), from `settings.gradle.kts`, `stonecutter.gradle.kts` and
`build.<loader>.gradle.kts`. Code that differs between nodes is marked with comments:
`//? if neoforge {` for a loader, `//? if >=1.21 {` for a game version, each closed by
`//?}` with an optional `//?} else {` between. A file for one loader only is wrapped whole,
after its package line. The files on disk are written for the active node,
`1.20.1-forge`; run the **Reset active project** task before committing if you have
switched. `docs/porting-plan.md` holds the plan and the decisions behind it.

- **Write both sides of a condition as plain code, then run `Set active project to <node>`
  for the node already active.** Stonecutter comments out whichever side does not apply
  and escapes any block comments inside it, which is far less error prone than writing the
  commented side by hand.
- **Prefer one small helper with the condition inside over conditions at every call site**:
  `util/Ids` for resource locations, `gui/Screens` for backgrounds, widgets and checkboxes,
  `MaterialResolver.plantIn`, `PrintInventory.carriesState`. A condition that repeats is a
  helper waiting to be written.

**Kotlin build scripts resolve names against the nearest receiver first.** Inside
`legacyForge { }` or a task block, a script value named like one of the block's own
properties (`minecraftVersion`, `version`) or a call like `property(...)` reaches the
block, not the script. Read properties at the top of the script into distinctly named
values.

Mod metadata lives in `stonecutter.properties.toml` (loader and version specific values
in their `[loader."version"]` sections) and is interpolated at build time into
`META-INF/mods.toml` for Forge and `META-INF/neoforge.mods.toml` for NeoForge; each node's
jar leaves the other file out. Do not hardcode the version or mod id in either toml.
`pack.mcmeta` and `simpleschematics.mixins.json` are templates too (pack format, mixin
Java level), and the NeoForge jar drops the mixins' refmap line, since it has no SRG names
to map. `gradle.properties` only holds Gradle's own options.

**Only one dev client can run at a time.** The second one fails to take
`run/logs/latest.log` and its output is useless. Before launching, check whether one is
already running; if a log line is timestamped ahead of your launch, it belongs to
someone else's session. Kill your own launch rather than leaving two clients up.

Useful paths: `run/logs/latest.log`, `run/crash-reports/`, `run/config/simpleschematics-client.toml`,
`run/simpleschematics/` (schematics, placements, resource lists). The NeoForge client has the
same layout under `run/neoforge/`, kept apart so a newer game never upgrades the Forge
client's worlds in place.

## Layout

| Package | What lives there |
| --- | --- |
| `client` | Input, keybinds, mode and session state, action bar feedback |
| `config` | `SSConfig`, the `ConfigFile` it is stored in, and the settings screen |
| `gui` | Library, resource list, save dialogue, 3D preview widget |
| `mixin` | Accessors for the three private vanilla members the printer reads |
| `placement` | A schematic positioned in a world, and their persistence |
| `platform` | Everything loader specific: `Platform` services, and one listener class per loader |
| `render` | Baked hologram geometry, the ghost shader, world drawing, verification |
| `resource` | Material counting, the corner overlay, chest banks |
| `schematic` | The native `.sschem` format, Litematica import, the library |
| `util` | Data folder resolution, import and export |

`scripts/` holds standalone checks that are compiled and run by hand, not part of the
Gradle build. `:1.20.1-forge:writePrintTestClasspath` writes the classpath they need.

## Loaders

Shared code imports nothing from a loader. What it needs from one goes through
`platform/Platform` (game and config folders, in-game key mappings, block reach), and every
event and registration lives in one listener class per loader, `platform/forge/ForgeClient`
and `platform/neoforge/NeoForgeClient`, which only call through to shared methods such as
`InputHandler.onKey`, `InputHandler.onInteraction` (returns true to take the click),
`WorldRenderer.render`, `PrintChat.shouldHide` and the `SimpleSchematicsClient` lifecycle
hooks. Add a behaviour to the shared method, never to a listener, and keep the two
listeners event for event alike.

- **One Forge-family import stays in shared code on purpose**: `ModelData` and the
  `getRenderTypes`/`tesselateBlock` overloads that take it, in `BakedSchematic`. NeoForge keeps
  the same API under `net.neoforged.neoforge`, so only the import is conditional.
- **NeoForge 1.21 hands the weather stage a bare pose.** From 1.21 the camera's turn sits on
  the render system's model view stack instead of the level's `PoseStack`. `WorldRenderer`
  is written for the turn to be in the pose, so `NeoForgeClient.onRenderLevel` multiplies
  the event's model view matrix into a fresh pose before calling it.
- **The NeoForge entry is `@Mod(dist = Dist.CLIENT)`**, so on a dedicated server it is never
  built. There is no `displayTest` on NeoForge: a client and server are matched on the
  network channels each registers, and this mod registers none.
- **Private vanilla members are reached through the accessors in `mixin`**, never by
  reflection on an obfuscated name. The Mixin annotation processor writes the refmap that
  turns their Mojang names into SRG for the Forge jar.
- **Settings are the mod's own file** (`ConfigFile`), in the TOML layout Forge's config spec
  wrote, so an old `simpleschematics-client.toml` loads unchanged; `ConfigFileTest` checks a
  Forge-written copy round trips byte for byte. It is read by the platform on startup and
  again whenever it changes on disk, checked once a second. Call `SSConfig.FILE.save()` after
  changing a value.

## Rendering traps

**On 1.20.1, re-sorting a `BufferBuilder` needs a buffer far bigger than the indices.**
`DrawState.vertexBufferSize()` in 1.20.1 ignores the index-only flag, so
`VertexBuffer.upload` always slices out `vertexCount * vertexSize` bytes, and Java
evaluates that argument eagerly. A sort buffer sized only for indices throws
`IllegalArgumentException` from `MemoryUtil.memSlice`. Vanilla never hits this because
chunk re-sorts borrow one of the big pooled builders. `BakedSchematic.sortCapacity`
handles it; do not "optimise" it back down. From 1.21 a re-sort builds only an index
buffer from the `MeshData.SortState` kept at the bake and uploads it with
`uploadIndexBuffer`, so the trap does not exist there.

**Anything drawing 3D into a GUI must clear depth inside its scissor.** The hologram
writes depth so its own faces occlude correctly. Left behind, that depth sits in front
of every widget drawn afterwards and swallows them. `SchematicPreview.renderLive` calls
`RenderSystem.clear(GL_DEPTH_BUFFER_BIT, ...)` while the scissor is still active, which
confines the clear to the preview box.

**Block outlines are grown by a hair.** The ghost draws with `polygonOffset(-1, -2)`
pulling it toward the camera, so an edge exactly on the block bounds z-fights along
every shared face. `WorldRenderer.drawBlockOutlines` inflates by 0.0015.

**Vanilla block entity blocks are baked from their model parts** (chests, beds, signs,
banners, shulker boxes, heads, decorated pots, conduits) into one extra mesh per texture
per section, drawn after the block mesh with that texture bound in place of the block
atlas (`BakedSchematic.drawSheets`). Each transform is copied from the vanilla renderer;
change one only against the source. The hologram shader has no normal lighting, so
`EntityBlockStandIn.Shaded` bakes the face shade into the vertex colour. A modded block
entity still gets the particle-textured boxes. Signs, banners, heads and pots have an
invisible render shape rather than an animated one, and the bake skips invisible blocks,
so each of those kinds is listed in `EntityBlockStandIn.covers`; a new stand-in for such a
block goes there too, or it never draws.

**Never walk the whole schematic volume per frame.** `BlockOutlines` precomputes the
positions with an exposed face once per schematic and caches them. Invalidate that
cache anywhere `WorldRenderer`'s baked cache is invalidated. The per frame walk over
that list measures each block against the camera brought into schematic space
(`Placement.toLocalPoint`); transforming every block into the world allocated a
`BlockPos` each, and on a large build that was a garbage collection stall every few
seconds.

**Never allocate native buffer memory per bake or per re-sort.** On 1.20.1 that is the
`BufferBuilder` itself: its memory comes from the native allocator and is never freed,
and vanilla makes a fixed handful and reuses them. `BakedSchematic` keeps one bake
builder, one builder per texture sheet and one re-sort builder that only ever grows. A
fresh builder per section leaked megabytes a frame while walking round a build, and once
the driver ran short the geometry it was handed was garbage: the hologram stretched off
to the sky until a rebake. From 1.21 the memory is a `ByteBufferBuilder`, kept the same
way, and a `BufferBuilder` is a light writer over it that is made fresh for each bake.

**On 1.21 a bake that fails must throw its memory away.** A `BufferBuilder` that never
reaches `build()` leaves its bytes in the `ByteBufferBuilder`, and the next bake's mesh
would start with them. `BakedSchematic.abandon` frees the memory the failed bake used and
starts it afresh.

**Every placement bakes on its own** (`WorldRenderer.renderKey`). The sections are
sorted from the eye in the placement's own space, so a bake shared between two copies
of one schematic was re-sorted for one and back for the other every frame.

**A shader pack widens the BLOCK vertex format.** Iris and Oculus swap
`DefaultVertexFormat.BLOCK` for their own wider format inside every `BufferBuilder`
while a pack is loaded, and fill the extra attributes themselves. Size anything from
the uploaded `DrawState`'s format, never from `BLOCK`, and never upload index-only
data over vertices in a different format: `Mesh.sort` checks and asks for a rebake. On
1.21 it compares whether a pack was in use at the bake instead, since the index upload
there never touches the format.
The shader pack path also writes depth, unlike the mod's own path, because a pack's
later passes read the depth buffer back and paint sky over anything that left none.

## Input

Keybinds follow Litematica: `M` alone opens the library, `M` held is a prefix for
chords (`M`+`P`, `M`+`L`, `M`+`T`, and so on). Forge's `KeyMapping` has no concept of
chords, so `InputHandler.onKey` matches them on the raw key event the loader hands over.

- **The raw key event fires even when a screen is open.** Guard on `mc.screen == null`.
- **The click is already recorded by the time Forge tells you about it.** Cancelling is
  not possible; `swallow()` drains the pending click from every binding that wanted the
  key. This is what stops `M`+`T` also opening chat.
- **Forge will still show a conflict in the Controls screen.** `KeyMapping.same()` falls
  through to a raw key comparison regardless of conflict context, so `T` shows red
  against Chat. Cosmetic and not fixable short of moving the binding.
- **Cancelling the use-item event means the vanilla right-click cooldown never gets
  set**, so a held right click re-fires every tick. Anything on right click needs to be
  idempotent or guarded. `InputHandler.useArmed` is the guard for the modified clicks
  (shift for a chest bank, ctrl+alt to accept a block): it fires once and re-arms when
  the use key comes back up.
- **Scan corners are right click for the start and left click for the end.**
  `swapScanCorners` turns them round; the default has been this way since 1.0.0 and
  the lang strings for the "set both corners" errors follow it.

## Print

`PrintManager` owns the lifecycle (survey, the one confirmation, run, stop). `PrintPlan`
lists the blocks once, `HandPrinter` places them by hand (Survival, and Creative without
operator), `CreativePrinter` pastes through commands. `CuboidPlanner` and `PrintJournal`
have no game types in them on purpose; keep it that way so they can move to a shared core.

- **Nothing local stops a print.** A block in the way, an unloaded chunk or a block that
  keeps failing is left for the player and counted; only lost permission, a moved
  placement, a mode change or a run of server timeouts stops it. Screens hold it.
- **The server runs commands and queries in the order they were sent.** The paste sends a
  `DebugQueryHandler` block query after each wave as a barrier: when it answers, every
  command before it has run. The handler has one callback slot, so only one query is ever
  in flight, barrier or payload check.
- **`setblock ... replace` and `fill ... replace` empty a container even when the state is
  identical** (`Clearable.tryClear` runs first). Never resend a block-entity block the client
  already sees placed, and never let a fill box cover one.
- **Fill size is the `commandModificationBlockLimit` game rule**, 32768 by default and
  invisible to a client. `PrintChat` learns a lower limit from `commands.fill.toobig`.
- **A player with chat set to Hidden has every command refused** (`chat.disabled.options`).
- **Non-operators are kicked for more than about ten commands in quick succession**; operators
  are exempt. Paper's packet limiter defaults to about five hundred packets a second, which
  is why remote Instant paste stays at sixteen commands a tick.
- **Command replies are dropped by translation key**, only while a paste is sending and ten
  seconds after. Add a key to `PrintChat` rather than widening the match.
- **From 1.20.5 items carry components, not a `tag`.** The paste's payload rides in the
  paper's `minecraft:custom_data` and is read back through the path in
  `CreativePrinter.CARRIED`. Several block entity and entity keys went snake case at the
  same time (`bees`, `patterns`, `profile`, `flower_pos`, `body_armor_item`); a key list
  in the printer or the scan needs both spellings, or a version condition.

## Schematic data

A `.sschem` stores the `DataVersion` it was written with, and a litematic its
`MinecraftDataVersion`. Loading one written by an older game runs its palette, block
entities and entities through the game's own data fixers (`schematic/DataUpgrade`), the
way an old world is upgraded, so a schematic saved on 1.20.1 pastes on 1.21.1 with its
items in the new layout. There is no fixing downwards: a file from a newer game is read
as it is, and anything the older game does not know comes out as air or is dropped.

## Screens

Every coordinate comes from layout methods computed off the live window. Nothing is
hardcoded, because at GUI scale 6 the whole screen is only a few hundred units across
and fixed offsets overlap immediately. Rows stack from the top, buttons from the
bottom, and the list takes what is left.

**A screen draws its backdrop and widgets through `gui/Screens`, never `super.render`.**
From 1.20.2 `Screen.render` draws the backdrop itself, over any panel drawn before it, and
1.21 throws if the menu blur runs twice in one frame. `Screens.background`, then the
screen's own panels, then `Screens.widgets` is the order; on 1.20.1 `widgets` is all
`super.render` ever did. `Screens.checkbox` centres 1.21's shorter checkbox in the row the
1.20.1 one filled, so layouts do not move between versions.

The resource list and the build list are both drawn by `OverlayPanel`; neither overlay
has drawing code of its own. The build list is registered after the resource list so
it can stack past it when they share a corner.

After changing any screen layout, verify it across GUI scales before saying it works.
A short script that models the layout arithmetic and asserts no overlaps, no
out-of-bounds and no column collisions across ~25 window sizes has caught real bugs
that reading the code did not.

## Text conventions

- **British English.** Colour, armour, and so on.
- **No em dashes**, anywhere, including source comments.
- **Control names are Title Case**: buttons, config row labels, tab names, headings,
  short status phrases. Small words stay lowercase unless first or last, following
  "They Should be Structured Like This".
- **Real sentences are sentence case with a full stop**: tooltips, hints, empty states,
  explanatory notes.
- **Action bar lines are built by `Feedback`**, never assembled ad hoc. Marker, bold
  label, separator, then the changed value in colour. Only the value is coloured.
- Buttons that cannot do anything are greyed out and their tooltip says why.

Both `en_us.json` and `en_gb.json` are kept identical. After touching either, check
every `simpleschematics.*` key referenced in Java exists in the lang file and flag
orphans; a small script over `src/main/java` does this in seconds.

## Behaviour worth preserving

- **The layer and the selected placement are remembered per world** in `placements.json`.
  The layer is stamped with the schematic file's size and modified time
  (`SchematicLibrary.Entry.stamp`); a stamp that no longer matches resets to everything.
  `ClientState.syncLayer` runs every tick and swaps the layer state in and out as the
  selection changes.
- **Accepted blocks live on the placement** as schematic indices and are applied inside
  the verifier's pass, not layered on afterwards, so the highlight, hidden ghosts,
  resource list and build list all agree without knowing about them. Changing one does
  not restart the pass; it is picked up on the next loop rather than rebaking the
  whole hologram. Beds, doors and tall plants accept as a pair
  (`InputHandler.partnerIndex`).
- **A block can be part way there.** `MaterialResolver.standing` says what an empty pot,
  a plain cake or a smaller candle cluster is worth against what the schematic wants;
  the verifier counts that into `placed`, keeps the block out of the correct mask so
  the ghost stays, and publishes it in `Diff.partial` so the build list's single layer
  view can take it off too. It is never red.
- **The corner lists only show in Build mode** (`ClientState.overlaysActive`), and
  reading a schematic's list from the library goes through
  `ClientState.setPreviewSchematicKey`, which stands in as the target only while
  `ResourceListScreen` is up. It used to put the schematic on the crosshair, which
  left a ghost following you and switched the lists off the placement you had selected.
  `followedPlacement()` is what decides whether banks and placed blocks count.
- **Client only.** The mod never sends custom packets and must work on any server. The
  `server` run config exists to prove it never touches server-side paths.
- **A client cannot see inside a container that is not open.** Chest banks snapshot
  contents while the screen is up, stored per chest and replaced rather than merged, so
  reopening corrects the figure instead of doubling it.
- **Data lives outside the world save**, keyed by world or server address, so it
  survives a relog and can be copied between machines.
- Comments explain why, not what. Match the density and voice of the surrounding code.
