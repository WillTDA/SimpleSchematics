package dev.willtda.simpleschematics.printing;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.resource.Banks;
import dev.willtda.simpleschematics.schematic.Schematic;
import dev.willtda.simpleschematics.util.DataPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.decoration.PaintingVariant;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Operator-only Creative pasting through the server's ordinary command interface. */
public final class CreativePrinter {
    public enum Status { RUNNING, COMPLETE, BLOCKED, NO_PERMISSION, CANCELLED }

    // Chat command packets in 1.20.1 reject anything longer than 256 characters.
    private static final int MAX_COMMAND = 256;
    private static final int COMMANDS_PER_TICK = 4;
    private static final int SCAN_PER_TICK = 4096;
    private static final int ACK_TICKS = 100;
    private final Placement placement;
    private final Schematic schematic;
    private final ClientLevel level;
    private final BlockPos origin;
    private final Rotation rotation;
    private final Mirror mirror;
    private final boolean replace;
    private final boolean entities;
    private final boolean contents;
    private final ArrayDeque<Target> blocks = new ArrayDeque<>();
    private final List<Pending> pending = new ArrayList<>();
    private final ArrayDeque<Payload> data = new ArrayDeque<>();
    private final Set<String> attemptedEntities = new HashSet<>();
    private final Path entityJournal;
    private final String signature;
    private Status status = Status.RUNNING;
    private String detail = "";
    private int cursor;
    private int tick;
    private int placed;
    private int skipped;
    private int deferred;
    private int entityCursor;
    private int verifyCursor;
    private int settleAt = -1;
    private boolean verifiedBlocks;
    private UUID pendingEntity;
    private CompoundTag pendingEntityData;
    private int entitySentAt;
    private Payload pendingPayload;
    private int payloadSentAt;
    private boolean scanned;
    private boolean journalReadable = true;

    public CreativePrinter(Placement placement, Schematic schematic, boolean replace,
                           boolean entities, boolean contents) {
        this.placement = placement;
        this.schematic = schematic;
        this.replace = replace;
        this.entities = entities;
        this.contents = contents;
        this.level = Minecraft.getInstance().level;
        this.origin = placement.origin();
        this.rotation = placement.rotation();
        this.mirror = placement.mirror();
        String world = DataPaths.currentWorldKey();
        this.signature = world + "/" + (level == null ? "" : level.dimension().location()) + "/"
                + placement.id() + "/" + origin.toShortString() + "/" + rotation + "/" + mirror;
        String worldId = UUID.nameUUIDFromBytes(world.getBytes(StandardCharsets.UTF_8)).toString();
        this.entityJournal = DataPaths.root().resolve("print-entities").resolve(worldId + ".txt");
        if (entities) {
            try {
                if (Files.exists(entityJournal)) attemptedEntities.addAll(Files.readAllLines(entityJournal));
            } catch (IOException e) {
                journalReadable = false;
                SimpleSchematics.LOG.warn("Could not read Creative print entity history", e);
            }
        }
    }

    public Status status() { return status; }
    public int placed() { return placed; }
    /** Payloads omitted because they were unsafe, oversized or already attempted. */
    public int skipped() { return skipped; }
    public String detail() { return detail; }
    public void cancel() { status = Status.CANCELLED; }

