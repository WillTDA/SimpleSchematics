package dev.willtda.simpleschematics.printing;

import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.resource.MaterialResolver;
import dev.willtda.simpleschematics.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Printing by hand: Survival, and Creative without operator permission.
 *
 * <p>Every block goes in through the same use packet a right click sends, from
 * where the player is standing, so reach, line of sight, server rules and
 * protection plugins all still apply.</p>
 *
 * <p>It never gives up on the whole build because one block will not go. Each
 * tick it looks only at the blocks within reach, lowest first and then
 * farthest, instead of a slice of the whole build that was mostly out of reach.
 * A block that cannot be placed from here is tried again once the player has
 * moved; one that fails from several places, or that the server keeps
 * refusing, is left for the player. Blocks in the way are left the same way.
 * When nothing in reach can be placed it waits and says why, and carries on as
 * soon as the player walks closer or brings more materials. It used to stop
 * after ten quiet seconds and had to be started again by hand.</p>
 */
final class HandPrinter {

    private static final int FINDS_PER_TICK = 24;
    /** Tries from different spots within reach, or refusals at two apiece, before a block is left for the player. */
    private static final int GIVE_UP_AFTER = 5;
    private static final int TIMEOUTS_BEFORE_STOPPING = 5;
    /** Ticks to wait for the block after the server has answered, if it changed nothing at first. */
    private static final int SETTLE_TICKS = 4;
    private static final long PASS_BUDGET_NANOS = 2_000_000L;
    private static final long INDEX_MASK = (1L << 30) - 1;

    private static boolean applying;

    private final PrintPlan plan;
    private final boolean creative;
    private final boolean chestsOnly;
    private final boolean useBanks;
    private final PrintInventory inventory;
    /** Left for the player: refused, unreachable from everywhere tried, or not placeable by hand. */
    private final BitSet gaveUp = new BitSet();
    /** Something else is standing there. Checked again every pass, so clearing it by hand is noticed. */
    private final BitSet inTheWay = new BitSet();
    private final Map<Integer, Miss> misses = new HashMap<>();
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos passPos = new BlockPos.MutableBlockPos();
    private long[] candidates = new long[1024];

    private int tick;
    private int placed;
    private int delay;
    private int timeouts;
    private Pending pending;

    private int passCursor;
    private int passLeft;
    private int passUnloaded;
    private int passAttention;
    private Map<Item, Integer> passRequired = new HashMap<>();
    private int left;
    private int unloaded;
    private int attention;
    private Map<Item, Integer> required;
    private boolean complete;
    private String failure;

    private Waiting waiting = Waiting.NONE;
    private Item missingItem;

    private enum Waiting { NONE, REACH, MATERIALS }

    private static final class Miss {
        int count;
        long spot = Long.MIN_VALUE;
        int retryAt;
    }

    private static final class Pending {
        final int index;
        final BlockPos pos;
        final BlockState wanted;
        final Item item;
        final BlockState before;
        final int count;
        final int sentAt;
        boolean predicted;
        int answeredAt = -1;

        Pending(int index, BlockPos pos, BlockState wanted, Item item, BlockState before, int count, int sentAt) {
            this.index = index;
            this.pos = pos;
            this.wanted = wanted;
            this.item = item;
            this.before = before;
            this.count = count;
            this.sentAt = sentAt;
        }
    }

    HandPrinter(PrintPlan plan, boolean creative, boolean chestsOnly, boolean useBanks,
                PrintInventory inventory, PrintSurvey survey) {
        this.plan = plan;
        this.creative = creative;
        this.chestsOnly = chestsOnly;
        this.useBanks = useBanks;
        this.inventory = inventory;
        this.left = survey.missing + survey.unloaded;
        this.unloaded = survey.unloaded;
        this.attention = survey.obstructed + survey.outside + survey.banks;
        this.required = new HashMap<>(survey.required());
    }

    static boolean applying() {
        return applying;
    }

