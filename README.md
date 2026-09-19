# Simple Schematics

**Guided building made easy.**

A deliberately small schematic mod for **Minecraft Forge 1.20.1**, by WillTDA.

Client side only. It is never required on the server, so you can use it on any vanilla or modded server you can already join.

## Why it exists

Litematica and its Forge ports do the job, but the everyday parts are awkward: saving a region takes several menus, the hologram fights you, and your placements and material list vanish every time you relog. This mod keeps the same three ideas (scan a region, show it as a hologram, tell you what you need) and tries to make each one boring and reliable.

## Building it

You need a JDK 17 on your PATH. Then:

```
./gradlew build
```

The jar lands in `build/libs/`. Drop it in your `mods` folder.

For a development client, run `./gradlew runClient`. On Windows, use `gradlew.bat build` or `gradlew.bat runClient`.

To run the regression checks on Windows, generate the current runtime classpath with `gradlew.bat --offline --init-script scripts/print_test_classpath.gradle writePrintTestClasspath`, then run `scripts/check_print_placement.ps1` under Java 17 and `python scripts/check_print_ui.py`. These headless checks cover block states, material accounting, NBT, transforms, settings persistence and layout; gameplay and networking require an in-game check.

## How you use it

Hold the activation item (a stick by default, changeable in the config) and the controls wake up.

**M** is the way in. On its own it opens the library. Held down it becomes a prefix, the way Litematica does it, and the rest of the mod hangs off it:

| Keys | What it does |
| --- | --- |
| **M** | Open the library |
| **M** + **P** | Open the placements |
| **M** + **L** | Open the resource list |
| **M** + **O** | Show or hide the resource list overlay |
| **M** + **N** | Show or hide the build list |
| **M** + **H** | Show or hide the mismatch highlight |
| **M** + **R** | Show or hide the holograms |
| **M** + **B** | Show or hide the outline box |
| **M** + **T** | Switch the mod on or off |
| **M** + **C** | Open the settings |

None of the second column does anything on its own, so the mod costs you exactly one key. All of it is rebindable in the vanilla Controls screen.

The mod switch, hologram visibility, mismatch highlighting and mode are saved preferences shared by the config menu and shortcuts. Leaving or joining a world does not reset them. Resource and build list visibility is remembered per placement, with separate saved defaults while positioning a new schematic.

**Ctrl and scroll** swaps between Scan, Build and Print mode. Whichever one you were last in is where you come back the next time you log in. **Ctrl, Shift and scroll** switches the mod on or off, and unlike everything else that one works while the mod is off, so long as the activation item is in your hand.

### Scan mode

- Right click a block to set the start corner.
- Left click a block to set the end corner. **Swap the Scan Corner Buttons** in the settings turns them round if you would rather start with a left click.
- The two corners are different colours and a box is drawn between them.
- Press **Enter** to save. You are asked for a name, an optional description, and whether to include entities such as armour stands, and container contents.
- Press **\\** to clear the selection.

### Build mode

- Press **M** for the library. Each schematic gets a live 3D preview on a slow turntable. Drag it to spin it yourself, flick it and let go to send it coasting, scroll to zoom, right click to put it back. Leave it alone for a second and a half and it picks the turntable back up. **Resource List** there reads what a schematic needs without picking it up, so whatever you had selected in the world is exactly as you left it when you come back.
- Choose one, then point where you want it and press your normal **use block** key. Whatever you have that bound to is what works.
- **.** rotates and **,** mirrors the selected placement.
- **Shift and scroll**, or **Page Up** and **Page Down**, walks the selected build up one layer at a time, with a note block hi-hat that rises in pitch as you climb. It stops at the top, and scrolling back down past the bottom returns the whole build, so there is no separate layer mode to switch on. Only the build you have selected is sliced; every other one in the world stays whole. The layer is remembered with the build, along with which build you had selected, so a relog puts you back where you were. If the schematic file changed while you were away the layer means nothing any more and the build comes back whole.

