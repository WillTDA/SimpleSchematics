package dev.willtda.simpleschematics.schematic;

import dev.willtda.simpleschematics.SimpleSchematics;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Brings existing Litematica and Forgematica libraries across.
 *
 * <p>Handles every schema version those tools have shipped, including negative
 * region sizes, and merges multiple regions down into the single volume this
 * mod works with. The thumbnail baked into the file is carried over too, so
 * imported builds show a picture in the library straight away.</p>
 */
public final class LitematicImporter {

    public static final String EXTENSION = ".litematic";

    private LitematicImporter() {
    }

    public static Schematic read(Path file) throws IOException {
        CompoundTag root;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            root = NbtIo.readCompressed(in);
        }
        if (root == null || !root.contains("Regions")) {
            throw new IOException("This does not look like a litematic file, there are no regions in it");
        }
        return convert(root, file.getFileName().toString());
    }

    private static Schematic convert(CompoundTag root, String fileName) throws IOException {
        CompoundTag regionsTag = root.getCompound("Regions");
        if (regionsTag.isEmpty()) {
            throw new IOException("The file contains no regions");
        }

        List<Region> regions = new ArrayList<>();
        for (String key : regionsTag.getAllKeys()) {
            Region region = Region.parse(key, regionsTag.getCompound(key));
            if (region != null) {
                regions.add(region);
            }
        }
        if (regions.isEmpty()) {
            throw new IOException("None of the regions in the file could be read");
        }

        // work out the box that covers every region so they can be merged
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Region region : regions) {
            minX = Math.min(minX, region.originX);
            minY = Math.min(minY, region.originY);
            minZ = Math.min(minZ, region.originZ);
            maxX = Math.max(maxX, region.originX + region.width);
            maxY = Math.max(maxY, region.originY + region.height);
            maxZ = Math.max(maxZ, region.originZ + region.length);
        }

        int width = maxX - minX;
        int height = maxY - minY;
        int length = maxZ - minZ;
        long volume = (long) width * height * length;
        if (volume <= 0 || volume > 200_000_000L) {
            throw new IOException("The combined size of " + width + "x" + height + "x" + length + " is not usable");
        }

        Schematic.Builder builder = new Schematic.Builder(width, height, length);

        for (Region region : regions) {
            int offsetX = region.originX - minX;
            int offsetY = region.originY - minY;
            int offsetZ = region.originZ - minZ;

            for (int y = 0; y < region.height; y++) {
                for (int z = 0; z < region.length; z++) {
                    for (int x = 0; x < region.width; x++) {
                        long index = ((long) y * region.length + z) * region.width + x;
                        int id = region.bits.get(index);
                        BlockState state = id >= 0 && id < region.palette.length
                                ? region.palette[id]
                                : Blocks.AIR.defaultBlockState();
                        if (!state.isAir()) {
                            builder.set(offsetX + x, offsetY + y, offsetZ + z, state);
                        }
                    }
                }
            }

            for (Tag tag : region.blockEntities) {
                CompoundTag be = ((CompoundTag) tag).copy();
                int x = be.getInt("x") + offsetX;
                int y = be.getInt("y") + offsetY;
                int z = be.getInt("z") + offsetZ;
                be.remove("x");
                be.remove("y");
                be.remove("z");
                builder.setBlockEntity(new BlockPos(x, y, z), be);
            }

            for (Tag tag : region.entities) {
                CompoundTag entity = ((CompoundTag) tag).copy();
                ListTag pos = entity.getList("Pos", Tag.TAG_DOUBLE);
                if (pos.size() == 3) {
                    ListTag shifted = new ListTag();
                    shifted.add(net.minecraft.nbt.DoubleTag.valueOf(pos.getDouble(0) + offsetX));
                    shifted.add(net.minecraft.nbt.DoubleTag.valueOf(pos.getDouble(1) + offsetY));
                    shifted.add(net.minecraft.nbt.DoubleTag.valueOf(pos.getDouble(2) + offsetZ));
                    entity.put("Pos", shifted);
                }
                builder.addEntity(entity);
            }
        }

        CompoundTag metaTag = root.getCompound("Metadata");
        Schematic.Meta meta = builder.meta();
        meta.name = metaTag.contains("Name") && !metaTag.getString("Name").isBlank()
                ? metaTag.getString("Name")
                : stripExtension(fileName);
        meta.author = metaTag.getString("Author");
        meta.description = metaTag.getString("Description");
        meta.source = "Imported from " + fileName;
        meta.created = metaTag.contains("TimeCreated") ? metaTag.getLong("TimeCreated") : System.currentTimeMillis();
        meta.modified = System.currentTimeMillis();

        if (metaTag.contains("PreviewImageData")) {
            int[] pixels = metaTag.getIntArray("PreviewImageData");
            // Litematica always writes a square thumbnail
            int side = (int) Math.round(Math.sqrt(pixels.length));
            if (side > 0 && side * side == pixels.length) {
                meta.previewPixels = pixels;
                meta.previewWidth = side;
                meta.previewHeight = side;
            }
        }

        SimpleSchematics.LOG.info("Imported {} as {}x{}x{} from {} region(s)",
                meta.name, width, height, length, regions.size());
        return builder.build();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** One parsed region, already normalised so the size is always positive. */
    private static final class Region {
        int originX, originY, originZ;
        int width, height, length;
        BlockState[] palette;
        LitematicBitArray bits;
        ListTag blockEntities;
        ListTag entities;

        static Region parse(String name, CompoundTag tag) {
            try {
                CompoundTag posTag = tag.getCompound("Position");
                CompoundTag sizeTag = tag.getCompound("Size");
                int px = posTag.getInt("x"), py = posTag.getInt("y"), pz = posTag.getInt("z");
                int sx = sizeTag.getInt("x"), sy = sizeTag.getInt("y"), sz = sizeTag.getInt("z");

                Region region = new Region();
                // a negative size means the region grows the other way from the position
                region.originX = sx < 0 ? px + sx + 1 : px;
                region.originY = sy < 0 ? py + sy + 1 : py;
                region.originZ = sz < 0 ? pz + sz + 1 : pz;
                region.width = Math.abs(sx);
                region.height = Math.abs(sy);
                region.length = Math.abs(sz);

                if (region.width == 0 || region.height == 0 || region.length == 0) {
                    return null;
                }

                ListTag paletteTag = tag.getList("BlockStatePalette", Tag.TAG_COMPOUND);
                region.palette = new BlockState[Math.max(1, paletteTag.size())];
                region.palette[0] = Blocks.AIR.defaultBlockState();
                for (int i = 0; i < paletteTag.size(); i++) {
                    region.palette[i] = SchematicIO.readState(paletteTag.getCompound(i));
                }

                long[] packed = tag.getLongArray("BlockStates");
                long volume = (long) region.width * region.height * region.length;
                int bits = LitematicBitArray.bitsFor(region.palette.length);
                long needed = LitematicBitArray.expectedLongCount(volume, bits);
                if (packed.length < needed) {
                    SimpleSchematics.LOG.warn("Region {} has {} longs but needs {}, it may be truncated",
                            name, packed.length, needed);
                }
                region.bits = new LitematicBitArray(bits, volume, packed);

                region.blockEntities = tag.getList("TileEntities", Tag.TAG_COMPOUND);
                region.entities = tag.getList("Entities", Tag.TAG_COMPOUND);
                return region;
            } catch (Exception e) {
                SimpleSchematics.LOG.error("Could not read the region {}", name, e);
                return null;
            }
        }
    }

    /** Convenience for the scan side, kept here so both formats share one home. */
    public static AABB boxOf(Schematic schematic) {
        return new AABB(0, 0, 0, schematic.width(), schematic.height(), schematic.length());
    }
}
