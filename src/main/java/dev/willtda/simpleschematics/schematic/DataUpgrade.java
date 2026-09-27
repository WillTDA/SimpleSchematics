package dev.willtda.simpleschematics.schematic;

import com.mojang.datafixers.DSL;
import com.mojang.serialization.Dynamic;
import dev.willtda.simpleschematics.SimpleSchematics;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

/**
 * Brings block states, block entities and entities saved by an older game up
 * to the running one, through the same data fixers that upgrade an old world.
 *
 * <p>Without it a schematic saved on 1.20.1 and opened on 1.21.1 kept its
 * items in the old layout, which the paste would hand to a server that no
 * longer reads it. A file from this version or a newer one is left alone,
 * because there is no fixing data downwards.</p>
 */
final class DataUpgrade {

    private static final int CURRENT = SharedConstants.getCurrentVersion().getDataVersion().getVersion();

    private final int from;

    /** @param from the data version the file was written with, or zero if it never said */
    DataUpgrade(int from) {
        this.from = from;
    }

    CompoundTag blockState(CompoundTag tag) {
        return update(References.BLOCK_STATE, tag);
    }

    /** The fixers find a block entity's type by its id, so one without is left as it is. */
    CompoundTag blockEntity(CompoundTag tag) {
        return tag.contains("id", Tag.TAG_STRING) ? update(References.BLOCK_ENTITY, tag) : tag;
    }

    CompoundTag entity(CompoundTag tag) {
        return tag.contains("id", Tag.TAG_STRING) ? update(References.ENTITY, tag) : tag;
    }

    private CompoundTag update(DSL.TypeReference type, CompoundTag tag) {
        if (from <= 0 || from >= CURRENT) {
            return tag;
        }
        try {
            Tag fixed = DataFixers.getDataFixer()
                    .update(type, new Dynamic<>(NbtOps.INSTANCE, tag), from, CURRENT)
                    .getValue();
            return fixed instanceof CompoundTag compound ? compound : tag;
        } catch (RuntimeException e) {
            SimpleSchematics.LOG.warn("Could not upgrade a {} from data version {}", type.typeName(), from, e);
            return tag;
        }
    }
}