### Print mode

Place a schematic in Build mode, switch to **Print** with **Ctrl and scroll**, and press **Enter** or right click with the activation item. The placements screen also has a **Print** button. Print checks the entire build before starting. **\** pauses it; opening another screen, changing placement or mode, dying or leaving the world also stops it. Starting again checks the actual world and asks whether to continue a partial build. It never silently resumes after a relog.

- **Creative:** operators get a fast paste, batching runs of identical blocks into vanilla commands. Larger builds are spread across ticks to keep the server responsive. Saved entities and container contents can be restored, with independent settings for each. Without operator permission, Print uses ordinary reachable item placement and explains that saved entities and block data cannot be restored.
- **Survival:** blocks appear in a steady sequence with their own placement sounds and a short rising sparkle. Print draws from **Inventory**, **Linked Chests**, or **Both**; Both uses carried items first. It opens reachable linked storage and transfers stacks through the normal inventory menu. Spare items stay in your inventory. Chest-only printing protects supplies you already owned and remembers withdrawals when you pause and continue in the same game session. After restarting the game, choose Both to use carried leftovers.
- **Server rules:** Survival shows a warning that automated building is bannable on most servers and asks you to double-check the rules before proceeding. Print does not bypass server permissions or protection plugins.
- **Missing materials:** the confirmation explains that Yes builds as much as possible. Cached chest totals are estimates; the printer rechecks the real menu and never creates resources from those estimates. Nearby visible chests, barrels, shulker boxes, hoppers and dispensers are supported. Locked, distant or inaccessible containers cannot supply a build.
- **Obstructions:** non-operators must clear conflicting blocks themselves. Operator replacement is off by default and requires a separate confirmation when enabled. Linked material banks are protected from replacement. Existing correct blocks and accepted differences are left alone, and air in the schematic does not clear the landscape.
- **Placement rules:** supports, attachment faces, orientation, gravity, cactus neighbours, doors, beds, double chests and stackable blocks are checked before placing. Survival printing respects reach and line of sight; walk around the build to expose the next section. If nothing can progress for a while, Print stops with the remaining block count, ready to continue.

Some states require manual work: fluid placement, adding water to an already dry block, tilling, growing crops, powered or otherwise non-placeable states, and unsupported modded interactions. Survival never restores captured entities, container inventories or block-entity data, and will not unpack items carrying block-entity data. The mismatch highlight and build list show what still needs attention. Print always checks the whole placement; the layer view only changes the hologram and lists.

**Settings → Print** controls material sources, pace, sounds, particles, the Survival and missing-resource warnings, operator replacement, entities and container contents. The defaults enable warnings, use both material sources and keep replacement off.

Creative data transfer restores long text and full inventories through a temporary Creative item and checks the server's response. Payloads over 1 MiB, executable captured data and unsupported riding relationships are omitted and reported. Entity attempts are remembered in `print-entities/` so continuing a build cannot repeatedly summon the same captured entity. A server refusal stops the paste; correct blocks are rechecked on the next run.

### Checking your work

Press **M** and **H** to switch the mismatch highlight on or off. While it is on, the mod quietly compares the build against the world a slice at a time and marks what is out of place:

- **Red** means there is a block there but it is the wrong one.
- **Amber** means there is a block there that the schematic does not want at all.
- Blocks you have already placed correctly stop being drawn as hologram, so what is left standing is exactly what you still have to do.

Toggling it on reports the totals above the hotbar. The highlight respects layer mode, so stepping through layers shows you only that layer's mistakes.

The comparison checks block type and placement properties such as facing, axis and slab half. Expected states follow the selected rotation and mirror. Connections, redstone power, waterlogging and whether a door is open are ignored unless **Match the full block state** is on. A door hung from the other side, which the game records as facing the other way with the hinge swapped, counts as the same door. A shut trapdoor matches whichever edge it hinges on, since that only shows once it is open; a trapdoor the schematic has open must hinge on the same side.

