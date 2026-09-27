package dev.willtda.simpleschematics.printing;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.schematic.Schematic;
import dev.willtda.simpleschematics.util.DataPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
//? if >=1.21 {
/*import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.component.CustomData;
*///?}
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.PaintingVariant;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Operator-only Creative pasting through the server's ordinary command interface.
 *
 * <p>Blocks go in waves. Each wave walks the plan once, lowest layer first,
 * and sends every block that can stand where it is going, grouping identical
 * blocks into boxes so one fill command places a whole wall. Then it waits for
 * the server to catch up, checks what actually went in, and starts another
 * wave for anything that could not stand the first time round: the torch whose
 * wall was in the same wave, the sand whose floor was. Most builds are done in
 * two or three waves. When a few waves in a row change nothing, what is left
 * is left for the player and reported rather than stopping the paste.</p>
 *
 * <p>The old paste walked the whole volume three times at a fixed rate,
 * grouped blocks only along one row, sent four commands a tick and waited for
 * each one to be seen, so a large build took minutes before it was checked
 * twice more. Instant paste lifts the pace to as many commands as the server
 * will take; the steady pace keeps a shared server responsive.</p>
 *
 * <p>Saved container contents and entities follow the blocks. What has been
 * placed and restored is written to a journal as it happens, so a paste that
 * is interrupted picks up exactly where it stopped: a container placed but not
 * yet filled still gets filled, and an entity is never summoned twice.</p>
 */
public final class CreativePrinter {

    public enum Status { RUNNING, COMPLETE, NO_PERMISSION, CANCELLED }

    private enum Stage { BLOCKS, SETTLE, VERIFY, WAIT, DATA, DONE }

    private enum Step { NOTHING_LEFT, WAITING, SENT }

    // Chat command packets reject anything longer than 256 characters.
    private static final int MAX_COMMAND = 256;
    /** Where the payload sits in the player's own data while it rides in the offhand. */
    //? if >=1.21 {
    /*private static final String CARRIED = "Inventory[{Slot:-106b}].components.\"minecraft:custom_data\".ss_print";
    *///?} else {
    private static final String CARRIED = "Inventory[{Slot:-106b}].tag.ss_print";
    //?}
    /** A hive's residents, renamed when 1.20.5 moved the game's data to snake case. */
    //? if >=1.21 {
    /*private static final String BEES = "bees";
    *///?} else {
    private static final String BEES = "Bees";
    //?}
    /** The server's commandModificationBlockLimit by default. A lower limit is learnt from its reply. */
    private static final int FILL_LIMIT = 32768;
    private static final int ACK_TICKS = 100;
    /** Waves in a row that place nothing more before the rest is left for the player. */
    private static final int STUCK_WAVES = 3;
    private static final int WAIT_TICKS = 40;
    private static final long VERIFY_BUDGET_NANOS = 4_000_000L;

    private final PrintPlan plan;
    private final Schematic schematic;
    private final ClientLevel level;
    private final boolean replace;
    private final boolean entities;
    private final boolean contents;
    private final boolean instant;
    private final int commandsPerTick;
    private final int payloadsPerTick;
    private final long budgetNanos;
    private final String signature;
    private final PrintJournal journal;

    /** Sent in the current wave, so a box never covers a block twice. */
    private final BitSet sent = new BitSet();
    private final BitSet everSent = new BitSet();
    private final BitSet placedHere = new BitSet();
    /** Left for the player after several waves, or too long to send as a command. */
    private final BitSet abandoned = new BitSet();
    private final BitSet queued = new BitSet();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final ArrayDeque<Payload> data = new ArrayDeque<>();
    private final ArrayDeque<Integer> entityQueue = new ArrayDeque<>();
    private final List<Integer> entityLater = new ArrayList<>();
    /** Instant payloads go unchecked one by one; they are marked done once the server has had them all. */
    private final List<UUID> doneAfterBarrier = new ArrayList<>();

    private Status status = Status.RUNNING;
    private String detail = "";
    private Stage stage = Stage.BLOCKS;
    private int tick;
    private int cursor;
    private int stuck;
    private int lastLeft = Integer.MAX_VALUE;
    private int lastPlaced;
    private int refusedBeforeData = -1;
    private int waitUntil;
    private int verifyCursor;
    private int verifyLeft;
    private int verifyUnloaded;
    private int verifyAttention;
    private boolean abandoning;
    private int left;
    private int unloaded;
    private int attention;
    private int omitted;
    private int fillLimit = FILL_LIMIT;
    private boolean journalWarned;

    private long barrierToken;
    private boolean barrierAnswered;
    private int barrierSentAt;
    private int barrierAnsweredAt;
    private boolean finalBarrier;

    private Payload pendingPayload;
    private int payloadSentAt;
    private UUID pendingEntity;
    private CompoundTag pendingEntityData;
    private int entitySentAt;
    private int entityRetryAt;

