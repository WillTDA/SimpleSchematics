package dev.willtda.simpleschematics.printing;

import dev.willtda.simpleschematics.client.ClientState;
import dev.willtda.simpleschematics.client.EditMode;
import dev.willtda.simpleschematics.client.Feedback;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.placement.PlacementManager;
import dev.willtda.simpleschematics.resource.MaterialResolver;
import dev.willtda.simpleschematics.schematic.Schematic;
import dev.willtda.simpleschematics.schematic.SchematicLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** A resumable client-side printer. The server remains the authority for every change. */
public final class PrintManager {
    public static final PrintManager INSTANCE = new PrintManager();
    private enum Phase { IDLE, SCANNING, CONFIRMING, BUILDING }
    private record Target(BlockPos pos, BlockState state, int index) { }
    private Phase phase = Phase.IDLE;
    private Placement placement;
    private Schematic schematic;
    private ClientLevel level;
    private String signature;
    private long generation;
    private boolean creative;
    private boolean operator;
    private boolean chestsOnly;
    private boolean useBanks;
    private int scanIndex;
    private int scanPredictionTicks;
    private int existing;
    private int obstructionCount;
    private int unavailable;
    private int placed;
    private int cursor;
    private int delay;
    private int stalledTicks;
    private int examined;
    private int skipped;
    private int pendingWait;
    private int pendingCount;
    private BlockState pendingBefore;
    private Item pendingItem;
    private Target pending;
    private CreativePrinter creativePrinter;
    private PrintInventory inventory = new PrintInventory();
    private Map<Item, Integer> restockRequired = Map.of();
    private final List<Target> targets = new ArrayList<>();
    private final Map<Item, Integer> required = new LinkedHashMap<>();
    private final Set<Integer> failed = new HashSet<>();
    private final Map<String, ResourceBudget<Item>> withdrawalBudgets = new LinkedHashMap<>();

    private PrintManager() { }

    public boolean isRunning() { return phase != Phase.IDLE; }

