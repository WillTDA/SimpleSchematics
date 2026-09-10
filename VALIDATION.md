# Rendering repair validation

This revision starts from the supplied Claude source archive. Mod identity stays
Simple Schematics 1.0.0, by WillTDA, Apache-2.0, for Forge 1.20.1. It is a source
revision, not a verified installable release.

## Completed here

- Java 17 parsed all 34 mod source files without syntax errors. This does **not**
  type-check Minecraft or Forge APIs.
- Compiled and ran `SectionVisibilityTest` against the actual visibility helper.
  Checked hidden blocks, restoring visibility, layer cut faces, volume bounds,
  all 24 section neighbourhoods in an asymmetric grid, and combined changes.
- Compiled and linked the actual GLSL 150 vertex and fragment shaders in a
  headless OpenGL context using Mesa llvmpipe. Checked the declared uniforms and
  Minecraft's BLOCK vertex layout with 15 pixel/depth checks: opacity at zero,
  5%, 35% and 100%; cutout holes; texture alpha; close and distant fading; gallery
  fog bypass; terrain occlusion; unchanged world depth; and coplanar depth bias.
- Checked the two language files have matching keys and UK English text, with no
  em dashes and no missing static translation keys used by the source.

## Shader pack compatibility

- Confirmed against the real artefacts that `ShaderPackCompat` asks for a class
  that exists. Downloaded Oculus `mc1.20.1-1.8.0`, `1.7.0` and `1.6.15a` and
  found `net/irisshaders/iris/api/v0/IrisApi.class` in all three, not the older
  `net.coderbot` package.
- Ran `scripts/IrisLookupCheck` against each of those jars. It asserts
  `getInstance` is static and no-arg and returns the API type, and that
  `isShaderPackInUse` is an instance method returning boolean. All three pass,
  and the absent-class branch is exercised by the second candidate name.
- **Not verified in game.** Whether the fallback draw actually puts the ghosts
  on screen under a loaded shader pack has not been observed. That needs a
  client with Oculus and a pack enabled, which has not been run here.

## Build limitation

The Gradle wrapper distribution is not cached here and its download failed with
`java.net.SocketException: Network is unreachable`. Forge dependencies are also
unavailable locally. A full Forge compilation, reobfuscated JAR and Minecraft
client run have **not** been completed. The source-level and OpenGL checks above
cannot establish Forge API compatibility, in-game appearance or mod-pack
compatibility.

On a computer with JDK 17 and access to the Gradle and Forge repositories, run:

```sh
./gradlew build
./gradlew runClient
```

On Windows, use `gradlew.bat` in place of `./gradlew`.

The intended target is Minecraft 1.20.1 with Forge 47.3.0. A successful build puts
the mod JAR in `build/libs/`.

## Repeating the local checks

From the project root with JDK 17:

```sh
mkdir -p build/verification
javac -d build/verification scripts/SectionVisibilityTest.java src/main/java/dev/willtda/simpleschematics/render/SectionVisibility.java
java -cp build/verification SectionVisibilityTest
java scripts/ParseSources.java
```

On Linux with Python 3, libEGL, libGL and a surfaceless OpenGL driver:

```sh
python3 scripts/check_shader.py
```

## Checks still needed in Minecraft

1. Scan a small asymmetric build containing logs on different axes, stairs,
   slabs, glass, leaves and a flower. Place its ghost in open air and against
   matching real blocks. Walk around and through it. Check the corners remain
   aligned and that opacity and near fade are readable at different distances.
2. Test all rotations and mirrors. Check correctly oriented real blocks hide
   their ghosts, wrong-facing stairs remain visible, and strict matching also
   notices connection and waterlogging differences.
3. Use a build crossing X, Y and Z section boundaries at blocks 15/16 and 31/32.
   Step through layers and back to the whole build. Add and remove real blocks
   on the boundaries. Check exposed faces reappear after verification/rebaking.
4. Switch to Scan mode, put the stick away, and confirm the mod's marker and
   selection boxes disappear. Test the optional target marker and F1. Minecraft's
   own normal block selection outline is separate from the mod's marker.
5. Check the library preview, resize the window, and try every available GUI
   scale. Scroll the config near the header and footer and check clipped controls
   cannot be clicked through them. Test **Reset hologram appearance**.
6. Reload resources with F3+T, change dimension, relog and reconnect to a server
   without this mod. Confirm ghosts rebuild and placements/resource progress
   still load. Those data formats have not changed in this revision.
7. Repeat with the intended mod pack, graphics modes and shader packs. Large or
   overlapping transparent placements may still show ordering artefacts.

## Scope and remaining renderer limits

This repair covers baked block models. It does not add fluid, block entity or
entity ghost rendering. A saved chest, sign or armour stand may therefore have
no complete ghost representation. Large models are still baked over several
frames, and translucent ordering is per section and per placement rather than a
global sort of every face in the scene.

The camera stage was checked against the official [Forge 1.20.1 GameRenderer
patch](https://github.com/MinecraftForge/MinecraftForge/blob/1.20.1/patches/minecraft/net/minecraft/client/renderer/GameRenderer.java.patch).
World ghosts retain the terrain stage's camera pose; the 1.20.1 `AFTER_LEVEL`
event passes a different pose and must not be substituted without compensating
for that difference.