    int placed() {
        return placed;
    }

    /** Blocks left for the player at the last full pass. */
    int attention() {
        return attention;
    }

    boolean complete() {
        return complete;
    }

    /** A translation key when printing has to stop, or null. */
    String failure() {
        return failure;
    }

    Component status() {
        if (waiting == Waiting.MATERIALS && missingItem != null) {
            return PrintManager.tr("simpleschematics.print.waiting_materials", missingItem.getDescription());
        }
        if (waiting == Waiting.REACH && left > 0) {
            return unloaded >= left
                    ? PrintManager.tr("simpleschematics.print.waiting_chunks", unloaded)
                    : PrintManager.tr("simpleschematics.print.waiting_reach", left);
        }
        return PrintManager.tr("simpleschematics.print.progress", placed, left);
    }

    /** Stopping, or the server did not answer: whatever the last block used is written off. */
    void writeOff(Minecraft mc) {
        if (pending != null && mc.player != null) {
            inventory.budget.spend(pending.item, Math.max(0, pending.count - PrintInventory.count(mc, pending.item)));
        }
        pending = null;
    }

    void tick(Minecraft mc) {
        tick++;
        advancePass(mc.level);
        if (complete || failure != null) {
            return;
        }
        if (inventory.busy()) {
            inventory.restock(mc, plan.placement, required, chestsOnly);
            return;
        }
        // The player's own hands come first: sneaking, mining or eating holds printing.
        if (mc.player.isUsingItem() || mc.player.isShiftKeyDown() || mc.options.keyAttack.isDown()
                || !mc.player.inventoryMenu.getCarried().isEmpty()) {
            return;
        }
        if (pending != null) {
            confirm(mc);
            return;
        }
        if (delay > 0) {
            delay--;
            return;
        }
        // Someone may have topped a chest up while printing waited.
        if (waiting == Waiting.MATERIALS && tick % 200 == 0) {
            inventory.retryBanks();
        }
        placeNext(mc);
    }

    // ---- choosing the next block ------------------------------------------