A block that is only part of the way there is neither right nor wrong. An empty flower pot where a potted sapling should be, a cake waiting for its candle, two candles where the schematic wants four: what is standing comes off both lists and is never painted red, and the ghost stays up to show what is still to go in. A pot with the wrong plant in it, or a cake with a bite out of it, has to come out first, so that is still wrong.

The comparison loops rather than listening for block updates, so it repairs itself after a chunk reload or a server correction. It costs a fixed budget of blocks per tick, which you can lower in the config if a very large build costs you frames.

#### Accepting a block as built

Sometimes you mean to differ from the schematic: a window you decided against, a door moved one block over, a tree you would rather build around. **Ctrl, alt and right click** a block with the stick out to accept it as it stands. From the next pass it counts as placed, whatever the schematic wanted there: the ghost for it goes away, it is never red or amber, and it comes off both lists. The same click on an accepted block hands it back to the schematic. A block that already matches is refused, since accepting it would change nothing.

It looks in the build you have selected first and then in any other visible build the block falls inside, so one stray block in a neighbouring placement does not mean reselecting it. A bed, a door or a tall plant is one thing in two blocks, so either half accepts both. Accepted blocks are saved with the placement and survive a relog.

### Hologram appearance

Each build gets a box around it, which you can toggle with **M** and **B**. **Select what you look at** in the settings picks whichever build you are pointing at instead of choosing one from the list, ranked by which is actually nearest you; it is off by default.

**Outline every block** draws an edge around each block in the hologram so the grid stays readable. The edges are grown a hair to keep them off the ghost surfaces, and only blocks with a face you can actually see, within a configurable distance of you, are drawn.

Open **Mods > Simple Schematics > Config**. New installations use 65% opacity, fading within two blocks of the camera, block edges at 20% within four blocks of you, breathing ghosts on a one and a half second breath, and no outer hologram box. The Scan target marker is on by default. Scan selection boxes appear only while holding the tool, and F1 hides the mod's world overlays.

Existing opacity and outline preferences are retained. Choose **Reset Hologram Appearance** in the Build section to apply the current defaults. You can then adjust **Hologram Opacity**, **Fade Nearby Ghosts** and **Fade Distance** separately. Lowering opacity to zero hides the block ghosts; outlines and mismatch highlights have their own switches.

**Breathing Ghosts** pulses the hologram opacity slowly, from full down to a dip you choose and back, so the ghosts are easy to pick out from the real blocks around them. Off by default. **Breath Depth** is how far it dips, as a fraction of the hologram opacity, and **Breath Length** is how many seconds one breath takes. It keeps time from the clock rather than the game tick, so it does not stutter with the frame rate.

Ghost faces use one transparent shader, sort from the camera, keep the world's depth test, and do not write into the world's depth buffer. A small depth bias reduces flicker against real blocks without shifting the schematic. Hidden blocks and filtered layers are also removed from face culling so exposed edges remain visible.

With a shader pack loaded through Oculus or Iris the mod's own shader is ignored, so the ghosts go through the game's translucent block pass instead and the pack lights them like glass. They write depth on that path, because a pack's later passes read the depth buffer back to decide what each pixel is and a ghost that left none behind was painted over with sky wherever the sky was behind it. The near fade is lost on that path; opacity, breathing and everything else is not. **Shader Pack Compatibility** in the settings picks the path by hand if the mod guesses wrong.

### Resource list