    public void tick() {
        if (status != Status.RUNNING) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level || mc.player == null || mc.gameMode == null || mc.getConnection() == null) {
            cancel();
            return;
        }
        if (!mc.player.isCreative() || !mc.player.hasPermissions(2)) {
            fail(Status.NO_PERMISSION, "Creative printing requires Creative Mode and operator permission.");
            return;
        }
        if (!origin.equals(placement.origin()) || rotation != placement.rotation() || mirror != placement.mirror()) {
            fail(Status.BLOCKED, "The placement moved. Start Print again at its new position.");
            return;
        }
        tick++;
        if (!scanned) {
            scan();
            return;
        }
        acknowledgeBlocks();
        if (status != Status.RUNNING) return;
        int commands = 0;
        while (commands < COMMANDS_PER_TICK && !blocks.isEmpty() && pending.size() < 16) {
            Target target = blocks.removeFirst();
            if (!valid(target.world)) return;
            BlockState actual = level.getBlockState(target.world);
            if (PrintPlacement.matches(target.state, actual)) continue;
            if (placement.isBank(Banks.canonical(level, target.world))) {
                fail(Status.BLOCKED, "A linked material container is in the way. Move it outside the build before continuing.");
                return;
            }
            if (!replace && !actual.canBeReplaced() && !PrintPlacement.isPartial(target.state, actual)) {
                fail(Status.BLOCKED, "A block is in the way at " + target.world.toShortString()
                        + ". Clear it or enable replacement in Print settings.");
                return;
            }
            // Finishing the other half of a chest connects it without clearing its inventory.
            boolean existingContainer = actual.hasBlockEntity() && PrintPlacement.isPartial(target.state, actual);
            if (existingContainer || !survives(target)) {
                blocks.addLast(target);
                if (++deferred >= blocks.size()) {
                    if (!pending.isEmpty()) break;
                    fail(Status.BLOCKED, "Some blocks need support or conflict with their neighbours. Fix the build and continue Print.");
                    return;
                }
                continue;
            }
            deferred = 0;
            List<Target> batch = new ArrayList<>();
            batch.add(target);
            if (!target.state.hasBlockEntity() && (replace || actual.isAir())) {
                while (!blocks.isEmpty() && batch.size() < 256) {
                    Target next = blocks.peekFirst();
                    Target last = batch.get(batch.size() - 1);
                    if (next.local.getY() != target.local.getY() || next.local.getZ() != target.local.getZ()
                            || next.local.getX() != last.local.getX() + 1 || next.state != target.state
                            || !level.hasChunkAt(next.world) || (!replace && !level.getBlockState(next.world).isAir())
                            || placement.isBank(Banks.canonical(level, next.world))
                            || !survives(next)) break;
                    batch.add(blocks.removeFirst());
                }
            }
            String command;
            if (batch.size() > 1) {
                command = "fill " + coordinates(target.world) + " " + coordinates(batch.get(batch.size() - 1).world)
                        + " " + stateText(target.state) + (replace ? " replace" : " keep");
            } else {
                command = "setblock " + coordinates(target.world) + " " + stateText(target.state)
                        + (replace || !actual.isAir() ? " replace" : " keep");
                if (!replace && !actual.isAir()) {
                    // The server must still see exactly the replaceable block inspected here.
                    command = "execute if block " + coordinates(target.world) + " " + stateText(actual)
                            + " run " + command;
                }
            }
            if (command.length() > MAX_COMMAND) {
                fail(Status.BLOCKED, "A block state exceeds Minecraft's command length limit at " + target.world.toShortString() + ".");
                return;
            }
            mc.getConnection().sendCommand(command);
            pending.add(new Pending(batch, tick));
            commands++;
        }
        if (!blocks.isEmpty() || !pending.isEmpty()) return;
        if (!verifiedBlocks) {
            verifyFinishedBlocks();
            return;
        }
        if (pendingPayload != null) {
            if (tick - payloadSentAt > ACK_TICKS) {
                fail(Status.BLOCKED, "The server did not confirm the saved container or entity data. Check command permissions before continuing.");
            }
            return;
        }
        if (!data.isEmpty()) {
            if (commands < COMMANDS_PER_TICK) sendPayload(mc, data.removeFirst());
            return;
        }
        if (entities && commands < COMMANDS_PER_TICK && !tickEntities()) return;
        if (pendingEntity == null && (!entities || entityCursor >= schematic.entities().size()) && data.isEmpty()) {
            status = Status.COMPLETE;
            detail = skipped == 0 ? "" : skipped + " saved data entries were omitted or had already been attempted.";
        }
    }

    private void scan() {
        int end = (int) Math.min(schematic.volume(), (long) cursor + SCAN_PER_TICK);
        for (; cursor < end; cursor++) {
            if (placement.isAccepted(cursor)) continue;
            int x = cursor % schematic.width();
            int z = (cursor / schematic.width()) % schematic.length();
            int y = cursor / (schematic.width() * schematic.length());
            BlockState state = schematic.getBlockState(x, y, z).mirror(mirror).rotate(rotation);
            if (state.isAir()) continue;
            BlockPos local = new BlockPos(x, y, z);
            BlockPos world = placement.toWorld(schematic, x, y, z);
            if (!valid(world)) return;
            BlockState actual = level.getBlockState(world);
            if (PrintPlacement.matches(state, actual)) continue;
            if (!replace && !actual.canBeReplaced() && !PrintPlacement.isPartial(state, actual)) {
                fail(Status.BLOCKED, "A block is in the way at " + world.toShortString()
                        + ". Clear it or enable replacement in Print settings.");
                return;
            }
            blocks.add(new Target(local, world, state));
        }
        scanned = cursor >= schematic.volume();
    }

    private void verifyFinishedBlocks() {
        if (settleAt < 0) settleAt = tick + 2;
        if (tick < settleAt) return;
        int end = (int) Math.min(schematic.volume(), (long) verifyCursor + SCAN_PER_TICK);
        for (; verifyCursor < end; verifyCursor++) {
            if (placement.isAccepted(verifyCursor)) continue;
            int x = verifyCursor % schematic.width();
            int z = (verifyCursor / schematic.width()) % schematic.length();
            int y = verifyCursor / (schematic.width() * schematic.length());
            BlockState expected = schematic.getBlockState(x, y, z).mirror(mirror).rotate(rotation);
            if (expected.isAir()) continue;
            BlockPos local = new BlockPos(x, y, z);
            BlockPos world = placement.toWorld(schematic, x, y, z);
            if (!valid(world)) return;
            if (!PrintPlacement.matches(expected, level.getBlockState(world))
                    || !survives(new Target(local, world, expected))) {
                fail(Status.BLOCKED, "A finished block changed or lost support at " + world.toShortString()
                        + ". Check its neighbours before continuing Print.");
                return;
            }
        }
        verifiedBlocks = verifyCursor >= schematic.volume();
    }
    private boolean survives(Target target) {
        try {
            return target.state.canSurvive(level, target.world)
                    && (!(target.state.getBlock() instanceof FallingBlock)
                        || !FallingBlock.isFree(level.getBlockState(target.world.below())));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void acknowledgeBlocks() {
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            Pending sent = iterator.next();
            boolean ready = true;
            for (Target target : sent.targets) {
                if (!valid(target.world)) return;
                if (!PrintPlacement.matches(target.state, level.getBlockState(target.world))) {
                    ready = false;
                    if (tick - sent.sentAt > ACK_TICKS) {
                        fail(Status.BLOCKED, "The server did not confirm a block at " + target.world.toShortString()
                                + ". Check permissions, protection rules and block support before continuing.");
                        return;
                    }
                }
            }
            if (!ready) continue;
            iterator.remove();
            placed += sent.targets.size();
            for (Target target : sent.targets) {
                CompoundTag original = schematic.blockEntities().get(target.local);
                if (original == null) continue;
                CompoundTag tag = original.copy();
                skipped += sanitise(tag, 0);
                tag.remove("id");
                tag.remove("x");
                tag.remove("y");
                tag.remove("z");
                if (!contents) stripContents(tag);
                if (!entities) tag.remove("Bees");
                enqueueFields("block " + coordinates(target.world), tag, target);
            }
        }
    }

    private boolean tickEntities() {
        if (pendingEntity != null) {
            boolean found = false;
            for (Entity entity : level.entitiesForRendering()) {
                if (pendingEntity.equals(entity.getUUID())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (tick - entitySentAt > ACK_TICKS) {
                    fail(Status.BLOCKED, "The server did not confirm a saved entity. Its attempt was recorded to prevent duplicate entities when continuing.");
                }
                return false;
            }
            enqueueFields("entity " + pendingEntity, pendingEntityData, null);
            pendingEntity = null;
            pendingEntityData = null;
            return false;
        }
        if (entityCursor >= schematic.entities().size()) return true;
        int index = entityCursor++;
        CompoundTag original = schematic.entities().get(index);
        ResourceLocation id = ResourceLocation.tryParse(original.getString("id"));
        ListTag positions = original.getList("Pos", Tag.TAG_DOUBLE);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id) || positions.size() != 3
                || id.toString().equals("minecraft:player")) {
            skipped++;
            return false;
        }
        Vec3 local = new Vec3(positions.getDouble(0), positions.getDouble(1), positions.getDouble(2));
        if (!Double.isFinite(local.x) || !Double.isFinite(local.y) || !Double.isFinite(local.z)
                || local.x < 0 || local.x > schematic.width() || local.y < 0 || local.y > schematic.height()
                || local.z < 0 || local.z > schematic.length()) {
            skipped++;
            return false;
        }
        Vec3 world = toWorld(local);
        if (!valid(BlockPos.containing(world))) return false;
        UUID uuid = UUID.nameUUIDFromBytes((signature + "/" + index).getBytes(StandardCharsets.UTF_8));
        if (attemptedEntities.contains(uuid.toString())) {
            skipped++;
            return false;
        }
        CompoundTag tag = original.copy();
        skipped += sanitise(tag, 0);
        if (tag.contains("Passengers")) {
            // Captures also enumerate the passengers separately. Their original riding
            // graph cannot safely be restored through short chat command packets.
            tag.remove("Passengers");
            skipped++;
        }
        tag.remove("id");
        tag.remove("UUID");
        tag.remove("UUIDMost");
        tag.remove("UUIDLeast");
        tag.remove("Pos");
        tag.remove("Motion");
        tag.remove("Leash");
        tag.remove("SleepingX");
        tag.remove("SleepingY");
        tag.remove("SleepingZ");
        tag.remove("Brain");
        tag.remove("HomePosX");
        tag.remove("HomePosY");
        tag.remove("HomePosZ");
        if (!contents) stripContents(tag);
        transformEntityData(tag, id, world, mirror, rotation);
        CompoundTag spawn = new CompoundTag();
        spawn.putUUID("UUID", uuid);
        // Hanging entities need their transformed anchor during construction.
        for (String key : List.of("TileX", "TileY", "TileZ", "Facing", "facing", "variant")) {
            if (tag.contains(key)) spawn.put(key, tag.get(key).copy());
        }
        String command = "execute unless entity " + uuid + " run summon " + id + " "
                + decimal(world.x) + " " + decimal(world.y) + " " + decimal(world.z) + " " + spawn;
        if (command.length() > MAX_COMMAND) {
            // The durable attempt journal and fresh deterministic UUID still prevent
            // retries when the guarded form leaves no room for a hanging anchor.
            command = "summon " + id + " "
                    + decimal(world.x) + " " + decimal(world.y) + " " + decimal(world.z) + " " + spawn;
        }
        if (command.length() > MAX_COMMAND) {
            skipped++;
            return false;
        }
        if (!recordEntityAttempt(uuid)) {
            fail(Status.BLOCKED, "Entity printing could not save its resume history. Check the mod's data folder permissions.");
            return false;
        }
        Minecraft.getInstance().getConnection().sendCommand(command);
        pendingEntity = uuid;
        pendingEntityData = tag;
        entitySentAt = tick;
        return false;
    }

    private void enqueueFields(String target, CompoundTag tag, Target block) {
        if (tag.isEmpty()) return;
        // Leave ample space below the vanilla NBT packet limit, including its item wrapper.
        if (tag.sizeInBytes() > 1_048_576 || tag.sizeInBytes() < 0) {
            skipped++;
            return;
        }
        data.add(new Payload(target, tag, block, block == null ? UUID.fromString(target.substring(7)) : null));
    }

    private void sendPayload(Minecraft mc, Payload payload) {
        if (payload.block != null && (!valid(payload.block.world)
                || !PrintPlacement.matches(payload.block.state, level.getBlockState(payload.block.world)))) {
            if (status == Status.RUNNING) fail(Status.BLOCKED, "A container changed before its saved data could be copied.");
            return;
        }
        Entity entity = null;
        if (payload.entity != null) {
            for (Entity candidate : level.entitiesForRendering()) {
                if (payload.entity.equals(candidate.getUUID())) { entity = candidate; break; }
            }
            if (entity == null) {
                fail(Status.BLOCKED, "A saved entity left the loaded area before its data could be copied.");
                return;
            }
        }
        ItemStack previous = mc.player.getOffhandItem().copy();
        ItemStack carrier = new ItemStack(Items.PAPER);
        carrier.getOrCreateTag().put("ss_print", payload.tag.copy());
        // Packet ordering makes this one atomic data transfer followed by restoration.
        // The carrier avoids chat's 256-character ceiling for long text and inventories.
        mc.gameMode.handleCreativeModeItemAdd(carrier, 45);
        try {
            mc.getConnection().sendCommand("data modify " + payload.target
                    + " {} merge from entity @s Inventory[{Slot:-106b}].tag.ss_print");
        } finally {
            mc.gameMode.handleCreativeModeItemAdd(previous, 45);
        }
        pendingPayload = payload;
        payloadSentAt = tick;
        java.util.function.Consumer<CompoundTag> reply = actual -> {
            if (status != Status.RUNNING || pendingPayload != payload) return;
            pendingPayload = null;
            CompoundTag expected = verificationPayload(payload.tag);
            if (actual == null || !NbtUtils.compareNbt(expected, actual, true)) {
                fail(Status.BLOCKED, "The server did not apply all saved container or entity data. Check protection rules before continuing.");
            }
        };
        // DebugQueryHandler has one callback slot, so only one payload is in flight.
        if (payload.block != null) mc.getConnection().getDebugQueryHandler().queryBlockEntityTag(payload.block.world, reply);
        else mc.getConnection().getDebugQueryHandler().queryEntityTag(entity.getId(), reply);
    }

    private static CompoundTag verificationPayload(CompoundTag tag) {
        CompoundTag expected = tag.copy();
        // These values can change during the server tick which acknowledges the copy.
        for (String key : List.of("Air", "Fire", "FallDistance", "OnGround", "PortalCooldown", "HurtTime",
                "HurtByTimestamp", "DeathTime", "Age", "BurnTime", "CookTime", "TransferCooldown",
                "LastUpdate", "LastUpdateTime", "TicksSincePollination", "FlowerPos", "HivePos")) expected.remove(key);
        return expected;
    }
    private boolean recordEntityAttempt(UUID uuid) {
        if (!journalReadable) return false;
        try {
            Files.createDirectories(entityJournal.getParent());
            Files.writeString(entityJournal, uuid + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            attemptedEntities.add(uuid.toString());
            return true;
        } catch (IOException e) {
            SimpleSchematics.LOG.warn("Could not save Creative print entity history", e);
            return false;
        }
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
                PaintingVariant variant = variantId == null ? null : BuiltInRegistries.PAINTING_VARIANT.get(variantId);
                if (variant != null) {
                    across = variant.getWidth() % 32 == 0 ? 0.5 : 0;
                    up = variant.getHeight() % 32 == 0 ? 0.5 : 0;
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

    private Vec3 toWorld(Vec3 local) {
        return transformPoint(schematic, origin, mirror, rotation, local);
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
        for (String key : List.of("Items", "Inventory", "RecordItem", "Book", "Bees", "Item",
                "HandItems", "ArmorItems", "SaddleItem", "ArmorItem", "DecorItem")) tag.remove(key);
    }

    private boolean valid(BlockPos pos) {
        if (level == null || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || !level.hasChunkAt(pos)) {
            fail(Status.BLOCKED, "The build leaves the world border, build height or loaded chunks. Move closer and continue Print.");
            return false;
        }
        return true;
    }

    private void fail(Status result, String message) { status = result; detail = message; }
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
    private record Target(BlockPos local, BlockPos world, BlockState state) { }
    private record Pending(List<Target> targets, int sentAt) { }
    private record Payload(String target, CompoundTag tag, Target block, UUID entity) { }
}