    public CreativePrinter(PrintPlan plan, int work, boolean replace, boolean entities, boolean contents, boolean instant) {
        Minecraft mc = Minecraft.getInstance();
        this.plan = plan;
        this.schematic = plan.schematic;
        this.level = mc.level;
        this.replace = replace;
        this.entities = entities;
        this.contents = contents;
        this.instant = instant;
        this.left = work;
        // A server's own packet limits sit around five hundred a second, and
        // every one of these is a packet. Singleplayer has no such limit.
        boolean local = mc.isLocalServer();
        this.commandsPerTick = instant ? (local ? 256 : 16) : 4;
        this.payloadsPerTick = instant ? Math.max(1, commandsPerTick / 3) : 1;
        this.budgetNanos = instant ? (local ? 12_000_000L : 6_000_000L) : 3_000_000L;
        String world = DataPaths.currentWorldKey();
        this.signature = world + "/" + (level == null ? "" : level.dimension().location()) + "/"
                + plan.placement.id() + "/" + plan.placement.origin().toShortString() + "/"
                + plan.placement.rotation() + "/" + plan.placement.mirror();
        String worldId = UUID.nameUUIDFromBytes(world.getBytes(StandardCharsets.UTF_8)).toString();
        this.journal = PrintJournal.open(DataPaths.root().resolve("print-entities").resolve(worldId + ".txt"));
        if (!journal.readable()) {
            SimpleSchematics.LOG.warn("Could not read Creative print history; saved entities will be left out");
        }
        if (entities) {
            for (int i = 0; i < schematic.entities().size(); i++) {
                entityQueue.add(i);
            }
        }
    }

    public Status status() {
        return status;
    }

    public int placed() {
        return placedHere.cardinality();
    }

    /** Blocks left for the player: in the way, outside the world, or never taken by the server. */
    public int attention() {
        return attention;
    }

    /** Saved data entries left out because they were unsafe, oversized or could not be confirmed. */
    public int omitted() {
        // Instant payloads are not checked one by one, so the server's refusals stand in for that.
        // The steady pace checks each one, and counting its refusals as well would count them twice.
        return omitted + (instant && refusedBeforeData >= 0 ? Math.max(0, PrintChat.refused() - refusedBeforeData) : 0);
    }

    public String detail() {
        return detail;
    }

    public void cancel() {
        status = Status.CANCELLED;
    }

    Component statusLine() {
        return switch (stage) {
            case WAIT -> PrintManager.tr("simpleschematics.print.waiting_chunks", unloaded);
            case DATA -> entityLater.isEmpty() || !data.isEmpty() || !entityQueue.isEmpty()
                    ? PrintManager.tr("simpleschematics.print.restoring", data.size() + entityQueue.size())
                    : PrintManager.tr("simpleschematics.print.waiting_chunks", entityLater.size());
            default -> PrintManager.tr("simpleschematics.print.progress", placed(), left);
        };
    }

