package dev.willtda.simpleschematics.schematic;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads and writes the mod's own container, a gzipped NBT file ending in
 * {@code .sschem}. Flat and boring on purpose, so it stays readable with any
 * NBT editor and easy to fix by hand if something ever goes wrong.
 */
public final class SchematicIO {

    public static final String EXTENSION = ".sschem";
    private static final int FORMAT_VERSION = 1;

    private SchematicIO() {
    }

    private static HolderGetter<Block> blockLookup() {
        return BuiltInRegistries.BLOCK.asLookup();
    }

    public static void write(Schematic schematic, Path file) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("FormatVersion", FORMAT_VERSION);
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());

        Schematic.Meta meta = schematic.meta();
        CompoundTag metaTag = new CompoundTag();
        metaTag.putString("Name", meta.name);
        metaTag.putString("Author", meta.author);
        metaTag.putString("Description", meta.description);
        metaTag.putString("Source", meta.source);
        metaTag.putLong("Created", meta.created);
        metaTag.putLong("Modified", meta.modified);
        if (schematic.hasPreviewImage()) {
            metaTag.putIntArray("Preview", meta.previewPixels);
            metaTag.putInt("PreviewWidth", meta.previewWidth);
            metaTag.putInt("PreviewHeight", meta.previewHeight);
        }
        root.put("Metadata", metaTag);

        CompoundTag sizeTag = new CompoundTag();
        sizeTag.putInt("x", schematic.width());
        sizeTag.putInt("y", schematic.height());
        sizeTag.putInt("z", schematic.length());
        root.put("Size", sizeTag);

        ListTag paletteTag = new ListTag();
        for (BlockState state : schematic.palette()) {
            paletteTag.add(NbtUtils.writeBlockState(state));
        }
        root.put("Palette", paletteTag);
        root.putIntArray("Blocks", schematic.rawStates());

        ListTag beList = new ListTag();
        for (Map.Entry<BlockPos, CompoundTag> entry : schematic.blockEntities().entrySet()) {
            CompoundTag copy = entry.getValue().copy();
            copy.putInt("x", entry.getKey().getX());
            copy.putInt("y", entry.getKey().getY());
            copy.putInt("z", entry.getKey().getZ());
            beList.add(copy);
        }
        root.put("BlockEntities", beList);

        ListTag entityList = new ListTag();
        for (CompoundTag tag : schematic.entities()) {
            entityList.add(tag.copy());
        }
        root.put("Entities", entityList);

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
            NbtIo.writeCompressed(root, out);
        }
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static Schematic read(Path file) throws IOException {
        CompoundTag root;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            root = NbtIo.readCompressed(in);
        }
        return fromTag(root);
    }

    public static Schematic fromTag(CompoundTag root) throws IOException {
        CompoundTag sizeTag = root.getCompound("Size");
        int w = sizeTag.getInt("x");
        int h = sizeTag.getInt("y");
        int l = sizeTag.getInt("z");
        if (w <= 0 || h <= 0 || l <= 0) {
            throw new IOException("The schematic has an invalid size of " + w + "x" + h + "x" + l);
        }

        ListTag paletteTag = root.getList("Palette", Tag.TAG_COMPOUND);
        BlockState[] palette = new BlockState[Math.max(1, paletteTag.size())];
        for (int i = 0; i < paletteTag.size(); i++) {
            palette[i] = readState(paletteTag.getCompound(i));
        }
        if (paletteTag.isEmpty()) {
            palette[0] = Blocks.AIR.defaultBlockState();
        }

        int[] blocks = root.getIntArray("Blocks");
        int expected = w * h * l;
        if (blocks.length != expected) {
            throw new IOException("The block data is " + blocks.length + " long but should be " + expected);
        }
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] < 0 || blocks[i] >= palette.length) {
                blocks[i] = 0;
            }
        }

        Schematic.Builder builder = new Schematic.Builder(w, h, l);
        // rebuild through the builder so the palette is deduplicated consistently
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    int id = blocks[(y * l + z) * w + x];
                    builder.set(x, y, z, palette[id]);
                }
            }
        }

        for (Tag tag : root.getList("BlockEntities", Tag.TAG_COMPOUND)) {
            CompoundTag be = ((CompoundTag) tag).copy();
            BlockPos pos = new BlockPos(be.getInt("x"), be.getInt("y"), be.getInt("z"));
            be.remove("x");
            be.remove("y");
            be.remove("z");
            builder.setBlockEntity(pos, be);
        }
        for (Tag tag : root.getList("Entities", Tag.TAG_COMPOUND)) {
            builder.addEntity(((CompoundTag) tag).copy());
        }

        CompoundTag metaTag = root.getCompound("Metadata");
        Schematic.Meta meta = builder.meta();
        meta.name = metaTag.contains("Name") ? metaTag.getString("Name") : "Untitled";
        meta.author = metaTag.getString("Author");
        meta.description = metaTag.getString("Description");
        meta.source = metaTag.contains("Source") ? metaTag.getString("Source") : "Simple Schematics";
        meta.created = metaTag.getLong("Created");
        meta.modified = metaTag.getLong("Modified");
        if (metaTag.contains("Preview")) {
            meta.previewPixels = metaTag.getIntArray("Preview");
            meta.previewWidth = metaTag.getInt("PreviewWidth");
            meta.previewHeight = metaTag.getInt("PreviewHeight");
        }

        return builder.build();
    }

    /** Shared with the litematic importer, both formats use the vanilla state layout. */
    static BlockState readState(CompoundTag tag) {
        try {
            return NbtUtils.readBlockState(blockLookup(), tag);
        } catch (Exception e) {
            return Blocks.AIR.defaultBlockState();
        }
    }
}