    public void requestPrint() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (phase != Phase.IDLE) pause();
        if (mc.gameMode.getPlayerMode() != GameType.CREATIVE && mc.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            error("mode");
            return;
        }
        Placement selected = PlacementManager.INSTANCE.selected();
        SchematicLibrary.Entry entry = selected == null ? null : SchematicLibrary.INSTANCE.byKey(selected.schematicKey());
        if (entry == null || entry.get() == null) {
            error("select");
            return;
        }
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) {
            error("inventory_busy");
            return;
        }
        placement = selected;
        schematic = entry.get();
        level = mc.level;
        signature = signature(entry);
        creative = mc.player.isCreative();
        operator = mc.player.hasPermissions(2);
        chestsOnly = SSConfig.INSTANCE.printSource.get() == SSConfig.PrintSource.LINKED_CHESTS;
        useBanks = SSConfig.INSTANCE.printSource.get() != SSConfig.PrintSource.INVENTORY;
        targets.clear();
        required.clear();
        failed.clear();
        String budgetKey = dev.willtda.simpleschematics.util.DataPaths.currentWorldKey() + ":"
                + mc.player.getUUID() + ":" + placement.id() + ":" + signature + ":" + chestsOnly;
        if (withdrawalBudgets.size() > 128) withdrawalBudgets.clear();
        inventory = new PrintInventory(withdrawalBudgets.computeIfAbsent(budgetKey, key -> {
            ResourceBudget<Item> budget = new ResourceBudget<>();
            budget.reserveExisting(PrintInventory.counts(mc));
            return budget;
        }));
        scanIndex = existing = obstructionCount = unavailable = placed = skipped = cursor = delay = stalledTicks = examined = 0;
        pending = null;
        scanPredictionTicks = 0;
        creativePrinter = null;
        phase = Phase.SCANNING;
        info("checking");
    }

    private String signature(SchematicLibrary.Entry entry) {
        return entry.stamp() + ":" + placement.origin().asLong() + ":" + placement.rotation()
                + ":" + placement.mirror() + ":" + level.dimension().location();
    }

    private boolean valid(Minecraft mc) {
        SchematicLibrary.Entry entry = placement == null ? null : SchematicLibrary.INSTANCE.byKey(placement.schematicKey());
        return mc.player != null && mc.player.isAlive() && mc.level == level && mc.gameMode != null
                && mc.player.isCreative() == creative && (creative || mc.gameMode.getPlayerMode() == GameType.SURVIVAL)
                && ClientState.INSTANCE.isEnabled() && ClientState.INSTANCE.mode() == EditMode.PRINT
                && PlacementManager.INSTANCE.selected() == placement && entry != null
                && signature.equals(signature(entry));
    }

    public void tick() {
        if (phase == Phase.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        if (!valid(mc)) { pause(); return; }
        if (phase == Phase.CONFIRMING) return;
        if (mc.screen != null && !inventory.ownsScreen(mc)) { pause(); return; }
        if (mc.isPaused()) return;
        if (phase == Phase.SCANNING) { scan(mc); return; }
        if (mc.player.hasPermissions(2) != operator) { pause(); return; }
        if (creativePrinter != null) {
            creativePrinter.tick();
            switch (creativePrinter.status()) {
                case COMPLETE -> {
                    placed = creativePrinter.placed();
                    skipped = creativePrinter.skipped();
                    finish(skipped == 0 ? "complete" : "creative_partial", placed, skipped);
                }
                case BLOCKED, NO_PERMISSION -> {
                    Feedback.error(Component.literal(creativePrinter.detail()));
                    reset();
                }
                case CANCELLED -> pause();
                default -> { }
            }
            return;
        }
        if (inventory.busy()) {
            inventory.restock(mc, placement, restockRequired, chestsOnly);
            return;
        }
        if (mc.player.isUsingItem() || mc.player.isShiftKeyDown() || mc.options.keyAttack.isDown()) return;
        if (!mc.player.inventoryMenu.getCarried().isEmpty()) { pause(); return; }
        if (pending != null) { confirmPlacement(mc); return; }
        if (delay-- > 0) return;
        stalledTicks++;
        int checked = 0;
        while (checked++ < Math.min(512, targets.size())) {
            if (targets.isEmpty()) break;
            examined++;
            if (cursor >= targets.size()) cursor = 0;
            Target target = targets.get(cursor++);
            if (failed.contains(target.index()) || !mc.level.hasChunkAt(target.pos())) continue;
            BlockState actual = mc.level.getBlockState(target.pos());
            if (PrintPlacement.matches(target.state(), actual) || placement.isAccepted(target.index())) continue;
            if (!PrintInventory.reachable(mc, target.pos())) continue;
            if (isObstruction(target.state(), actual)) {
                if (placement.isBank(dev.willtda.simpleschematics.resource.Banks.canonical(mc.level, target.pos()))) {
                    error("linked_obstruction", target.pos().toShortString()); reset(); return;
                }
                if (!operator || !SSConfig.INSTANCE.printReplaceBlocks.get()) {
                    error("changed_obstruction", target.pos().toShortString());
                    reset();
                    return;
                }
                // Replacement is explicit, operator-only, and acknowledged before spending an item.
                mc.player.connection.sendCommand("setblock " + coords(target.pos()) + " minecraft:air replace");
                pending = target;
                pendingBefore = actual;
                pendingItem = null;
                pendingWait = PrintInventory.responseTicks(mc) * 4;
                return;
            }
            if (!validPartner(target)) { failed.add(target.index()); continue; }
            MaterialResolver.Cost cost = remainingCost(target.state(), actual);
            for (MaterialResolver.Cost.Entry entry : cost.entries()) {
                Item item = entry.item();
                if (!creative && inventory.budget.available(item, PrintInventory.count(mc, item), chestsOnly) == 0) continue;
                ItemStack stack = PrintInventory.eligibleStack(mc, item, creative);
                PrintPlacement.Attempt attempt = PrintPlacement.find(mc, target.pos(), target.state(), stack);
                if (attempt == null) continue;
                place(mc, target, item, attempt);
                return;
            }
        }
        // A full pass with no action is allowed to wait for movement, neighbour updates or more items.
        if (examined < targets.size() || stalledTicks % 20 != 0) return;
        examined = 0;
        Map<Item, Integer> remaining = remainingCosts(mc);
        int left = remainingBlocks(mc);
        if (left == 0) { finish("complete", placed); return; }
        restockRequired = remaining;
        if (useBanks && !creative && inventory.restock(mc, placement, restockRequired, chestsOnly)) return;
        if (stalledTicks >= 200) {
            finish("partial", placed, left);
        } else {
            info("waiting", left);
        }
    }

    private void scan(Minecraft mc) {
        int stop = (int) Math.min(schematic.volume(), (long) scanIndex + 8192);
        for (; scanIndex < stop; scanIndex++) {
            int x = scanIndex % schematic.width();
            int z = scanIndex / schematic.width() % schematic.length();
            int y = scanIndex / (schematic.width() * schematic.length());
            BlockState state = schematic.getBlockState(x, y, z).mirror(placement.mirror()).rotate(placement.rotation());
            if (state.isAir() || placement.isAccepted(scanIndex)) continue;
            BlockPos pos = placement.toWorld(schematic, x, y, z);
            if (mc.level.isOutsideBuildHeight(pos) || !mc.level.getWorldBorder().isWithinBounds(pos)) {
                error("bounds", pos.toShortString()); reset(); return;
            }
            if (!mc.level.hasChunkAt(pos)) { unavailable++; continue; }
            if (PrintAcknowledgement.pending(mc.level, pos)) {
                if (++scanPredictionTicks > 200) { error("server_timeout"); reset(); }
                return;
            }
            scanPredictionTicks = 0;
            BlockState actual = mc.level.getBlockState(pos);
            if (PrintPlacement.matches(state, actual)) { existing++; continue; }
            if (placement.isBank(dev.willtda.simpleschematics.resource.Banks.canonical(mc.level, pos))) {
                error("linked_obstruction", pos.toShortString()); reset(); return;
            }
            Target target = new Target(pos, state, scanIndex);
            targets.add(target);
            if (isObstruction(state, actual)) obstructionCount++;
            remainingCost(state, actual).addTo(required);
        }
        if (scanIndex < schematic.volume()) return;
        if (unavailable > 0) { error("unloaded", unavailable); reset(); return; }
        if (obstructionCount > 0 && (!operator || !SSConfig.INSTANCE.printReplaceBlocks.get())) {
            error(operator ? "replacement_disabled" : "obstructions", obstructionCount);
            reset();
            return;
        }
        if (targets.isEmpty() && !(creative && operator && SSConfig.INSTANCE.printEntities.get() && !schematic.entities().isEmpty())) {
            finish("already_complete");
            return;
        }
        phase = Phase.CONFIRMING;
        warnings(mc, 0);
    }

    private void warnings(Minecraft mc, int step) {
        if (!valid(mc)) { reset(); return; }
        if (step == 0 && (placement.hasPrintProgress(signature) || (existing > 0 && !targets.isEmpty()))) {
            confirm(mc, "continue", () -> warnings(mc, 1), existing, targets.size());
        } else if (step <= 1 && !creative && SSConfig.INSTANCE.printWarnSurvival.get()) {
            confirm(mc, "survival_warning", () -> warnings(mc, 2));
        } else if (step <= 2 && creative && !operator) {
            confirm(mc, "creative_no_op", () -> warnings(mc, 3));
        } else if (step <= 3 && obstructionCount > 0) {
            confirm(mc, "replace_warning", () -> warnings(mc, 4), obstructionCount);
        } else if (step <= 4 && !creative && SSConfig.INSTANCE.printWarnMissing.get()) {
            Map<Item, Integer> available = PrintInventory.counts(mc);
            if (chestsOnly) available.replaceAll((item, count) -> inventory.budget.available(item, count, true));
            if (useBanks) {
                for (var bank : placement.banks().entrySet()) {
                    if (!PrintInventory.reachable(mc, bank.getKey()) || !mc.level.hasChunkAt(bank.getKey())) continue;
                    for (var item : bank.getValue().entrySet()) {
                        ResourceLocation id = ResourceLocation.tryParse(item.getKey());
                        if (id != null) available.merge(BuiltInRegistries.ITEM.get(id), item.getValue(), Integer::sum);
                    }
                }
            }
            int missing = ResourceBudget.missing(required, available);
            if (missing > 0) confirm(mc, "missing_warning", () -> warnings(mc, 5), missing);
            else warnings(mc, 5);
        } else if (step <= 5 && useBanks && !creative && !placement.bankPositions().isEmpty()) {
            confirm(mc, "banks_warning", () -> warnings(mc, 6));
        } else if (step <= 6 && !creative && (!schematic.entities().isEmpty() || !schematic.blockEntities().isEmpty())) {
            confirm(mc, "survival_data", () -> warnings(mc, 7));
        } else {
            mc.setScreen(null);
            phase = Phase.BUILDING;
            placement.markPrintStarted(signature);
            PlacementManager.INSTANCE.markDirty();
            PlacementManager.INSTANCE.saveIfDirty();
            if (creative && operator) {
                creativePrinter = new CreativePrinter(placement, schematic, SSConfig.INSTANCE.printReplaceBlocks.get(),
                        SSConfig.INSTANCE.printEntities.get(), SSConfig.INSTANCE.printContents.get());
            }
            info("started", targets.size());
        }
    }

    private void confirm(Minecraft mc, String key, Runnable yes, Object... args) {
        long promptGeneration = ++generation;
        mc.setScreen(new ConfirmScreen(accepted -> {
            if (phase != Phase.CONFIRMING || generation != promptGeneration) return;
            mc.setScreen(null);
            if (accepted && valid(mc)) yes.run(); else reset();
        }, tr("title"), tr(key, args)));
    }

    private static boolean applying;

    public static boolean suppressPlacementSound() {
        return applying && !SSConfig.INSTANCE.printSounds.get();
    }

    private void place(Minecraft mc, Target target, Item item, PrintPlacement.Attempt attempt) {
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        boolean shift = mc.player.isShiftKeyDown();
        boolean changedPose = false;
        pendingBefore = mc.level.getBlockState(target.pos());
        pendingCount = PrintInventory.count(mc, item);
        InteractionHand hand = inventory.equip(mc, item, creative);
        try {
            if (hand == null) return;
            // Recheck the equipped stack, including NBT, after any inventory swap.
            attempt = PrintPlacement.find(mc, target.pos(), target.state(), mc.player.getItemInHand(hand));
            if (attempt == null) return;
            mc.player.setYRot(attempt.yaw());
            mc.player.setXRot(attempt.pitch());
            mc.player.input.shiftKeyDown = attempt.sneak();
            mc.player.setShiftKeyDown(attempt.sneak());
            changedPose = true;
            mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(attempt.yaw(), attempt.pitch(), mc.player.onGround()));
            if (shift != attempt.sneak()) mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player,
                    attempt.sneak() ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
            applying = true;
            InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, attempt.hit());
            applying = false;
            if (result.consumesAction()) {
                mc.player.swing(hand);
                pending = target;
                pendingItem = item;
                pendingWait = PrintInventory.responseTicks(mc) * 4;
            } else {
                failed.add(target.index());
            }
        } finally {
            applying = false;
            if (changedPose) {
                mc.player.setYRot(yaw);
                mc.player.setXRot(pitch);
                mc.player.input.shiftKeyDown = shift;
                mc.player.setShiftKeyDown(shift);
                mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, mc.player.onGround()));
                if (shift != attempt.sneak()) mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player,
                        shift ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
            }
            inventory.restore(mc);
        }
    }
    private void confirmPlacement(Minecraft mc) {
        pendingWait--;
        boolean awaiting = pendingItem != null && PrintAcknowledgement.pending(mc.level, pending.pos());
        if (awaiting && pendingWait > 0) return;
        if (awaiting) {
            error("server_timeout");
            reset();
            return;
        }
        BlockState actual = mc.level.getBlockState(pending.pos());
        boolean progress = !actual.equals(pendingBefore) && (pendingItem == null ? actual.isAir()
                : PrintPlacement.matches(pending.state(), actual) || PrintPlacement.isPartial(pending.state(), actual));
        if (!progress && pendingWait > 0) return;
        if (pendingItem != null) {
            inventory.budget.spend(pendingItem, Math.max(0, pendingCount - PrintInventory.count(mc, pendingItem)));
        }
        if (progress) {
            if (pendingItem != null) {
                if (PrintPlacement.matches(pending.state(), actual)) placed++;
                animate(mc, pending.pos(), actual);
            }
            stalledTicks = 0;
            examined = 0;
            inventory.retryBanks();
        } else {
            failed.add(pending.index());
        }
        pending = null;
        pendingItem = null;
        delay = SSConfig.INSTANCE.printDelay.get();
    }

    private static void animate(Minecraft mc, BlockPos pos, BlockState state) {
        if (SSConfig.INSTANCE.printParticles.get()) {
            Vec3 centre = Vec3.atCenterOf(pos);
            for (int i = 0; i < 7; i++) {
                double angle = i * Math.PI * 2 / 7;
                mc.level.addParticle(ParticleTypes.END_ROD, centre.x + Math.cos(angle) * .6, pos.getY() + .1,
                        centre.z + Math.sin(angle) * .6, 0, .045, 0);
            }
        }
    }

    private Map<Item, Integer> remainingCosts(Minecraft mc) {
        Map<Item, Integer> remaining = new LinkedHashMap<>();
        for (Target target : targets) {
            if (failed.contains(target.index()) || placement.isAccepted(target.index())) continue;
            BlockState actual = mc.level.getBlockState(target.pos());
            if (!PrintPlacement.matches(target.state(), actual)) remainingCost(target.state(), actual).addTo(remaining);
        }
        return remaining;
    }

    private int remainingBlocks(Minecraft mc) {
        int remaining = 0;
        for (Target target : targets) {
            if (!placement.isAccepted(target.index()) && !PrintPlacement.matches(target.state(), mc.level.getBlockState(target.pos()))) remaining++;
        }
        return remaining;
    }

    private boolean validPartner(Target target) {
        BlockState state = target.state();
        BlockPos partner = null;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) partner = target.pos().above();
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
            partner = target.pos().relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        if (partner == null) return true;
        BlockPos local = placement.toLocal(schematic, partner);
        if (local == null || placement.isAccepted(schematic.index(local.getX(), local.getY(), local.getZ()))) return false;
        BlockState expected = schematic.getBlockState(local).mirror(placement.mirror()).rotate(placement.rotation());
        if (expected.getBlock() != state.getBlock()) return false;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return expected.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
        }
        return expected.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD
                && expected.getValue(BlockStateProperties.HORIZONTAL_FACING) == state.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    static MaterialResolver.Cost remainingCost(BlockState desired, BlockState actual) {
        Map<Item, Integer> cost = new LinkedHashMap<>();
        MaterialResolver.costOf(desired).addTo(cost);
        for (var entry : MaterialResolver.standing(desired, actual).entries()) {
            cost.computeIfPresent(entry.item(), (item, count) -> Math.max(0, count - entry.amount()));
        }
        return new MaterialResolver.Cost(cost.entrySet().stream().filter(e -> e.getValue() > 0)
                .map(e -> new MaterialResolver.Cost.Entry(e.getKey(), e.getValue())).toList());
    }

    private static boolean isObstruction(BlockState wanted, BlockState actual) {
        return !actual.canBeReplaced() && !PrintPlacement.matches(wanted, actual) && !PrintPlacement.isPartial(wanted, actual);
    }

    private static String coords(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
    private static Component tr(String key, Object... args) { return Component.translatable("simpleschematics.print." + key, args); }
    private static void info(String key, Object... args) { Feedback.value(tr("title"), tr(key, args)); }
    private static void error(String key, Object... args) { Feedback.error(tr(key, args)); }

    private void finish(String key, Object... args) {
        if (key.equals("partial") || key.equals("creative_partial")) info(key, args);
        else Feedback.success(tr(key, args));
        reset();
    }

    public void pause() {
        if (!isRunning()) return;
        info("paused");
        reset();
    }

    public void reset() {
        Minecraft mc = Minecraft.getInstance();
        generation++;
        if (pending != null && pendingItem != null && mc.player != null && mc.level == level) {
            inventory.budget.spend(pendingItem, Math.max(0, pendingCount - PrintInventory.count(mc, pendingItem)));
        }
        inventory.close(mc);
        if (creativePrinter != null) creativePrinter.cancel();
        creativePrinter = null;
        phase = Phase.IDLE;
        pending = null;
        targets.clear();
        required.clear();
        failed.clear();
    }
}
