package dev.willtda.simpleschematics.printing;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.google.gson.JsonParser;
import com.mojang.brigadier.StringReader;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.schematic.Schematic;

import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/** Regression checks for Creative entity transforms and the full-payload NBT transfer. */
public final class CreativePrintTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        PrintTestBootstrap.initialise();
        CommentedConfig config = CommentedConfig.inMemory();
        SSConfig.SPEC.correct(config);
        SSConfig.SPEC.setConfig(config);
        transforms();
        hangingEntities();
        carrierTransfer();
        sanitisation();
        verification();
        System.out.println("Creative print checks passed: " + checks);
    }

    private static void transforms() {
        Schematic schematic = new Schematic.Builder(3, 4, 7).build();
        BlockPos origin = new BlockPos(-127, 65, 319);
        Placement placement = new Placement("test.sschem", "Test", origin);
        for (Mirror mirror : Mirror.values()) {
            placement.setMirror(mirror);
            for (Rotation rotation : Rotation.values()) {
                placement.setRotation(rotation);
                for (int y = 0; y < schematic.height(); y++) {
                    for (int z = 0; z < schematic.length(); z++) {
                        for (int x = 0; x < schematic.width(); x++) {
                            Vec3 centre = new Vec3(x + .5, y + .5, z + .5);
                            Vec3 actual = CreativePrinter.transformPoint(schematic, origin, mirror, rotation, centre);
                            Vec3 expected = Vec3.atCenterOf(placement.toWorld(schematic, x, y, z));
                            check(actual.distanceToSqr(expected) < 1.0e-15,
                                    "Entity and block centres must agree for " + mirror + "/" + rotation);
                            Vec3 fractional = new Vec3(x + .03125, y + .875, z + .46875);
                            Vec3 world = CreativePrinter.transformPoint(schematic, origin, mirror, rotation, fractional);
                            check(placement.toLocalPoint(schematic, world).distanceToSqr(fractional) < 1.0e-15,
                                    "Precise entity offsets must survive rotation and mirror round trips");
                        }
                    }
                }
            }
        }
    }

    private static void hangingEntities() {
        Schematic schematic = new Schematic.Builder(3, 4, 7).build();
        BlockPos origin = new BlockPos(-127, 65, 319);
        BlockPos localAnchor = new BlockPos(1, 1, 2);
        Placement placement = new Placement("test.sschem", "Test", origin);
        for (Mirror mirror : Mirror.values()) {
            placement.setMirror(mirror);
            for (Rotation rotation : Rotation.values()) {
                placement.setRotation(rotation);
                for (Direction direction : Direction.values()) {
                    Vec3 position = Vec3.atCenterOf(localAnchor)
                            .subtract(Vec3.atLowerCornerOf(direction.getNormal()).scale(.46875));
                    Vec3 world = CreativePrinter.transformPoint(schematic, origin, mirror, rotation, position);
                    CompoundTag tag = new CompoundTag();
                    tag.putByte("Facing", (byte) direction.get3DDataValue());
                    tag.putByte("ItemRotation", (byte) 0);
                    tag.putInt("TileX", 927);
                    tag.putInt("TileY", 63);
                    tag.putInt("TileZ", -431);
                    CreativePrinter.transformEntityData(tag, new ResourceLocation("minecraft:item_frame"), world, mirror, rotation);
                    BlockPos anchor = new BlockPos(tag.getInt("TileX"), tag.getInt("TileY"), tag.getInt("TileZ"));
                    check(anchor.equals(placement.toWorld(schematic, 1, 1, 2)),
                            "Hanging anchors must be reconstructed from local positions, not copied world Tile coordinates");
                    Direction facing = Direction.from3DDataValue(tag.getByte("Facing"));
                    ListTag angles = tag.getList("Rotation", Tag.TAG_FLOAT);
                    check(angles.getFloat(0) == (facing.getAxis().isHorizontal() ? facing.get2DDataValue() * 90 : 0),
                            "Hanging yaw must agree with the angle vanilla derives from Facing");
                    check(angles.getFloat(1) == (facing.getAxis().isVertical() ? -90 * facing.getAxisDirection().getStep() : 0),
                            "Hanging pitch must agree with the angle vanilla derives from Facing");
                    if (mirror == Mirror.NONE && rotation == Rotation.CLOCKWISE_90 && direction == Direction.UP) {
                        check(tag.getByte("ItemRotation") == 2, "A floor frame must turn its displayed item clockwise with the build");
                    }
                    if (mirror == Mirror.NONE && rotation == Rotation.CLOCKWISE_90 && direction == Direction.DOWN) {
                        check(tag.getByte("ItemRotation") == 6, "A ceiling frame must turn the opposite way when seen from below");
                    }
                }
            }
        }
    }
    private static void carrierTransfer() throws Exception {
        CompoundTag payload = new CompoundTag();
        CompoundTag text = new CompoundTag();
        ListTag messages = new ListTag();
        messages.add(StringTag.valueOf("{\"text\":\"" + "A long sign with detailed directions. ".repeat(32) + "\"}"));
        text.put("messages", messages);
        payload.put("front_text", text);
        ListTag items = new ListTag();
        for (int slot = 0; slot < 27; slot++) {
            CompoundTag item = new CompoundTag();
            item.putByte("Slot", (byte) slot);
            item.putString("id", "minecraft:diamond");
            item.putByte("Count", (byte) 64);
            items.add(item);
        }
        payload.put("Items", items);
        check(payload.toString().length() > 256, "The test must exceed the chat command limit");

        CompoundTag carrierTag = new CompoundTag();
        carrierTag.put("ss_print", payload.copy());
        CompoundTag carrier = new CompoundTag();
        carrier.putByte("Slot", (byte) -106);
        carrier.putString("id", "minecraft:paper");
        carrier.putByte("Count", (byte) 1);
        carrier.put("tag", carrierTag);
        ListTag inventory = new ListTag();
        inventory.add(carrier);
        CompoundTag player = new CompoundTag();
        player.put("Inventory", inventory);

        List<Tag> source = NbtPathArgument.nbtPath().parse(new StringReader(
                "Inventory[{Slot:-106b}].tag.ss_print")).get(player);
        check(source.size() == 1 && source.get(0).equals(payload),
                "The vanilla path must read the complete offhand payload");
        CompoundTag target = new CompoundTag();
        target.putString("id", "minecraft:chest");
        target.putInt("x", -127);
        List<Tag> roots = NbtPathArgument.nbtPath().parse(new StringReader("{}")).get(target);
        check(roots.size() == 1 && roots.get(0) == target, "The merge path must select the root compound itself");
        ((CompoundTag) roots.get(0)).merge((CompoundTag) source.get(0));
        check(target.getList("Items", Tag.TAG_COMPOUND).size() == 27, "All chest slots must transfer together");
        check(target.getCompound("front_text").equals(text), "Long sign text must remain exact");
        check(target.getString("id").equals("minecraft:chest") && target.getInt("x") == -127,
                "Payload merging must preserve the destination identity and coordinates");
        check(NbtUtils.compareNbt(payload, target, true), "Server query comparison must accept the complete copied payload");
        check(("data modify block -29999900 319 -29999900 {} merge from entity @s "
                + "Inventory[{Slot:-106b}].tag.ss_print").length() <= 256,
                "The transfer command must fit even near world-coordinate limits");
    }

    private static void sanitisation() throws Exception {
        Method sanitise = CreativePrinter.class.getDeclaredMethod("sanitise", CompoundTag.class, int.class);
        sanitise.setAccessible(true);
        CompoundTag tag = new CompoundTag();
        tag.putString("Command", "say should never run");
        tag.putBoolean("auto", true);
        tag.putString("LootTable", "minecraft:chests/simple_dungeon");
        CompoundTag nestedItem = new CompoundTag();
        nestedItem.putString("id", "minecraft:command_block");
        CompoundTag nestedData = new CompoundTag();
        nestedData.putString("Command", "say nested command should never run");
        nestedItem.put("BlockEntityTag", nestedData);
        ListTag items = new ListTag();
        items.add(nestedItem);
        tag.put("Items", items);
        ListTag sign = new ListTag();
        sign.add(StringTag.valueOf("{\"text\":\"Directions\",\"extra\":[{\"text\":\"North\","
                + "\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/say unexpected\"}}]}"));
        tag.put("messages", sign);
        CompoundTag profile = new CompoundTag();
        UUID owner = UUID.fromString("a15d1c80-4bc0-4f87-a6af-654280b38fdc");
        profile.putUUID("UUID", owner);
        tag.put("SkullOwner", profile);
        int omitted = (int) sanitise.invoke(null, tag, 0);
        check(omitted >= 5, "Executable copied data omissions must be reported");
        check(!tag.contains("Command") && !tag.contains("auto") && !tag.contains("LootTable"),
                "Commands, automatic execution and unresolved loot must be removed");
        check(!tag.getList("Items", Tag.TAG_COMPOUND).getCompound(0).getCompound("BlockEntityTag").contains("Command"),
                "Commands inside container items must also be removed");
        String cleaned = tag.getList("messages", Tag.TAG_STRING).getString(0);
        check(!cleaned.contains("clickEvent") && cleaned.contains("North"),
                "Clickable execution must be removed while preserving visible sign text");
        check(JsonParser.parseString(cleaned).isJsonObject(), "Sanitised sign text must remain valid JSON");
        check(tag.getCompound("SkullOwner").getUUID("UUID").equals(owner),
                "Sanitisation must preserve head profiles and item modifier identities");
    }

    private static void verification() throws Exception {
        Method verification = CreativePrinter.class.getDeclaredMethod("verificationPayload", CompoundTag.class);
        verification.setAccessible(true);
        CompoundTag payload = new CompoundTag();
        payload.putShort("BurnTime", (short) 200);
        payload.putString("CustomName", "{\"text\":\"Kiln\"}");
        ListTag items = new ListTag();
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:coal");
        item.putByte("Count", (byte) 64);
        items.add(item);
        payload.put("Items", items);
        CompoundTag expected = (CompoundTag) verification.invoke(null, payload);
        check(payload.contains("BurnTime"), "Building the comparison must not mutate the transmitted payload");
        check(!expected.contains("BurnTime"), "Ticking furnace timers cannot require an exact delayed acknowledgement");
        check(expected.getList("Items", Tag.TAG_COMPOUND).equals(items), "Inventory counts must remain part of verification");
        CompoundTag incomplete = expected.copy();
        incomplete.getList("Items", Tag.TAG_COMPOUND).getCompound(0).putByte("Count", (byte) 1);
        check(!NbtUtils.compareNbt(expected, incomplete, true), "Missing resources must fail the NBT acknowledgement");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}