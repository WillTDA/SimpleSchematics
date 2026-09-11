package dev.willtda.simpleschematics.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.willtda.simpleschematics.config.ConfigScreen;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.gui.LibraryScreen;
import dev.willtda.simpleschematics.gui.ResourceListScreen;
import dev.willtda.simpleschematics.gui.SaveSchematicScreen;
import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.placement.PlacementManager;
import dev.willtda.simpleschematics.resource.Banks;
import dev.willtda.simpleschematics.resource.ResourceListManager;
import dev.willtda.simpleschematics.render.SchematicVerifier;
import dev.willtda.simpleschematics.schematic.Schematic;
import dev.willtda.simpleschematics.schematic.SchematicLibrary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All of the in world input. The rules are deliberately few:
 *
 * <ul>
 *   <li>M opens the library. Held down it is a prefix: M and P for placements,
 *       M and L for the resource list, M and T to switch the mod off.</li>
 *   <li>Ctrl and scroll swaps between Scan and Build.</li>
 *   <li>Shift and scroll steps through the layers in Build.</li>
 *   <li>Ctrl, Shift and scroll switches the mod on or off, as long as the
 *       activation item is in your hand.</li>
 *   <li>In Scan, left click sets the start corner and right click sets the end.</li>
 *   <li>In Build, your normal place block button drops the hologram.</li>
 * </ul>
 */
public final class InputHandler {

    private InputHandler() {
    }

    private static final ClientState STATE = ClientState.INSTANCE;

    // ---- the M chords -----------------------------------------------------

    /** True between pressing and releasing the menu key. */
    private static boolean menuHeld;
    /** Set when a chord fires, so letting go of M does not also open the library. */
    private static boolean menuUsed;