    public void tick() {
        if (status != Status.RUNNING) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level || mc.player == null || mc.gameMode == null || mc.getConnection() == null) {
            cancel();
            return;
        }
        if (!mc.player.isCreative() || !mc.player.hasPermissions(2)) {
            status = Status.NO_PERMISSION;
            detail = "simpleschematics.print.no_permission";
            return;
        }
        if (mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) {
            status = Status.NO_PERMISSION;
            detail = "simpleschematics.print.chat_hidden";
            return;
        }
        tick++;
        int learnt = PrintChat.fillLimit();
        if (learnt > 0 && learnt < fillLimit) {
            fillLimit = learnt;
        }
        switch (stage) {
            case BLOCKS -> placeBlocks(mc);
            case SETTLE -> {
                if (barrierPassed(mc)) {
                    startVerify(false);
                }
            }
            case VERIFY -> verify();
            case WAIT -> {
                if (tick >= waitUntil) {
                    newWave();
                }
            }
            case DATA -> tickData(mc);
            default -> {
            }
        }
    }

    // ---- blocks -----------------------------------------------------------

    private void newWave() {
        cursor = 0;
        sent.clear();
        stage = Stage.BLOCKS;
    }

    private void placeBlocks(Minecraft mc) {
        long deadline = System.nanoTime() + budgetNanos;
        int commands = 0;
        while (cursor < plan.size() && commands < commandsPerTick) {
            if ((cursor & 63) == 0 && System.nanoTime() > deadline) {
                return;
            }
            int index = plan.target(cursor++);
            if (sent.get(index) || abandoned.get(index) || plan.accepted(index)) {
                continue;
            }
            BlockState wanted = plan.wanted(index);
            plan.world(index, pos);
            if (!PrintPlan.inWorld(level, pos) || !level.hasChunkAt(pos)) {
                continue;
            }
            BlockState actual = level.getBlockState(pos);
            if (!placeable(wanted, actual, pos)) {
                continue;
            }
            String command = command(index, wanted, actual);
            if (command == null) {
                abandoned.set(index);
                continue;
            }
            send(mc, command);
            commands++;
        }
        if (cursor >= plan.size()) {
            sendBarrier(mc);
            stage = Stage.SETTLE;
        }
    }

    private boolean placeable(BlockState wanted, BlockState actual, BlockPos at) {
        if (PrintPlacement.matches(wanted, actual) || plan.isBank(level, at)) {
            return false;
        }
        boolean partial = PrintPlacement.isPartial(wanted, actual);
        if (!replace && !actual.canBeReplaced() && !partial) {
            return false;
        }
        // A lone chest joins its partner by itself when that goes in, keeping what is inside.
        if (partial && actual.hasBlockEntity()) {
            return false;
        }
        return survives(wanted, at);
    }

    /** One command for this block and as many identical neighbours as can join it, or null if too long. */
    private String command(int index, BlockState wanted, BlockState actual) {
        BlockPos at = pos.immutable();
        if (!wanted.hasBlockEntity() && (replace || actual.isAir())) {
            int palette = plan.paletteOf(index);
            int[] box = CuboidPlanner.grow(schematic.width(), schematic.height(), schematic.length(), index,
                    other -> joins(other, palette, wanted), fillLimit);
            if (CuboidPlanner.volume(box) > 1) {
                BlockPos from = plan.world(box[0], box[1], box[2], new BlockPos.MutableBlockPos()).immutable();
                BlockPos to = plan.world(box[3], box[4], box[5], new BlockPos.MutableBlockPos()).immutable();
                String fill = "fill " + coordinates(from) + " " + coordinates(to) + " " + stateText(wanted)
                        + (replace ? " replace" : " keep");
                if (fill.length() <= MAX_COMMAND) {
                    markSent(box);
                    return fill;
                }
            }
        }
        String command = "setblock " + coordinates(at) + " " + stateText(wanted)
                + (replace || !actual.isAir() ? " replace" : " keep");
        if (!replace && !actual.isAir()) {
            // The server must still see exactly the replaceable block inspected here.
            command = "execute if block " + coordinates(at) + " " + stateText(actual) + " run " + command;
        }
        if (command.length() > MAX_COMMAND) {
            return null;
        }
        sent.set(index);
        everSent.set(index);
        // Written before the command goes, so contents still follow if the game stops straight after.
        if (plan.hasBlockEntityData(index) && !journal.record(blockId(index), PrintJournal.Step.PLACED)
                && !journalWarned) {
            journalWarned = true;
            SimpleSchematics.LOG.warn("Could not save Creative print history; an interrupted paste may leave containers empty");
        }
        return command;
    }

    private boolean joins(int other, int palette, BlockState wanted) {
        if (sent.get(other) || abandoned.get(other) || plan.paletteOf(other) != palette || plan.accepted(other)) {
            return false;
        }
        plan.world(other, probe);
        if (!PrintPlan.inWorld(level, probe) || !level.hasChunkAt(probe)) {
            return false;
        }
        BlockState actual = level.getBlockState(probe);
        boolean clear = replace
                ? !PrintPlacement.matches(wanted, actual)
                        && !(actual.hasBlockEntity() && PrintPlacement.isPartial(wanted, actual))
                : actual.isAir();
        return clear && !plan.isBank(level, probe) && survives(wanted, probe);
    }

    private void markSent(int[] box) {
        for (int y = box[1]; y <= box[4]; y++) {
            for (int z = box[2]; z <= box[5]; z++) {
                for (int x = box[0]; x <= box[3]; x++) {
                    int index = schematic.index(x, y, z);
                    sent.set(index);
                    everSent.set(index);
                }
            }
        }
    }

    private boolean survives(BlockState state, BlockPos at) {
        try {
            return state.canSurvive(level, at)
                    && (!(state.getBlock() instanceof FallingBlock) || !FallingBlock.isFree(level.getBlockState(at.below())));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void send(Minecraft mc, String command) {
        PrintChat.expect();
        mc.getConnection().sendCommand(command);
    }

    /**
     * Asks the server something it answers in order with the commands before
     * it. When the answer comes back, everything sent so far has been done, and
     * a few ticks later the client has been told about the blocks.
     */
    private void sendBarrier(Minecraft mc) {
        long token = ++barrierToken;
        barrierAnswered = false;
        barrierSentAt = tick;
        mc.getConnection().getDebugQueryHandler().queryBlockEntityTag(mc.player.blockPosition(), tag -> {
            if (token == barrierToken && !barrierAnswered) {
                barrierAnswered = true;
                barrierAnsweredAt = tick;
            }
        });
    }

    private boolean barrierPassed(Minecraft mc) {
        if (barrierAnswered) {
            return tick - barrierAnsweredAt >= 3;
        }
        return tick - barrierSentAt > ACK_TICKS + PrintInventory.responseTicks(mc);
    }

    // ---- checking ---------------------------------------------------------

    private void startVerify(boolean abandon) {
        stage = Stage.VERIFY;
        abandoning = abandon;
        verifyCursor = 0;
        verifyLeft = 0;
        verifyUnloaded = 0;
        verifyAttention = 0;
    }

    private void verify() {
        long deadline = System.nanoTime() + VERIFY_BUDGET_NANOS;
        while (verifyCursor < plan.size()) {
            if ((verifyCursor & 255) == 0 && System.nanoTime() > deadline) {
                return;
            }
            int index = plan.target(verifyCursor++);
            if (plan.accepted(index)) {
                continue;
            }
            BlockState wanted = plan.wanted(index);
            plan.world(index, pos);
            if (!PrintPlan.inWorld(level, pos)) {
                verifyAttention++;
                continue;
            }
            if (!level.hasChunkAt(pos)) {
                verifyUnloaded++;
                continue;
            }
            BlockState actual = level.getBlockState(pos);
            if (PrintPlacement.matches(wanted, actual)) {
                if (everSent.get(index)) {
                    placedHere.set(index);
                }
                if (plan.hasBlockEntityData(index) && !queued.get(index)) {
                    queueBlockData(index, wanted);
                }
                continue;
            }
            if (abandoned.get(index) || plan.isBank(level, pos)
                    || (!replace && !actual.canBeReplaced() && !PrintPlacement.isPartial(wanted, actual))) {
                verifyAttention++;
                continue;
            }
            if (abandoning) {
                abandoned.set(index);
                verifyAttention++;
                continue;
            }
            verifyLeft++;
        }
        left = verifyLeft;
        unloaded = verifyUnloaded;
        attention = verifyAttention;
        // Progress is either fewer blocks left or more of ours standing, since a
        // chunk loading in can add blocks to the count while others go in.
        int placedNow = placed();
        boolean progress = left < lastLeft || placedNow > lastPlaced;
        lastLeft = Math.min(lastLeft, left);
        lastPlaced = placedNow;
        if (progress) {
            stuck = 0;
        }
        if (left > 0) {
            if (!progress && ++stuck >= STUCK_WAVES) {
                // Several waves have gone by without another block going in.
                // Whatever is left is marked for the player on one more pass.
                startVerify(true);
                return;
            }
            newWave();
            return;
        }
        if (unloaded > 0) {
            stage = Stage.WAIT;
            waitUntil = tick + WAIT_TICKS;
            return;
        }
        stage = Stage.DATA;
        refusedBeforeData = PrintChat.refused();
    }

    // ---- saved data -------------------------------------------------------

    private UUID blockId(int index) {
        return UUID.nameUUIDFromBytes((signature + "/block/" + index).getBytes(StandardCharsets.UTF_8));
    }

    private UUID entityId(int index) {
        return UUID.nameUUIDFromBytes((signature + "/" + index).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A container this paste placed, now or before it was interrupted. One it
     * found already standing is never touched, so nothing the player has put
     * in a chest of their own is overwritten.
     */
    private void queueBlockData(int index, BlockState wanted) {
        UUID id = blockId(index);
        if (!everSent.get(index) && journal.step(id) != PrintJournal.Step.PLACED) {
            return;
        }
        queued.set(index);
        CompoundTag original = schematic.blockEntities().get(
                new BlockPos(plan.localX(index), plan.localY(index), plan.localZ(index)));
        if (original == null) {
            return;
        }
        CompoundTag tag = original.copy();
        omitted += sanitise(tag, 0);
        tag.remove("id");
        tag.remove("x");
        tag.remove("y");
        tag.remove("z");
        if (!contents) stripContents(tag);
        if (!entities) tag.remove(BEES);
        BlockPos world = pos.immutable();
        enqueue(new Payload("block " + coordinates(world), tag, world, wanted, null, id));
    }

    private void enqueue(Payload payload) {
        if (payload.tag.isEmpty()) {
            journal.record(payload.journalId, PrintJournal.Step.DONE);
            return;
        }
        // Leave ample space below the vanilla NBT packet limit, including its item wrapper.
        if (payload.tag.sizeInBytes() > 1_048_576 || payload.tag.sizeInBytes() < 0) {
            omitted++;
            journal.record(payload.journalId, PrintJournal.Step.DONE);
            return;
        }
        data.add(payload);
    }

    private void tickData(Minecraft mc) {
        if (pendingPayload != null) {
            if (tick - payloadSentAt > ACK_TICKS) {
                omitted++;
                journal.record(pendingPayload.journalId, PrintJournal.Step.DONE);
                pendingPayload = null;
            }
            return;
        }
        int sends = 0;
        while (sends < payloadsPerTick) {
            if (!data.isEmpty()) {
                if (sendPayload(mc, data.removeFirst())) {
                    sends++;
                    if (!instant) {
                        return;
                    }
                }
                continue;
            }
            Step step = entities ? entityStep(mc) : Step.NOTHING_LEFT;
            if (step == Step.SENT) {
                sends++;
                if (!instant) {
                    return;
                }
                continue;
            }
            if (step == Step.WAITING) {
                return;
            }
            break;
        }
        if (sends > 0 || !data.isEmpty()) {
            return;
        }
        if (!finalBarrier) {
            finalBarrier = true;
            sendBarrier(mc);
            return;
        }
        if (!barrierPassed(mc)) {
            return;
        }
        journal.recordAll(doneAfterBarrier, PrintJournal.Step.DONE);
        doneAfterBarrier.clear();
        stage = Stage.DONE;
        status = Status.COMPLETE;
    }

    private Step entityStep(Minecraft mc) {
        if (pendingEntity != null) {
            // Checked against the server's copy, so it has to be here first.
            if (findEntity(pendingEntity) != null) {
                enqueue(new Payload("entity " + pendingEntity, pendingEntityData, null, null, pendingEntity, pendingEntity));
                pendingEntity = null;
                pendingEntityData = null;
                return Step.SENT;
            }
            if (tick - entitySentAt > ACK_TICKS) {
                // Its attempt stays in the journal, so continuing never summons it twice.
                omitted++;
                pendingEntity = null;
                pendingEntityData = null;
            }
            return Step.WAITING;
        }
        if (entityQueue.isEmpty() && !entityLater.isEmpty() && tick >= entityRetryAt) {
            entityQueue.addAll(entityLater);
            entityLater.clear();
        }
        while (!entityQueue.isEmpty()) {
            int index = entityQueue.poll();
            Step step = entity(mc, index);
            if (step != Step.NOTHING_LEFT) {
                return step;
            }
        }
        if (!entityLater.isEmpty()) {
            entityRetryAt = Math.max(entityRetryAt, tick + WAIT_TICKS);
            return Step.WAITING;
        }
        return Step.NOTHING_LEFT;
    }

    /** @return SENT if something went to the server, NOTHING_LEFT if this entity needs nothing more */
    private Step entity(Minecraft mc, int index) {
        CompoundTag original = schematic.entities().get(index);
        ResourceLocation id = ResourceLocation.tryParse(original.getString("id"));
        ListTag positions = original.getList("Pos", Tag.TAG_DOUBLE);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id) || positions.size() != 3
                || id.toString().equals("minecraft:player")) {
            omitted++;
            return Step.NOTHING_LEFT;
        }
        Vec3 local = new Vec3(positions.getDouble(0), positions.getDouble(1), positions.getDouble(2));
        if (!Double.isFinite(local.x) || !Double.isFinite(local.y) || !Double.isFinite(local.z)
                || local.x < 0 || local.x > schematic.width() || local.y < 0 || local.y > schematic.height()
                || local.z < 0 || local.z > schematic.length()) {
            omitted++;
            return Step.NOTHING_LEFT;
        }
        Vec3 world = transformPoint(schematic, plan.placement.origin(), plan.placement.mirror(),
                plan.placement.rotation(), local);
        BlockPos at = BlockPos.containing(world);
        if (!PrintPlan.inWorld(level, at)) {
            omitted++;
            return Step.NOTHING_LEFT;
        }
        if (!level.hasChunkAt(at)) {
            entityLater.add(index);
            return Step.NOTHING_LEFT;
        }
        UUID uuid = entityId(index);
        PrintJournal.Step done = journal.step(uuid);
        if (done == PrintJournal.Step.DONE) {
            return Step.NOTHING_LEFT;
        }
        CompoundTag tag = entityData(original, id, world);
        if (done != null) {
            // Summoned before the paste was interrupted. Its data is finished off
            // if it is here; if not, it was removed, and is never summoned again.
            if (findEntity(uuid) != null) {
                enqueue(new Payload("entity " + uuid, tag, null, null, uuid, uuid));
                return Step.SENT;
            }
            omitted++;
            return Step.NOTHING_LEFT;
        }
        CompoundTag spawn = new CompoundTag();
        spawn.putUUID("UUID", uuid);
        // Hanging entities need their transformed anchor during construction.
        for (String key : List.of("TileX", "TileY", "TileZ", "Facing", "facing", "variant")) {
            if (tag.contains(key)) spawn.put(key, tag.get(key).copy());
        }
        String command = "execute unless entity " + uuid + " run summon " + id + " "
                + decimal(world.x) + " " + decimal(world.y) + " " + decimal(world.z) + " " + spawn;
        if (command.length() > MAX_COMMAND) {
            // The journal and the fresh deterministic UUID still prevent retries
            // when the guarded form leaves no room for a hanging anchor.
            command = "summon " + id + " " + decimal(world.x) + " " + decimal(world.y) + " " + decimal(world.z) + " " + spawn;
        }
        if (command.length() > MAX_COMMAND) {
            omitted++;
            return Step.NOTHING_LEFT;
        }
        if (!journal.record(uuid, PrintJournal.Step.ATTEMPTED)) {
            // Without a record, continuing could summon it again.
            omitted++;
            if (!journalWarned) {
                journalWarned = true;
                SimpleSchematics.LOG.warn("Could not save Creative print history; saved entities are being left out");
            }
            return Step.NOTHING_LEFT;
        }
        send(mc, command);
        if (instant) {
            // The server takes the data straight after the summon, in order.
            enqueue(new Payload("entity " + uuid, tag, null, null, uuid, uuid));
        } else {
            pendingEntity = uuid;
            pendingEntityData = tag;
            entitySentAt = tick;
        }
        return Step.SENT;
    }

    private CompoundTag entityData(CompoundTag original, ResourceLocation id, Vec3 world) {
        CompoundTag tag = original.copy();
        omitted += sanitise(tag, 0);
        if (tag.contains("Passengers")) {
            // Captures also enumerate the passengers separately. Their original riding
            // graph cannot safely be restored through short chat command packets.
            tag.remove("Passengers");
            omitted++;
        }
        for (String key : List.of("id", "UUID", "UUIDMost", "UUIDLeast", "Pos", "Motion", "Leash",
                "SleepingX", "SleepingY", "SleepingZ", "Brain", "HomePosX", "HomePosY", "HomePosZ")) {
            tag.remove(key);
        }
        if (!contents) stripContents(tag);
        transformEntityData(tag, id, world, plan.placement.mirror(), plan.placement.rotation());
        return tag;
    }

    private Entity findEntity(UUID uuid) {
        for (Entity entity : level.entitiesForRendering()) {
            if (uuid.equals(entity.getUUID())) {
                return entity;
            }
        }
        return null;
    }

    /** @return false if the payload no longer has anywhere to go */
    private boolean sendPayload(Minecraft mc, Payload payload) {
        if (payload.block != null && (!level.hasChunkAt(payload.block)
                || !PrintPlacement.matches(payload.state, level.getBlockState(payload.block)))) {
            // Changed since it was placed. Left as it is; the journal still offers it next time.
            omitted++;
            return false;
        }
        Entity entity = null;
        if (payload.entity != null && !instant) {
            entity = findEntity(payload.entity);
            if (entity == null) {
                omitted++;
                return false;
            }
        }
        ItemStack previous = mc.player.getOffhandItem().copy();
        ItemStack carrier = new ItemStack(Items.PAPER);
        //? if >=1.21 {
        /*CompoundTag carried = new CompoundTag();
        carried.put("ss_print", payload.tag.copy());
        carrier.set(DataComponents.CUSTOM_DATA, CustomData.of(carried));
        *///?} else {
        carrier.getOrCreateTag().put("ss_print", payload.tag.copy());
        //?}
        // Packet ordering makes this one atomic data transfer followed by restoration.
        // The carrier avoids chat's 256-character ceiling for long text and inventories.
        PrintChat.expect();
        mc.gameMode.handleCreativeModeItemAdd(carrier, 45);
        try {
            mc.getConnection().sendCommand("data modify " + payload.target
                    + " {} merge from entity @s " + CARRIED);
        } finally {
            mc.gameMode.handleCreativeModeItemAdd(previous, 45);
        }
        if (instant) {
            doneAfterBarrier.add(payload.journalId);
            return true;
        }
        pendingPayload = payload;
        payloadSentAt = tick;
        Consumer<CompoundTag> reply = actual -> {
            if (status != Status.RUNNING || pendingPayload != payload) return;
            pendingPayload = null;
            if (actual == null || !arrived(verificationPayload(payload.tag), actual)) {
                omitted++;
            }
            journal.record(payload.journalId, PrintJournal.Step.DONE);
        };
        // DebugQueryHandler has one callback slot, so only one payload is in flight.
        if (payload.block != null) mc.getConnection().getDebugQueryHandler().queryBlockEntityTag(payload.block, reply);
        else mc.getConnection().getDebugQueryHandler().queryEntityTag(entity.getId(), reply);
        return true;
    }

    /** A painting's size in blocks, or null if the variant is not known here. */
    private static int[] paintingSize(ResourceLocation id) {
        //? if >=1.21 {
        /*// Paintings became data driven in 1.21, so the variants arrive from the server.
        ClientLevel level = Minecraft.getInstance().level;
        PaintingVariant variant = level == null ? null
                : level.registryAccess().registryOrThrow(Registries.PAINTING_VARIANT).get(id);
        return variant == null ? null : new int[]{variant.width(), variant.height()};
        *///?} else {
        PaintingVariant variant = BuiltInRegistries.PAINTING_VARIANT.get(id);
        return variant == null ? null : new int[]{variant.getWidth() / 16, variant.getHeight() / 16};
        //?}
    }

    private static CompoundTag verificationPayload(CompoundTag tag) {
        CompoundTag expected = tag.copy();
        // These values can change during the server tick which acknowledges the copy.
        for (String key : List.of("Air", "Fire", "FallDistance", "OnGround", "PortalCooldown", "HurtTime",
                "HurtByTimestamp", "DeathTime", "Age", "BurnTime", "CookTime", "TransferCooldown",
                "LastUpdate", "LastUpdateTime", "TicksSincePollination", "FlowerPos", "HivePos",
                "flower_pos", "hive_pos")) expected.remove(key);
        return expected;
    }

    /**
     * {@code NbtUtils.compareNbt} in its partial form, except that two strings
     * holding the same text match however the JSON is written. A server saves
     * text back in its own form, so {@code {"text":"Shiny"}} returns as
     * {@code "Shiny"}, and data fixed up from an older version is written the
     * long way. Without this every such payload was counted as not copied.
     */
    private static boolean arrived(Tag expected, Tag actual) {
        if (expected == actual || expected == null) return true;
        if (actual == null || !expected.getClass().equals(actual.getClass())) return false;
        if (expected instanceof StringTag want) {
            return want.equals(actual) || sameText(want.getAsString(), actual.getAsString());
        }
        if (expected instanceof CompoundTag want) {
            CompoundTag got = (CompoundTag) actual;
            if (got.size() < want.size()) return false;
            for (String key : want.getAllKeys()) {
                if (!arrived(want.get(key), got.get(key))) return false;
            }
            return true;
        }
        if (expected instanceof ListTag want) {
            ListTag got = (ListTag) actual;
            if (want.isEmpty()) return got.isEmpty();
            if (got.size() < want.size()) return false;
            for (Tag element : want) {
                boolean found = false;
                for (Tag candidate : got) {
                    if (arrived(element, candidate)) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        }
        return expected.equals(actual);
    }

    private static boolean sameText(String a, String b) {
        if (!looksLikeText(a) && !looksLikeText(b)) return false;
        try {
            Component left = parseText(a);
            return left != null && left.equals(parseText(b));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean looksLikeText(String json) {
        return json.startsWith("{") || json.startsWith("[") || json.startsWith("\"");
    }

    private static Component parseText(String json) {
        //? if >=1.21 {
        /*ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : Component.Serializer.fromJson(json, level.registryAccess());
        *///?} else {
        return Component.Serializer.fromJson(json);
        //?}
    }
    static void transformEntityData(CompoundTag tag, ResourceLocation id, Vec3 world, Mirror mirror, Rotation rotation) {
        ListTag angles = tag.getList("Rotation", Tag.TAG_FLOAT);
        if (angles.size() == 2) {
            float yaw = angles.getFloat(0);
            float pitch = angles.getFloat(1);
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                tag.remove("Rotation");
            } else {
                if (mirror == Mirror.FRONT_BACK) yaw = -yaw;
                if (mirror == Mirror.LEFT_RIGHT) yaw = 180 - yaw;
                yaw += switch (rotation) {
                    case CLOCKWISE_90 -> 90;
                    case CLOCKWISE_180 -> 180;
                    case COUNTERCLOCKWISE_90 -> -90;
                    default -> 0;
                };
                ListTag transformed = new ListTag();
                transformed.add(FloatTag.valueOf(yaw));
                transformed.add(FloatTag.valueOf(pitch));
                tag.put("Rotation", transformed);
            }
        }
        Direction facing = null;
        boolean painting = id.toString().equals("minecraft:painting");
        if (tag.contains("Facing")) {
            facing = transformDirection(Direction.from3DDataValue(tag.getByte("Facing")), mirror, rotation);
            tag.putByte("Facing", (byte) facing.get3DDataValue());
        } else if (painting) {
            facing = transformDirection(Direction.from2DDataValue(tag.getByte("facing")), mirror, rotation);
            tag.putByte("facing", (byte) facing.get2DDataValue());
        }
        if (facing != null) {
            // Hanging entities derive these angles from Facing when their NBT loads.
            ListTag hangingAngles = new ListTag();
            hangingAngles.add(FloatTag.valueOf(facing.getAxis().isHorizontal() ? facing.get2DDataValue() * 90 : 0));
            hangingAngles.add(FloatTag.valueOf(facing.getAxis().isVertical() ? -90 * facing.getAxisDirection().getStep() : 0));
            tag.put("Rotation", hangingAngles);
        }
        if (facing != null && tag.contains("TileX")) {
            double across = 0;
            double up = 0;
            if (painting) {
                ResourceLocation variantId = ResourceLocation.tryParse(tag.getString("variant"));
                int[] size = variantId == null ? null : paintingSize(variantId);
                if (size != null) {
                    across = size[0] % 2 == 0 ? 0.5 : 0;
                    up = size[1] % 2 == 0 ? 0.5 : 0;
                }
            }
            Direction left = facing.getAxis().isHorizontal() ? facing.getCounterClockWise() : Direction.WEST;
            BlockPos anchor = BlockPos.containing(world.x + facing.getStepX() * 0.46875 - left.getStepX() * across,
                    world.y + facing.getStepY() * 0.46875 - up,
                    world.z + facing.getStepZ() * 0.46875 - left.getStepZ() * across);
            tag.putInt("TileX", anchor.getX());
            tag.putInt("TileY", anchor.getY());
            tag.putInt("TileZ", anchor.getZ());
        }
        if (facing != null && tag.contains("ItemRotation")) {
            boolean map = tag.getCompound("Item").getString("id").equals("minecraft:filled_map");
            int steps = map ? 4 : 8;
            int itemRotation = tag.getByte("ItemRotation");
            if (mirror != Mirror.NONE) {
                itemRotation = facing.getAxis().isHorizontal() || mirror == Mirror.FRONT_BACK
                        ? steps / 2 - itemRotation : -itemRotation;
            }
            if (facing.getAxis().isVertical()) {
                int turns = switch (rotation) {
                    case CLOCKWISE_90 -> 1;
                    case CLOCKWISE_180 -> 2;
                    case COUNTERCLOCKWISE_90 -> -1;
                    default -> 0;
                };
                itemRotation += turns * (steps / 4) * facing.getAxisDirection().getStep();
            }
            tag.putByte("ItemRotation", (byte) Math.floorMod(itemRotation, steps));
        }
    }

    private static Direction transformDirection(Direction direction, Mirror mirror, Rotation rotation) {
        if (mirror == Mirror.LEFT_RIGHT && direction.getAxis() == Direction.Axis.Z) direction = direction.getOpposite();
        if (mirror == Mirror.FRONT_BACK && direction.getAxis() == Direction.Axis.X) direction = direction.getOpposite();
        return rotation.rotate(direction);
    }

    static Vec3 transformPoint(Schematic schematic, BlockPos origin, Mirror mirror, Rotation rotation, Vec3 local) {
        double x = mirror == Mirror.FRONT_BACK ? schematic.width() - local.x : local.x;
        double z = mirror == Mirror.LEFT_RIGHT ? schematic.length() - local.z : local.z;
        double rx = switch (rotation) {
            case CLOCKWISE_90 -> schematic.length() - z;
            case CLOCKWISE_180 -> schematic.width() - x;
            case COUNTERCLOCKWISE_90 -> z;
            default -> x;
        };
        double rz = switch (rotation) {
            case CLOCKWISE_90 -> x;
            case CLOCKWISE_180 -> schematic.length() - z;
            case COUNTERCLOCKWISE_90 -> schematic.width() - x;
            default -> z;
        };
        return new Vec3(origin.getX() + rx, origin.getY() + local.y, origin.getZ() + rz);
    }

    private static int sanitise(CompoundTag tag, int depth) {
        if (depth > 32) {
            for (String key : new ArrayList<>(tag.getAllKeys())) tag.remove(key);
            return 1;
        }
        int removed = 0;
        for (String key : new ArrayList<>(tag.getAllKeys())) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("command") || lower.equals("lastoutput") || lower.equals("auto")
                    || lower.equals("loottable") || lower.equals("loottableseed")) {
                tag.remove(key);
                removed++;
                continue;
            }
            Tag value = tag.get(key);
            if (value instanceof CompoundTag nested) removed += sanitise(nested, depth + 1);
            else if (value instanceof ListTag list) removed += sanitiseList(list, depth + 1);
            else if (value instanceof StringTag string) {
                String text = safeText(string.getAsString());
                if (!text.equals(string.getAsString())) removed++;
                tag.putString(key, text);
            }
        }
        return removed;
    }

    private static int sanitiseList(ListTag list, int depth) {
        if (depth > 32) { list.clear(); return 1; }
        int removed = 0;
        for (int i = 0; i < list.size(); i++) {
            Tag value = list.get(i);
            if (value instanceof CompoundTag compound) removed += sanitise(compound, depth + 1);
            else if (value instanceof ListTag nested) removed += sanitiseList(nested, depth + 1);
            else if (value instanceof StringTag string) {
                String text = safeText(string.getAsString());
                if (!text.equals(string.getAsString())) removed++;
                list.set(i, StringTag.valueOf(text));
            }
        }
        return removed;
    }
    private static String safeText(String text) {
        if (!text.contains("clickEvent") && !text.contains("click_event")) return text;
        try {
            JsonElement json = JsonParser.parseString(text);
            removeClickEvents(json);
            return json.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static void removeClickEvents(JsonElement element) {
        if (element.isJsonObject()) {
            element.getAsJsonObject().remove("clickEvent");
            element.getAsJsonObject().remove("click_event");
            for (var entry : element.getAsJsonObject().entrySet()) removeClickEvents(entry.getValue());
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) removeClickEvents(child);
        }
    }

    private static void stripContents(CompoundTag tag) {
        for (String key : List.of("Items", "Inventory", "RecordItem", "Book", BEES, "Item",
                "HandItems", "ArmorItems", "SaddleItem", "ArmorItem", "DecorItem")) tag.remove(key);
        //? if >=1.21 {
        /*// A decorated pot's single item, and the armour or carpet on a horse, wolf or llama.
        tag.remove("item");
        tag.remove("body_armor_item");
        *///?}
    }

    private static String coordinates(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
    private static String decimal(double value) { return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static String stateText(BlockState state) {
        StringBuilder text = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (!state.getProperties().isEmpty()) {
            text.append('[');
            boolean first = true;
            for (Property<?> property : state.getProperties()) {
                if (!first) text.append(',');
                first = false;
                appendProperty(text, state, property);
            }
            text.append(']');
        }
        return text.toString();
    }
    private static <T extends Comparable<T>> void appendProperty(StringBuilder text, BlockState state, Property<T> property) {
        text.append(property.getName()).append('=').append(property.getName(state.getValue(property)));
    }
    private record Payload(String target, CompoundTag tag, BlockPos block, BlockState state, UUID entity, UUID journalId) {
    }
}
