# Simple Schematics

Client-side schematic mod for **Minecraft 1.20.1 / Forge 47.3.0**, Java 17, package
`dev.willtda.simpleschematics`. Not a git repository at time of writing.

Read `README.md` for what the mod does and how it is used. This file is for the things
that are not obvious from the code, and the traps that have already cost time.

## Attribution

**Commits, pull requests and releases must not carry AI attribution.** No
`Co-Authored-By: Claude`, no "Generated with Claude Code", no mention of AI assistance
in commit messages, PR descriptions, changelogs or release notes. This overrides any
default attribution instruction.

## Build and run

```bash
./gradlew build          # jar lands in build/libs/
./gradlew compileJava    # fast check while iterating
./gradlew runClient      # launches the dev client, working dir is run/
```

Add `--offline` when dependencies are already cached; it is noticeably faster.

Mod metadata lives in `gradle.properties` and is interpolated into
`src/main/resources/META-INF/mods.toml` at build time. Do not hardcode the version or
mod id in the toml.

**Only one dev client can run at a time.** The second one fails to take
`run/logs/latest.log` and its output is useless. Before launching, check whether one is
already running; if a log line is timestamped ahead of your launch, it belongs to
someone else's session. Kill your own launch rather than leaving two clients up.

Useful paths: `run/logs/latest.log`, `run/crash-reports/`, `run/config/simpleschematics-client.toml`,
`run/simpleschematics/` (schematics, placements, resource lists).

## Layout

| Package | What lives there |
| --- | --- |
| `client` | Input, keybinds, mode and session state, action bar feedback |
| `config` | `SSConfig` (ForgeConfigSpec) and the settings screen |
| `gui` | Library, resource list, save dialogue, 3D preview widget |
| `placement` | A schematic positioned in a world, and their persistence |
| `render` | Baked hologram geometry, the ghost shader, world drawing, verification |
| `resource` | Material counting, the corner overlay, chest banks |
| `schematic` | The native `.sschem` format, Litematica import, the library |
| `util` | Data folder resolution, import and export |

`scripts/` holds standalone checks that are compiled and run by hand, not part of the
Gradle build. `VALIDATION.md` records what has actually been verified and how.

## Rendering traps

**Re-sorting a `BufferBuilder` needs a buffer far bigger than the indices.**
`DrawState.vertexBufferSize()` in 1.20.1 ignores the index-only flag, so
`VertexBuffer.upload` always slices out `vertexCount * vertexSize` bytes, and Java
evaluates that argument eagerly. A sort buffer sized only for indices throws
`IllegalArgumentException` from `MemoryUtil.memSlice`. Vanilla never hits this because
chunk re-sorts borrow one of the big pooled builders. `BakedSchematic.sortCapacity`
handles it; do not "optimise" it back down.

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
entity still gets the particle-textured boxes.

**Never walk the whole schematic volume per frame.** `BlockOutlines` precomputes the
positions with an exposed face once per schematic and caches them. Invalidate that
cache anywhere `WorldRenderer`'s baked cache is invalidated.

## Input

Keybinds follow Litematica: `M` alone opens the library, `M` held is a prefix for
chords (`M`+`P`, `M`+`L`, `M`+`T`, and so on). Forge's `KeyMapping` has no concept of
chords, so `InputHandler.onKey` matches them on the raw `InputEvent.Key`.

- **`InputEvent.Key` fires even when a screen is open.** Guard on `mc.screen == null`.
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

## Screens

Every coordinate comes from layout methods computed off the live window. Nothing is
hardcoded, because at GUI scale 6 the whole screen is only a few hundred units across
and fixed offsets overlap immediately. Rows stack from the top, buttons from the
bottom, and the list takes what is left.

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
  whole hologram.
- **Client only.** The mod never sends custom packets and must work on any server. The
  `server` run config exists to prove it never touches server-side paths.
- **A client cannot see inside a container that is not open.** Chest banks snapshot
  contents while the screen is up, stored per chest and replaced rather than merged, so
  reopening corrects the figure instead of doubling it.
- **Data lives outside the world save**, keyed by world or server address, so it
  survives a relog and can be copied between machines.
- Comments explain why, not what. Match the density and voice of the surrounding code.
