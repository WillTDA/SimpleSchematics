package dev.willtda.simpleschematics.client;

import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Turns the current selection into a schematic.
 *
 * <p>One caveat worth knowing about, and it applies to every client side tool
 * of this kind: the server only tells your client what is inside a container
 * while you have it open. Ticking the container contents box therefore captures
 * whatever your client happens to know, which in single player is everything
 * and on a server is usually nothing.</p>
 */
public final class ScanManager {

    private ScanManager() {
    }

    /** Options gathered from the save dialogue. */
    public record Options(String name, String description, boolean includeEntities, boolean includeContainerContents) {
    }

    /** Anything that was skipped, so the dialogue can be honest about it. */
    public record Result(Schematic schematic, int blockCount, int entityCount, int blockEntityCount) {
    }

    public static Result capture(ScanSelection selection, Options options) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !selection.isComplete()) {
            return null;
        }

        BlockPos min = selection.min();
        BlockPos max = selection.max();
        Vec3i size = selection.size();

        Schematic.Builder builder = new Schematic.Builder(size.getX(), size.getY(), size.getZ());
        Schematic.Meta meta = builder.meta();
        meta.name = options.name();
        meta.description = options.description();
        meta.author = mc.player != null ? mc.player.getGameProfile().getName() : "";
        meta.source = "Scanned in game";

        int blocks = 0;
        int blockEntities = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    cursor.set(min.getX() + x, min.getY() + y, min.getZ() + z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    builder.set(x, y, z, state);
                    blocks++;

                    BlockEntity be = level.getBlockEntity(cursor);
                    if (be != null) {
                        CompoundTag tag = be.saveWithId();
                        tag.remove("x");
                        tag.remove("y");
                        tag.remove("z");
                        if (!options.includeContainerContents()) {
                            stripContents(tag);
                        }
                        builder.setBlockEntity(new BlockPos(x, y, z), tag);
                        blockEntities++;
                    }
                }
            }
        }

        int entityCount = 0;
        if (options.includeEntities()) {
            AABB box = new AABB(min.getX(), min.getY(), min.getZ(),
                    max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
            List<Entity> found = level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player));
            for (Entity entity : found) {
                try {
                    CompoundTag tag = new CompoundTag();
                    if (!entity.saveAsPassenger(tag)) {
                        // some entities refuse to serialise, skip them rather than fail the scan
                        tag = new CompoundTag();
                        entity.saveWithoutId(tag);
                        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                        tag.putString("id", id.toString());
                    }
                    ListTag pos = new ListTag();
                    pos.add(DoubleTag.valueOf(entity.getX() - min.getX()));
                    pos.add(DoubleTag.valueOf(entity.getY() - min.getY()));
                    pos.add(DoubleTag.valueOf(entity.getZ() - min.getZ()));
                    tag.put("Pos", pos);
                    tag.remove("UUID");
                    builder.addEntity(tag);
                    entityCount++;
                } catch (Exception e) {
                    SimpleSchematics.LOG.warn("Skipped an entity that could not be saved", e);
                }
            }
        }

        Schematic schematic = builder.build();
        return new Result(schematic, blocks, entityCount, blockEntities);
    }

    /** Removes the payload from containers while keeping the block entity itself. */
    private static void stripContents(CompoundTag tag) {
        tag.remove("Items");
        tag.remove("Inventory");
        tag.remove("RecordItem");
        tag.remove("Book");
        tag.remove("Bees");
        tag.remove("LootTable");
        tag.remove("LootTableSeed");
    }
}