- **M** and **O** shows or hides the overlay for whichever build you are working on. That choice is remembered per placement and written out with it, so a half finished build still has its list waiting the next time you log in. Both corner lists belong to Build and Print modes; switch to Scan and they step aside until you come back.
- It sits in the bottom right by default at half size, refreshes five times a second, and drops rows the moment you have gathered them. The build it is following is named above it; **Show the Build's Name** in the settings turns that off.
- Sorted with the biggest shortfall first, shown as `1,234` with `19 × 64 + 18` beside it. A single stack reads `64 + 18`.
- Items disappear from the list as you collect them. That includes your inventory, offhand, the stack on your cursor, your ender chest, and any chest you currently have open.
- Blocks already standing correctly in the placement come off the total too, so a half built wall only asks for the half that is missing. It uses the same comparison as the mismatch highlight, whether or not that is switched on, and can only see loaded chunks: a build you have not been near this session reads as untouched until you visit it. **Count Blocks Already Placed** in the settings turns it off.
- **Shift and right click a chest** with the stick out to hand it to the build you have selected. Banked chests are boxed in amber while you build, and whatever is inside counts towards the list even when the chest is shut. Shift and right click again to take it back off. Both halves of a double chest count as one.
- Left click a row to tick it off, right click to clear that, and shift and scroll to correct a total by hand. Hold ctrl while scrolling to move in stacks.
- Position, size, width and row count are all in the config. It defaults to the bottom right and shrinks itself to fit rather than running off the screen, at every GUI scale.

### Build list

The resource list says what to fetch. The build list says what to put down, right here, right now.

- **M** and **N** shows or hides it for the build you have selected, or the schematic you are holding. Like the resource list, that choice is remembered per placement.
- With the whole build showing it lists every block still to place across the build. Step through the layers with **shift and scroll** and it narrows to the layer in front of you, headed `Layer 3 of 12`, so you can work a layer to done and move up.
- It is counted from the same comparison the mismatch highlight uses. A block that is there but wrong is still on the list, because the right one is not. Your inventory makes no difference to it: something you are carrying but have not placed is still to place.
- It sits in the top right by default, in the same style as the resource list. Send both to the same corner and they stack rather than overlap. Corner, offset and row count are in the config; size, width, panel darkness and the stack breakdown follow the resource list settings.

## Your data

Everything lives in one folder, `.minecraft/simpleschematics/` unless you point `dataDirectory` somewhere else in the config. Put it in OneDrive or Dropbox and your laptop and your PC share the same schematics, placements and progress.

```
simpleschematics/
  schematics/       your .sschem files, and any .litematic you drop in
  placements.json   where each schematic sits, per world and per server, with its chests and accepted blocks
  resource-lists/   how far through each build you are
  print-entities/   Creative entity attempts, preventing duplicates when continuing
```

Placements are keyed by world or server address and written as soon as they change, so relogging does not lose them. The config screen also has **Export** and **Import** buttons that move the whole lot as a zip: Import opens your system's file dialogue and takes a `.zip`, a `.litematic` or a `.sschem`, several at once if you like. Dragging any of those straight onto that screen does the same.

## Litematica files

`.litematic` files load directly. Multi region schematics are flattened into one volume. The embedded thumbnail is reused for the library gallery when there is one. **Convert** in the library rewrites a litematic as a native `.sschem`, which loads faster.

## Two things worth knowing

**Container contents on servers.** A client side mod can only see inside a chest you have actually opened. Scanning a room full of unopened chests records the chests but not what is in them. The save dialogue says so at the time.

**Rendering limits.** Ghosts render baked block models. Blocks the game draws with a block entity renderer have no model to bake, so the vanilla ones are drawn from their real model parts and textures instead: chests with the lid shut, beds in their colour, signs and hanging signs without their text, banners with their patterns, shulker boxes, heads, decorated pots with their sherds, and conduits. A modded block of that kind is stood in for by boxes of its own shape in its particle texture, so it is the right size in the right place. Fluids and saved entities are not drawn. Large builds appear a few sections at a time. Overlapping transparent surfaces may still show sorting artefacts, especially where multiple placements overlap.

## Licence

Apache License 2.0. See `LICENSE`.

The `.litematic` reader was written from a description of the format. No code from Litematica or any of its ports is used here, and this project is not affiliated with those projects or with Mojang.