    private void placeNext(Minecraft mc) {
        Schematic schematic = plan.schematic;
        ClientLevel level = mc.level;
        Vec3 eye = plan.placement.toLocalPoint(schematic, mc.player.getEyePosition());
        double reach = mc.player.getBlockReach();
        // Generous: the placement search checks the true reach to every face.
        double reachSq = (reach + 1) * (reach + 1);
        int x0 = Math.max(0, Mth.floor(eye.x - reach - 1));
        int y0 = Math.max(0, Mth.floor(eye.y - reach - 1));
        int z0 = Math.max(0, Mth.floor(eye.z - reach - 1));
        int x1 = Math.min(schematic.width() - 1, Mth.floor(eye.x + reach + 1));
        int y1 = Math.min(schematic.height() - 1, Mth.floor(eye.y + reach + 1));
        int z1 = Math.min(schematic.length() - 1, Mth.floor(eye.z + reach + 1));

        int count = 0;
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    int index = schematic.index(x, y, z);
                    if (plan.isAir(index) || gaveUp.get(index) || inTheWay.get(index)) {
                        continue;
                    }
                    double dx = x + 0.5 - eye.x;
                    double dy = y + 0.5 - eye.y;
                    double dz = z + 0.5 - eye.z;
                    double distance = dx * dx + dy * dy + dz * dz;
                    if (distance > reachSq) {
                        continue;
                    }
                    if (count == candidates.length) {
                        candidates = Arrays.copyOf(candidates, count * 2);
                    }
                    // Lowest layer first so supports go in before what stands on
                    // them, then farthest first: a player builds a floor from the far
                    // side back towards themselves, because a block placed nearer
                    // first stands between the eye and the faces the farther ones
                    // need to be placed against.
                    candidates[count++] = ((long) y << 44)
                            | ((long) (16383 - Math.min(16383, (int) (distance * 64))) << 30) | index;
                }
            }
        }
        Arrays.sort(candidates, 0, count);

        missingItem = null;
        int finds = 0;
        for (int i = 0; i < count; i++) {
            int index = (int) (candidates[i] & INDEX_MASK);
            if (plan.accepted(index)) {
                continue;
            }
            BlockState wanted = plan.wanted(index);
            if (PrintPlacement.isSecondaryPart(wanted)) {
                continue;
            }
            BlockPos pos = plan.world(index, scratch);
            if (!PrintPlan.inWorld(level, pos) || !level.hasChunkAt(pos)) {
                continue;
            }
            BlockState actual = level.getBlockState(pos);
            if (PrintPlacement.matches(wanted, actual)) {
                continue;
            }
            Miss miss = misses.get(index);
            if (miss != null && miss.retryAt > tick) {
                continue;
            }
            if (PrintManager.isObstruction(wanted, actual) || plan.isBank(level, pos)) {
                inTheWay.set(index);
                continue;
            }
            if (!validPartner(index, wanted)) {
                gaveUp.set(index);
                continue;
            }
            MaterialResolver.Cost cost = PrintManager.remainingCost(wanted, actual);
            if (cost.isNothing()) {
                // A lone chest waiting for its partner finishes by itself. Anything
                // else with nothing to place, water say, is not a job for a hand.
                if (!PrintPlacement.isPartial(wanted, actual)) {
                    gaveUp.set(index);
                }
                continue;
            }
            BlockPos target = pos.immutable();
            boolean tried = false;
            for (MaterialResolver.Cost.Entry entry : cost.entries()) {
                Item item = entry.item();
                if (!creative && inventory.budget.available(item, PrintInventory.count(mc, item), chestsOnly) == 0) {
                    if (missingItem == null) {
                        missingItem = item;
                    }
                    continue;
                }
                if (finds++ >= FINDS_PER_TICK) {
                    // The rest are looked at next tick, so one busy wall cannot stall a frame.
                    waiting = Waiting.NONE;
                    return;
                }
                tried = true;
                ItemStack stack = PrintInventory.eligibleStack(mc, item, creative);
                PrintPlacement.Attempt attempt = PrintPlacement.find(mc, target, wanted, stack);
                if (attempt != null) {
                    waiting = Waiting.NONE;
                    place(mc, index, target, wanted, item, attempt);
                    return;
                }
            }
            if (tried) {
                miss(mc, index);
            }
        }
        if (missingItem != null && useBanks && !creative
                && inventory.restock(mc, plan.placement, required, chestsOnly)) {
            waiting = Waiting.NONE;
            return;
        }
        waiting = missingItem != null ? Waiting.MATERIALS : Waiting.REACH;
    }

    /**
     * No placement from where the player stands. Counted once per spot, and
     * only when the block is truly within reach, so standing still or walking
     * past just out of reach does not use up a block's chances. Tried again
     * after a short wait in case a neighbour goes in first.
     */
    private void miss(Minecraft mc, int index) {
        long spot = mc.player.blockPosition().asLong();
        Miss miss = misses.computeIfAbsent(index, key -> new Miss());
        if (!PrintInventory.reachable(mc, plan.world(index, new BlockPos.MutableBlockPos()))) {
            miss.retryAt = tick + 10;
            return;
        }
        if (miss.spot != spot) {
            miss.count++;
            miss.spot = spot;
        }
        miss.retryAt = tick + 20 * Math.min(miss.count, 5);
        if (miss.count >= GIVE_UP_AFTER) {
            gaveUp.set(index);
            misses.remove(index);
        }
    }

    /** The server said no, or put something else there. Worth two tries. */
    private void refuse(int index) {
        Miss miss = misses.computeIfAbsent(index, key -> new Miss());
        miss.count += 2;
        miss.retryAt = tick + 40;
        if (miss.count >= GIVE_UP_AFTER) {
            gaveUp.set(index);
            misses.remove(index);
        }
    }

    /** A block went in, so anything leaning on it deserves another go. */
    private void succeeded(int index) {
        misses.remove(index);
        int x = plan.localX(index);
        int y = plan.localY(index);
        int z = plan.localZ(index);
        for (Direction side : Direction.values()) {
            int nx = x + side.getStepX();
            int ny = y + side.getStepY();
            int nz = z + side.getStepZ();
            if (plan.schematic.inBounds(nx, ny, nz)) {
                int neighbour = plan.schematic.index(nx, ny, nz);
                misses.remove(neighbour);
                gaveUp.clear(neighbour);
            }
        }
    }

    private boolean validPartner(int index, BlockState state) {
        BlockPos pos = plan.world(index, new BlockPos.MutableBlockPos()).immutable();
        BlockPos partner = null;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            partner = pos.above();
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
            partner = pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        if (partner == null) {
            return true;
        }
        BlockPos local = plan.placement.toLocal(plan.schematic, partner);
        if (local == null) {
            return false;
        }
        int partnerIndex = plan.schematic.index(local.getX(), local.getY(), local.getZ());
        if (plan.accepted(partnerIndex)) {
            return false;
        }
        BlockState expected = plan.wanted(partnerIndex);
        if (expected.getBlock() != state.getBlock()) {
            return false;
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return expected.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
        }
        return expected.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD
                && expected.getValue(BlockStateProperties.HORIZONTAL_FACING)
                == state.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    // ---- placing ----------------------------------------------------------

    private void place(Minecraft mc, int index, BlockPos pos, BlockState wanted, Item item,
                       PrintPlacement.Attempt attempt) {
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        boolean shift = mc.player.isShiftKeyDown();
        boolean changedPose = false;
        BlockState before = mc.level.getBlockState(pos);
        int count = PrintInventory.count(mc, item);
        InteractionHand hand = inventory.equip(mc, item, creative);
        try {
            if (hand == null) {
                return;
            }
            // Recheck the equipped stack, including NBT, after any inventory swap.
            attempt = PrintPlacement.find(mc, pos, wanted, mc.player.getItemInHand(hand));
            if (attempt == null) {
                miss(mc, index);
                return;
            }
            mc.player.setYRot(attempt.yaw());
            mc.player.setXRot(attempt.pitch());
            mc.player.input.shiftKeyDown = attempt.sneak();
            mc.player.setShiftKeyDown(attempt.sneak());
            changedPose = true;
            mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(attempt.yaw(), attempt.pitch(), mc.player.onGround()));
            if (shift != attempt.sneak()) {
                mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, attempt.sneak()
                        ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                        : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
            }
            applying = true;
            InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, attempt.hit());
            applying = false;
            if (result.consumesAction()) {
                mc.player.swing(hand);
                pending = new Pending(index, pos, wanted, item, before, count, tick);
            } else {
                refuse(index);
            }
        } finally {
            applying = false;
            if (changedPose) {
                mc.player.setYRot(yaw);
                mc.player.setXRot(pitch);
                mc.player.input.shiftKeyDown = shift;
                mc.player.setShiftKeyDown(shift);
                mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, mc.player.onGround()));
                if (shift != attempt.sneak()) {
                    mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, shift
                            ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                            : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
                }
            }
            inventory.restore(mc);
        }
    }

    /**
     * Waits for the server's answer to the last block. The pause between blocks
     * starts counting from the click rather than the answer, so on a normal
     * connection the pace is the setting and not the setting plus the ping.
     */
    private void confirm(Minecraft mc) {
        Pending sent = pending;
        int waited = tick - sent.sentAt;
        if (PrintAcknowledgement.pending(mc.level, sent.pos)) {
            sent.predicted = true;
            if (waited < PrintInventory.responseTicks(mc) * 4) {
                return;
            }
            // No answer at all. One slow block is not a reason to stop; a run of them is.
            writeOff(mc);
            if (++timeouts >= TIMEOUTS_BEFORE_STOPPING) {
                failure = "simpleschematics.print.server_timeout";
                return;
            }
            miss(mc, sent.index);
            delay = SSConfig.INSTANCE.printDelay.get();
            return;
        }
        if (sent.answeredAt < 0) {
            sent.answeredAt = tick;
        }
        BlockState actual = mc.level.getBlockState(sent.pos);
        boolean progress = !actual.equals(sent.before) && (PrintPlacement.matches(sent.wanted, actual)
                || PrintPlacement.isPartial(sent.wanted, actual));
        int settle = sent.predicted ? SETTLE_TICKS : PrintInventory.responseTicks(mc);
        if (!progress && tick - sent.answeredAt < settle) {
            return;
        }
        timeouts = 0;
        inventory.budget.spend(sent.item, Math.max(0, sent.count - PrintInventory.count(mc, sent.item)));
        if (progress) {
            if (PrintPlacement.matches(sent.wanted, actual)) {
                placed++;
            }
            animate(mc, sent.pos);
            succeeded(sent.index);
            inventory.retryBanks();
        } else {
            refuse(sent.index);
        }
        pending = null;
        delay = Math.max(0, SSConfig.INSTANCE.printDelay.get() - waited);
    }

    private static void animate(Minecraft mc, BlockPos pos) {
        if (!SSConfig.INSTANCE.printParticles.get()) {
            return;
        }
        Vec3 centre = Vec3.atCenterOf(pos);
        for (int i = 0; i < 7; i++) {
            double angle = i * Math.PI * 2 / 7;
            mc.level.addParticle(ParticleTypes.END_ROD, centre.x + Math.cos(angle) * .6, pos.getY() + .1,
                    centre.z + Math.sin(angle) * .6, 0, .045, 0);
        }
    }

    // ---- keeping count ----------------------------------------------------

    /**
     * Walks the whole plan a little each tick to keep the totals true, whatever
     * the player or the server has done since. It is also what notices the
     * build is finished, and what hands a cleared obstruction back.
     */
    private void advancePass(ClientLevel level) {
        long deadline = System.nanoTime() + PASS_BUDGET_NANOS;
        int looked = 0;
        while (passCursor < plan.size()) {
            if ((++looked & 255) == 0 && System.nanoTime() > deadline) {
                return;
            }
            int index = plan.target(passCursor++);
            if (plan.accepted(index)) {
                continue;
            }
            BlockState wanted = plan.wanted(index);
            // The top of a door or the head of a bed comes with the other half.
            if (PrintPlacement.isSecondaryPart(wanted)) {
                continue;
            }
            plan.world(index, passPos);
            if (!PrintPlan.inWorld(level, passPos)) {
                passAttention++;
                continue;
            }
            if (!level.hasChunkAt(passPos)) {
                passLeft++;
                passUnloaded++;
                MaterialResolver.costOf(wanted).addTo(passRequired);
                continue;
            }
            BlockState actual = level.getBlockState(passPos);
            if (PrintPlacement.matches(wanted, actual)) {
                inTheWay.clear(index);
                continue;
            }
            if (gaveUp.get(index)) {
                passAttention++;
                continue;
            }
            if (PrintManager.isObstruction(wanted, actual) || plan.isBank(level, passPos)) {
                inTheWay.set(index);
                passAttention++;
                continue;
            }
            inTheWay.clear(index);
            MaterialResolver.Cost cost = PrintManager.remainingCost(wanted, actual);
            if (cost.isNothing()) {
                if (!PrintPlacement.isPartial(wanted, actual)) {
                    passAttention++;
                }
                continue;
            }
            passLeft++;
            cost.addTo(passRequired);
        }
        left = passLeft;
        unloaded = passUnloaded;
        attention = passAttention;
        required = passRequired;
        passLeft = 0;
        passUnloaded = 0;
        passAttention = 0;
        passRequired = new HashMap<>();
        passCursor = 0;
        if (left == 0 && pending == null) {
            complete = true;
        }
    }
}