    /**
     * Runs before the keys reach anything else, which is what lets a chord take
     * a key back off vanilla. M and T would otherwise open the chat window on
     * the way past.
     */
    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || mc.level == null) {
            menuHeld = false;
            return;
        }
        InputConstants.Key pressed = InputConstants.getKey(event.getKey(), event.getScanCode());
        if (pressed.equals(InputConstants.UNKNOWN)) {
            return;
        }

        if (Keybinds.MENU.isActiveAndMatches(pressed)) {
            onMenuKey(mc, event.getAction());
            // Anything else bound to this key keeps its press unless we are
            // claiming it, which is the escape hatch for a minimap on the same
            // key. Chord keys below are always taken, or M and T opens chat.
            if (SSConfig.INSTANCE.menuKeyBlocksOtherMods.get()) {
                swallow(mc, pressed);
            }
            return;
        }

        // Alt-tabbing away while M is down means the release never arrives, so
        // trust the keyboard over the flag before treating anything as a chord.
        if (menuHeld && !isMenuKeyDown(mc)) {
            menuHeld = false;
        }
        if (!menuHeld || event.getAction() != InputConstants.PRESS) {
            return;
        }
        for (KeyMapping chord : Keybinds.chords()) {
            if (chord.isActiveAndMatches(pressed)) {
                menuUsed = true;
                swallow(mc, pressed);
                runChord(mc, chord);
                return;
            }
        }
    }

    private static boolean isMenuKeyDown(Minecraft mc) {
        InputConstants.Key key = Keybinds.MENU.getKey();
        return key.getType() == InputConstants.Type.KEYSYM
                && key.getValue() != InputConstants.UNKNOWN.getValue()
                && InputConstants.isKeyDown(mc.getWindow().getWindow(), key.getValue());
    }

    private static void onMenuKey(Minecraft mc, int action) {
        if (action == InputConstants.PRESS) {
            menuHeld = true;
            menuUsed = false;
            return;
        }
        if (action != InputConstants.RELEASE) {
            return;
        }
        boolean used = menuUsed;
        menuHeld = false;
        menuUsed = false;
        // A bare tap is the main menu. Anything else was a chord already dealt with.
        if (!used) {
            mc.setScreen(new LibraryScreen(null));
        }
    }

    private static void runChord(Minecraft mc, KeyMapping chord) {
        if (chord == Keybinds.TOGGLE_MOD) {
            announceMod(STATE.toggleEnabled());
            return;
        }
        if (chord == Keybinds.SETTINGS) {
            mc.setScreen(new ConfigScreen(null));
            return;
        }
        if (!STATE.isEnabled()) {
            Feedback.error(Component.translatable("simpleschematics.feedback.mod_is_off"));
            return;
        }
        if (chord == Keybinds.PLACEMENTS) {
            mc.setScreen(new LibraryScreen(null, LibraryScreen.Tab.PLACEMENTS));
        } else if (chord == Keybinds.RESOURCE_LIST_SCREEN) {
            openResourceListScreen(mc);
        } else if (chord == Keybinds.RESOURCE_LIST) {
            toggleResourceList();
        } else if (chord == Keybinds.TOGGLE_RENDERING) {
            Feedback.state(Component.translatable("simpleschematics.feedback.rendering"),
                    STATE.toggleRenderHolograms());
        } else if (chord == Keybinds.TOGGLE_BOX) {
            boolean on = !SSConfig.INSTANCE.hologramOutline.get();
            SSConfig.INSTANCE.hologramOutline.set(on);
            SSConfig.SPEC.save();
            Feedback.state(Component.translatable("simpleschematics.feedback.outline"), on);
        } else if (chord == Keybinds.HIGHLIGHT) {
            boolean on = SchematicVerifier.INSTANCE.toggle();
            Feedback.state(Component.translatable("simpleschematics.feedback.highlight"), on);
            if (on) {
                reportDiff = true;
            }
        }
    }

    /**
     * Takes a key back from every binding that wanted it, this mod's included.
     * The click has already been recorded by the time Forge tells us about it,
     * so the only way to cancel it is to drain it before the next tick reads it.
     */
    private static void swallow(Minecraft mc, InputConstants.Key pressed) {
        for (KeyMapping mapping : mc.options.keyMappings) {
            if (pressed.equals(mapping.getKey())) {
                while (mapping.consumeClick()) {
                    // drained
                }
            }
        }
        KeyMapping.set(pressed, false);
    }

    // ---- scrolling --------------------------------------------------------

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null) {
            return;
        }

        double raw = event.getScrollDelta();
        if (raw == 0.0D) {
            return;
        }

        boolean ctrl = Screen.hasControlDown();
        boolean shift = Screen.hasShiftDown();

        // Both modifiers switch the mod itself on or off, so this one branch has
        // to work while the mod is off. Holding the activation item is all it asks.
        if (ctrl && shift) {
            if (!STATE.holdingActivationItem()) {
                return;
            }
            announceMod(STATE.toggleEnabled());
            event.setCanceled(true);
            return;
        }

        if (!STATE.shortcutsActive()) {
            return;
        }

        int direction = raw > 0 ? 1 : -1;
        if (SSConfig.INSTANCE.invertScroll.get()) {
            direction = -direction;
        }

        if (ctrl) {
            STATE.cycleMode();
            event.setCanceled(true);
            return;
        }

        if (shift && STATE.mode() == EditMode.BUILD) {
            stepLayer(direction);
            event.setCanceled(true);
        }
    }

    private static void stepLayer(int direction) {
        Schematic schematic = STATE.targetSchematic();
        if (schematic == null) {
            Feedback.error(Component.translatable("simpleschematics.feedback.no_target"));
            return;
        }
        if (STATE.shiftLayer(direction)) {
            announceLayer(schematic);
            // pitch climbs with height, so you can hear roughly where you are
            float progress = STATE.layerView() == ClientState.LayerView.ALL
                    ? 0.0F
                    : (float) STATE.layer() / Math.max(1, schematic.height() - 1);
            Feedback.playLayerTick(0.9F + progress * 1.1F);
        } else {
            Feedback.playLayerLimit();
        }
    }

    private static void announceLayer(Schematic schematic) {
        if (STATE.layerView() == ClientState.LayerView.ALL) {
            Feedback.value(Component.translatable("simpleschematics.feedback.layer"),
                    Component.translatable("simpleschematics.layer.all")
                            .withStyle(ChatFormatting.AQUA));
        } else {
            Feedback.value(Component.translatable("simpleschematics.feedback.layer"),
                    Component.literal((STATE.layer() + 1) + " / " + schematic.height())
                            .withStyle(ChatFormatting.AQUA));
        }
    }

    private static void announceMod(boolean on) {
        Feedback.state(Component.translatable("simpleschematics.feedback.mod"), on);
    }

    // ---- clicks -----------------------------------------------------------

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (event.isUseItem()) {
            // Clicking anything that is not a block clears it rather than
            // leaving it set, so a villager opened straight after a chest
            // cannot have its trades recorded into that chest's bank.
            lastUsedBlock = mc.hitResult instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK
                    ? hit.getBlockPos().immutable()
                    : null;
            lastUsedAge = 0;
        }
        if (!STATE.isEnabled() || !STATE.isHoldingTool()) {
            return;
        }

        if (STATE.mode() == EditMode.SCAN) {
            // Attack is the start corner and use is the end, unless you play
            // with your mouse buttons switched, in which case the game sees
            // them the other way round and this puts them back.
            boolean swapped = SSConfig.INSTANCE.swapScanCorners.get();
            if (event.isAttack()) {
                setCorner(!swapped);
                event.setSwingHand(false);
                event.setCanceled(true);
            } else if (event.isUseItem()) {
                setCorner(swapped);
                event.setSwingHand(false);
                event.setCanceled(true);
            }
            return;
        }

        if (STATE.mode() != EditMode.BUILD || !event.isUseItem()) {
            return;
        }

        // Shift and right click on a chest hands it to the build you have
        // selected, so what is inside counts towards the resource list.
        if (Screen.hasShiftDown() && toggleBank(mc)) {
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
        }

        if (STATE.hasPending()) {
            commitPending();
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /**
     * Marks or unmarks the container you are pointing at as a material bank.
     *
     * @return true if this was a container and the click has been dealt with
     */
    private static boolean toggleBank(Minecraft mc) {
        BlockPos looking = lookedAtBlock();
        if (looking == null || mc.level == null || !Banks.isContainer(mc.level, looking)) {
            return false;
        }
        Placement placement = PlacementManager.INSTANCE.selected();
        if (placement == null) {
            Feedback.error(Component.translatable("simpleschematics.feedback.bank_needs_placement"));
            return true;
        }
        BlockPos pos = Banks.canonical(mc.level, looking);
        boolean now = placement.toggleBank(pos);
        PlacementManager.INSTANCE.markDirty();
        if (now) {
            Feedback.success(Component.translatable("simpleschematics.feedback.bank_added",
                    Component.literal(placement.displayName())));
        } else {
            Feedback.info(Component.translatable("simpleschematics.feedback.bank_removed"));
        }
        return true;
    }

    /**
     * Left click sets the start corner, right click sets the end.
     *
     * <p>Vanilla re-fires the use item binding every tick it is held down, and
     * cancelling the event means the usual cooldown never gets set, so holding
     * right click used to re-stamp the end corner twenty times a second and
     * bury the hotbar in messages. Landing on the corner it is already on is
     * therefore treated as nothing having happened.</p>
     */
    /**
     * The block the last right click landed on. A container screen does not
     * carry the position it was opened from, so this is how a banked chest is
     * recognised once its screen is up.
     */
    private static BlockPos lastUsedBlock;
    private static int lastUsedAge;

    /**
     * How long that position survives with no screen up.
     *
     * <p>A container screen never arrives in the tick that asked for it. The
     * click goes to the server and the screen comes back, which is at least a
     * round trip away, so on any real connection there is always at least one
     * tick where the click has happened and no screen is open yet.</p>
     */
    private static final int USE_GRACE_TICKS = 20;

    public static BlockPos lastUsedBlock() {
        return lastUsedBlock;
    }

    private static void setCorner(boolean start) {
        BlockPos pos = lookedAtBlock();
        if (pos == null) {
            Feedback.error(Component.translatable("simpleschematics.feedback.nothing_there"));
            return;
        }
        ScanSelection selection = STATE.selection();
        if (pos.equals(start ? selection.start() : selection.end())) {
            return;
        }
        if (start) {
            selection.setStart(pos);
        } else {
            selection.setEnd(pos);
        }

        Component label = Component.translatable(start
                ? "simpleschematics.feedback.start_corner"
                : "simpleschematics.feedback.end_corner");
        Component coords = Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
                .withStyle(start ? ChatFormatting.GREEN : ChatFormatting.RED);

        if (selection.isComplete()) {
            var size = selection.size();
            Feedback.value(label, coords.copy().append(Component.literal("  ")
                    .append(Component.literal(size.getX() + " x " + size.getY() + " x " + size.getZ())
                            .withStyle(ChatFormatting.GRAY))));
        } else {
            Feedback.value(label, coords);
        }
    }

    /** A longer reach than the vanilla one, because selections are often big. */
    public static BlockPos lookedAtBlock() {
        Minecraft mc = Minecraft.getInstance();
        Entity camera = mc.getCameraEntity();
        if (camera == null || mc.level == null) {
            return null;
        }
        double reach = SSConfig.INSTANCE.maxSelectionReach.get();
        Vec3 eye = camera.getEyePosition(1.0F);
        Vec3 end = eye.add(camera.getViewVector(1.0F).scale(reach));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    /**
     * Where the schematic you are dragging would land. Sits on top of the face
     * you are pointing at, or floats at the end of your reach if you are
     * pointing at the sky.
     */
    public static BlockPos pendingOrigin() {
        Minecraft mc = Minecraft.getInstance();
        Entity camera = mc.getCameraEntity();
        if (camera == null || mc.level == null) {
            return null;
        }
        double reach = Math.min(SSConfig.INSTANCE.maxSelectionReach.get(), 64);
        Vec3 eye = camera.getEyePosition(1.0F);
        Vec3 end = eye.add(camera.getViewVector(1.0F).scale(reach));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera));

        if (hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().relative(hit.getDirection());
        }
        if (!SSConfig.INSTANCE.snapPlacementToGrid.get()) {
            return null;
        }
        return BlockPos.containing(end);
    }

    private static void commitPending() {
        String key = STATE.pendingSchematicKey();
        BlockPos origin = pendingOrigin();
        if (key == null || origin == null) {
            Feedback.error(Component.translatable("simpleschematics.feedback.nowhere_to_place"));
            return;
        }
        var entry = SchematicLibrary.INSTANCE.byKey(key);
        String name = entry != null && entry.get() != null ? entry.get().meta().name : key;

        Placement placement = new Placement(key, name, origin);
        // Carry the corner list across from the schematic you were holding, so
        // turning it on before you put the build down is not thrown away.
        placement.setResourceList(STATE.resourceListVisible());
        PlacementManager.INSTANCE.add(placement);
        PlacementManager.INSTANCE.saveIfDirty();
        STATE.cancelPending();

        Feedback.success(Component.translatable("simpleschematics.feedback.placed",
                Component.literal(name), Component.literal(origin.getX() + ", " + origin.getY() + ", " + origin.getZ())));
    }

    /**
     * Selects whichever build you are looking at, when that is switched on.
     *
     * <p>The ray is cast against the placement boxes rather than the world, so
     * looking at a hologram standing in open air still picks it. Off by default,
     * because it takes the choice out of your hands.</p>
     */
    private static void autoSelect(Minecraft mc) {
        if (!SSConfig.INSTANCE.autoSelectLookedAt.get() || STATE.mode() != EditMode.BUILD
                || mc.screen != null || STATE.hasPending()) {
            return;
        }
        Entity camera = mc.getCameraEntity();
        if (camera == null) {
            return;
        }
        double reach = Math.min(SSConfig.INSTANCE.maxSelectionReach.get(), 128);
        Vec3 eye = camera.getEyePosition(1.0F);
        Vec3 end = eye.add(camera.getViewVector(1.0F).scale(reach));

        Placement best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Placement placement : PlacementManager.INSTANCE.current()) {
            if (!placement.visible()) {
                continue;
            }
            SchematicLibrary.Entry entry = SchematicLibrary.INSTANCE.byKey(placement.schematicKey());
            Schematic schematic = entry == null ? null : entry.get();
            if (schematic == null) {
                continue;
            }
            AABB box = placement.bounds(schematic);
            if (box.clip(eye, end).isEmpty()) {
                continue;
            }
            // Ranked by how near the build itself is, not by where the ray
            // happens to strike it. Standing inside a large build would
            // otherwise score it by its far wall and lose to a smaller one
            // further away.
            double distance = distanceSquared(box, eye);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = placement;
            }
        }
        if (best != null && best != PlacementManager.INSTANCE.selected()) {
            PlacementManager.INSTANCE.select(best);
        }
    }

    /** Nearest point of the box to a point, and zero when the point is inside it. */
    private static double distanceSquared(AABB box, Vec3 point) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0D), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0D), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0D), point.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    /** Set when the highlight is switched on, cleared once the counts are shown. */
    private static boolean reportDiff;

    /**
     * Feeds the diff only the placements that are visible and close enough to
     * matter, so a folder full of distant builds costs nothing.
     *
     * <p>The resource list wants the selected placement checked whatever mode
     * you are in and whether or not the highlight is drawing, because what is
     * already standing comes off what it says you still need. That one skips
     * the distance test: far away it simply finds nothing loaded and keeps what
     * the last pass saw.</p>
     */
    private static void tickVerifier(Minecraft mc) {
        boolean highlighting = STATE.mode() == EditMode.BUILD && SchematicVerifier.INSTANCE.isEnabled();
        Placement followed = SSConfig.INSTANCE.countPlacedBlocks.get() && !STATE.hasPending()
                ? PlacementManager.INSTANCE.selected()
                : null;
        if (!highlighting && followed == null) {
            return;
        }
        double maxDistance = SSConfig.INSTANCE.hologramRenderDistance.get();

        List<Placement> active = new ArrayList<>();
        Map<String, Schematic> schematics = new HashMap<>();
        for (Placement placement : PlacementManager.INSTANCE.current()) {
            if (placement != followed && !(highlighting && placement.visible())) {
                continue;
            }
            SchematicLibrary.Entry entry = SchematicLibrary.INSTANCE.byKey(placement.schematicKey());
            Schematic schematic = entry == null ? null : entry.get();
            if (schematic == null) {
                continue;
            }
            if (placement != followed
                    && !placement.bounds(schematic).inflate(maxDistance).contains(mc.player.position())) {
                continue;
            }
            active.add(placement);
            schematics.put(placement.schematicKey(), schematic);
        }
        SchematicVerifier.INSTANCE.tick(active, schematics);
    }

    // ---- keybinds ---------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        // Only meaningful for as long as the screen that click opened is up,
        // plus the wait for it to arrive. Clearing on the first screenless tick
        // threw the position away before the chest could open, so the bank had
        // nothing to record against and a banked chest never kept its contents.
        if (mc.screen == null) {
            if (++lastUsedAge > USE_GRACE_TICKS) {
                lastUsedBlock = null;
            }
        } else {
            lastUsedAge = 0;
        }

        // The chord keys are handled the moment they arrive. Anything still
        // queued here was a bare press, which is not a shortcut on its own.
        drainChords();

        if (!STATE.isEnabled()) {
            drainPlainKeys();
            PlacementManager.INSTANCE.tick();
            SchematicVerifier.INSTANCE.clear();
            return;
        }

        autoSelect(mc);
        tickVerifier(mc);

        // report once the first pass has actually produced something to report
        if (reportDiff && SchematicVerifier.INSTANCE.hasResult()) {
            reportDiff = false;
            SchematicVerifier.Diff diff = SchematicVerifier.INSTANCE.summary();
            if (diff.isFinished()) {
                Feedback.success(Component.translatable("simpleschematics.feedback.verify_clean"));
            } else {
                Feedback.info(Component.translatable("simpleschematics.feedback.verify",
                        diff.wrongCount(), diff.extraCount(), diff.missing()));
            }
        }

        while (Keybinds.CONFIRM.consumeClick()) {
            handleConfirm(mc);
        }

        while (Keybinds.CLEAR.consumeClick()) {
            if (STATE.hasPending()) {
                STATE.cancelPending();
                Feedback.info(Component.translatable("simpleschematics.feedback.placement_cancelled"));
            } else if (!STATE.selection().isEmpty()) {
                STATE.selection().clear();
                Feedback.info(Component.translatable("simpleschematics.feedback.selection_cleared"));
            }
        }

        while (Keybinds.LAYER_NEXT.consumeClick()) {
            stepLayer(1);
        }

        while (Keybinds.LAYER_PREVIOUS.consumeClick()) {
            stepLayer(-1);
        }

        while (Keybinds.ROTATE.consumeClick()) {
            Placement placement = PlacementManager.INSTANCE.selected();
            if (placement != null) {
                placement.rotateClockwise();
                PlacementManager.INSTANCE.markDirty();
                Feedback.value(Component.translatable("simpleschematics.feedback.rotation"),
                        Component.literal(placement.rotation().name())
                                .withStyle(ChatFormatting.AQUA));
            } else {
                Feedback.error(Component.translatable("simpleschematics.feedback.no_placement"));
            }
        }

        while (Keybinds.MIRROR.consumeClick()) {
            Placement placement = PlacementManager.INSTANCE.selected();
            if (placement != null) {
                placement.cycleMirror();
                PlacementManager.INSTANCE.markDirty();
                Feedback.value(Component.translatable("simpleschematics.feedback.mirror"),
                        Component.literal(placement.mirror().name())
                                .withStyle(ChatFormatting.AQUA));
            } else {
                Feedback.error(Component.translatable("simpleschematics.feedback.no_placement"));
            }
        }

        PlacementManager.INSTANCE.tick();
        ResourceListManager.INSTANCE.tick();

        // Written back about once a second, so scrolling through the modes does
        // not rewrite the config file on every notch.
        if (++modeFlushCounter >= 20) {
            modeFlushCounter = 0;
            STATE.flushMode();
        }
    }

    private static int modeFlushCounter;

    private static void handleConfirm(Minecraft mc) {
        if (STATE.mode() == EditMode.SCAN) {
            if (!STATE.selection().isComplete()) {
                Feedback.error(Component.translatable(SSConfig.INSTANCE.swapScanCorners.get()
                        ? "simpleschematics.feedback.need_both_corners_swapped"
                        : "simpleschematics.feedback.need_both_corners"));
                return;
            }
            mc.setScreen(new SaveSchematicScreen(null));
        } else if (STATE.hasPending()) {
            commitPending();
        } else {
            mc.setScreen(new LibraryScreen(null));
        }
    }

    private static void openResourceListScreen(Minecraft mc) {
        if (STATE.targetSchematicKey() == null) {
            Feedback.error(Component.translatable("simpleschematics.feedback.no_target"));
            return;
        }
        mc.setScreen(new ResourceListScreen(null));
    }

    private static void toggleResourceList() {
        if (STATE.targetSchematicKey() == null) {
            Feedback.error(Component.translatable("simpleschematics.feedback.resource_needs_target"));
            return;
        }
        Feedback.state(Component.translatable("simpleschematics.feedback.resource_list"),
                STATE.toggleResourceList());
    }

    /** Bare presses of a chord key mean nothing, so they never reach a tick. */
    private static void drainChords() {
        for (KeyMapping mapping : Keybinds.chords()) {
            while (mapping.consumeClick()) {
                // drained
            }
        }
        while (Keybinds.MENU.consumeClick()) {
            // drained
        }
    }

    /** Stops queued presses firing all at once when the mod is switched back on. */
    private static void drainPlainKeys() {
        for (KeyMapping mapping : Keybinds.plain()) {
            while (mapping.consumeClick()) {
                // drained
            }
        }
    }
}
