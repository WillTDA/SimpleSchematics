package dev.willtda.simpleschematics.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Function;

/** Section-sized cached geometry, with one transparent pass and camera-relative sorting. */
public final class BakedSchematic implements AutoCloseable {
    private static final int SECTION = 16;
    private static final int BAKE_BUDGET = 6;
    private static final int SORT_BUDGET = 8;

    private final Schematic schematic;
    private final List<Section> sections = new ArrayList<>();
    private final Deque<Section> pending = new ArrayDeque<>();
    private final SchematicLevel view;
    private final Vector3f eye = new Vector3f();
    private int layerMin;
    private int layerMax;
    private int sortCursor;
    private boolean closed;
    private BitSet correctMask;
    private int maskVersion = -1;

    public BakedSchematic(Schematic schematic) {
        this.schematic = schematic;
        this.layerMax = Math.max(0, schematic.height() - 1);
        this.view = new SchematicLevel(Minecraft.getInstance().level, schematic, BlockPos.ZERO);
        for (int y = 0; y < schematic.height(); y += SECTION) {
            for (int z = 0; z < schematic.length(); z += SECTION) {
                for (int x = 0; x < schematic.width(); x += SECTION) {
                    sections.add(new Section(x, y, z));
                }
            }
        }
        queueAll();
    }

    public Schematic schematic() { return schematic; }
    public static int sectionsAcross(int size) { return Math.max(1, (size + SECTION - 1) / SECTION); }
    public static int sectionIndexOf(Schematic schematic, int x, int y, int z) {
        return ((y / SECTION) * sectionsAcross(schematic.length()) + z / SECTION)
                * sectionsAcross(schematic.width()) + x / SECTION;
    }
    /**
     * Elements a re-sort builder must be able to hold. {@link BufferBuilder}
     * allocates six bytes per element, and the buffer has to cover both the
     * vertex slice {@code upload} insists on taking and the freshly written
     * indices, which are at most four bytes each.
     *
     * <p>The vertex size comes from the format the mesh was actually uploaded
     * with rather than {@link DefaultVertexFormat#BLOCK}. A shader mod widens
     * the block format behind every builder while a pack is loaded, and sizing
     * for the plain one then left every re-sort throwing on that path.</p>
     */
    private static int sortCapacity(int vertices, VertexFormat format) {
        long vertexBytes = (long) vertices * format.getVertexSize();
        long indexBytes = (long) VertexFormat.Mode.QUADS.indexCount(vertices) * 4L + 16L;
        long elements = (vertexBytes + indexBytes + 5L) / 6L;
        return (int) Math.max(4096L, Math.min(elements, Integer.MAX_VALUE / 6L));
    }

    // ---- shared builders --------------------------------------------------

    /**
     * The builders every bake and re-sort goes through.
     *
     * <p>A {@link BufferBuilder} takes its memory straight from the native
     * allocator and never gives it back; vanilla only ever makes a fixed
     * handful and reuses them. Making a fresh one per section, and another per
     * re-sort, leaked a few megabytes each time, and a walk round a large
     * build re-sorts eight sections a frame. The game bogged down as the
     * process swelled and, once the driver could no longer find memory for a
     * buffer, the geometry it was handed was whatever happened to be there.
     * One set, kept for the life of the game, is the vanilla arrangement.</p>
     *
     * <p>All of it runs on the render thread, so nothing here needs a lock.</p>
     */
    private static final BufferBuilder BAKE = new BufferBuilder(262144);
    private static final Map<ResourceLocation, BufferBuilder> SHEET_BUILDERS = new LinkedHashMap<>();
    private static BufferBuilder sortBuilder;
    private static int sortBuilderElements;

    /** A builder ready to begin, however the last use of it ended. */
    private static BufferBuilder ready(BufferBuilder builder) {
        if (builder.building()) {
            builder.end().release();
        }
        return builder;
    }

    /**
     * The re-sort builder, replaced by a bigger one when a mesh outgrows it.
     * It only ever grows, and the largest section sets the ceiling, so the
     * old ones it leaves behind are a handful over the life of the game.
     */
    private static BufferBuilder sortBuilder(int elements) {
        if (sortBuilder == null || elements > sortBuilderElements) {
            sortBuilderElements = Math.max(elements, sortBuilderElements * 2);
            sortBuilder = new BufferBuilder(sortBuilderElements);
        }
        return ready(sortBuilder);
    }

    public boolean isReady() { return pending.isEmpty(); }
    public int pendingCount() { return pending.size(); }
    public void setTintOrigin(BlockPos origin) { view.setTintOrigin(origin); }

    public void setLayerRange(int min, int max) {
        int nextMin = Math.max(0, Math.min(min, schematic.height() - 1));
        int nextMax = Math.max(nextMin, Math.min(max, schematic.height() - 1));
        if (nextMin == layerMin && nextMax == layerMax) return;
        int oldMin = layerMin;
        int oldMax = layerMax;
        layerMin = nextMin;
        layerMax = nextMax;
        view.setVisibleBlocks(layerMin, layerMax, correctMask);
        // Adjacent slices matter as well: a face must appear when its neighbour
        // leaves the visible range, even across a sixteen-block section boundary.
        BitSet changed = new BitSet();
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            if (s.overlaps(oldMin, oldMax) != s.overlaps(layerMin, layerMax)
                    || s.straddles(oldMin, oldMax) || s.straddles(layerMin, layerMax)) {
                changed.set(i);
            }
        }
        queueWithNeighbours(changed);
    }

    public void setCorrectMask(BitSet mask, BitSet dirtySections, int version) {
        boolean first = correctMask == null;
        correctMask = mask;
        view.setVisibleBlocks(layerMin, layerMax, mask);
        if (first) {
            maskVersion = version;
            queueAll();
        } else if (version != maskVersion) {
            maskVersion = version;
            queueWithNeighbours(dirtySections);
        }
    }

    public void clearCorrectMask() {
        if (correctMask == null) return;
        correctMask = null;
        maskVersion = -1;
        view.setVisibleBlocks(layerMin, layerMax, null);
        queueAll();
    }

    private void queueWithNeighbours(BitSet changed) {
        BitSet expanded = SectionVisibility.withNeighbours(changed,
                sectionsAcross(schematic.width()), sectionsAcross(schematic.height()),
                sectionsAcross(schematic.length()));
        for (int i = expanded.nextSetBit(0); i >= 0; i = expanded.nextSetBit(i + 1)) {
            queue(sections.get(i));
        }
    }

    private void queue(Section section) {
        if (!section.queued) {
            section.queued = true;
            pending.add(section);
        }
    }

    private void queueAll() {
        pending.clear();
        for (Section section : sections) {
            section.queued = false;
            queue(section);
        }
    }

    public void bakeStep() {
        if (closed) return;
        RenderSystem.assertOnRenderThread();
        int budget = BAKE_BUDGET;
        while (budget > 0 && !pending.isEmpty()) {
            Section section = pending.removeFirst();
            section.queued = false;
            if (!section.overlaps(layerMin, layerMax)) {
                section.discard();
                continue;
            }
            budget--;
            try {
                section.bake();
            } catch (Exception e) {
                section.discard();
                SimpleSchematics.LOG.error("Could not bake a section of {}", schematic.meta().name, e);
            }
        }
    }

    public void draw(PoseStack pose, Matrix4f projection, float alpha) {
        draw(pose, projection, alpha, false);
    }

    /** Library previews write depth; in-world ghosts only read the real world's depth. */
    public void draw(PoseStack pose, Matrix4f projection, float alpha, boolean depthWrite) {
        ShaderInstance shader = HologramShader.get();
        // The library preview is a GUI draw, which no shader pack touches, so
        // only the world pass ever needs the compatible path.
        boolean shaded = !depthWrite && ShaderPackCompat.shaderPackInUse();
        if (closed || !Float.isFinite(alpha) || alpha <= 0.0F) return;
        if (shader == null && !shaded) return;
        // GUI vertices normally receive RenderSystem's separate model-view
        // translation when a buffer is flushed. Direct VBO draws need it too.
        Matrix4f base = depthWrite
                ? new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose())
                : pose.last().pose();
        new Matrix4f(base).invert().transformPosition(0.0F, 0.0F, 0.0F, eye);
        if (!Float.isFinite(eye.x) || !Float.isFinite(eye.y) || !Float.isFinite(eye.z)) return;

        // Re-sort index buffers without rebuilding block models. A rotating
        // round-robin budget prevents far sections starving the near ones.
        int remaining = SORT_BUDGET;
        int inspected = 0;
        while (remaining > 0 && inspected++ < sections.size()) {
            Section section = sections.get(sortCursor++ % sections.size());
            if (sortCursor == Integer.MAX_VALUE) sortCursor = 0;
            if (!section.queued && section.overlaps(layerMin, layerMax) && section.needsSort()) {
                section.sort();
                remaining--;
            }
        }
        List<Section> ordered = sections.stream()
                .filter(s -> !s.queued && s.hasGeometry() && s.overlaps(layerMin, layerMax))
                .sorted(Comparator.comparingDouble(Section::distanceSquared).reversed()).toList();
        if (ordered.isEmpty()) return;

        float ghostAlpha = Math.min(1.0F, alpha);
        try (GhostRenderState ignored = new GhostRenderState(ghostAlpha, depthWrite)) {
            if (shaded) {
                drawShaded(ordered, base, projection, ghostAlpha);
            } else {
                shader.safeGetUniform("WorldPass").set(depthWrite ? 0.0F : 1.0F);
                shader.safeGetUniform("NearFade").set(SSConfig.INSTANCE.hologramNearFade.get() ? 1.0F : 0.0F);
                shader.safeGetUniform("FadeStart").set(0.35F);
                shader.safeGetUniform("FadeEnd").set(SSConfig.INSTANCE.hologramFadeDistance.get().floatValue());
                // No vanilla render-type setup may run here: solid/cutout types
                // disable blending or write depth and would undo the ghost pass.
                for (Section section : ordered) {
                    section.blocks.draw(section, base, projection, shader);
                }
                drawSheets(ordered, base, projection, shader);
            }
        } finally {
            VertexBuffer.unbind();
        }
    }

    /**
     * The path taken while a shader pack owns the level render.
     *
     * <p>Going through a vanilla render type is the whole point: Iris and
     * Oculus patch the programs behind the render types the same way they patch
     * terrain, so the geometry reaches their gbuffer and survives the passes
     * that follow. This mod's own shader is ignored inside that pipeline, which
     * is why the ghosts were not there at all.</p>
     *
     * <p>The near fade and the distance fade live in that shader and are lost
     * here. Opacity is not: it rides on ColorModulator, which every vanilla
     * shader honours. The polygon offset stays on, because the render type
     * does not set it the way a ghost needs it.</p>
     *
     * <p>Depth is written here, unlike on the mod's own path. A pack's later
     * passes read the depth buffer back to decide what each pixel is, and a
     * ghost that left no depth behind was taken for whatever stood behind it:
     * seen against the sky, its faces were painted over with sky and fog and
     * lost their texture. Glass and water write depth for the same reason.
     * The sections are already drawn far to near, so writing it costs the
     * ghost nothing of its own.</p>
     */
    private void drawShaded(List<Section> ordered, Matrix4f base, Matrix4f projection, float alpha) {
        RenderType type = RenderType.translucent();
        type.setupRenderState();
        try {
            ShaderInstance shader = RenderSystem.getShader();
            if (shader == null) {
                return;
            }
            RenderSystem.depthMask(true);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.enablePolygonOffset();
            RenderSystem.polygonOffset(-1.0F, -2.0F);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
            for (Section section : ordered) {
                section.blocks.draw(section, base, projection, shader);
            }
            drawSheets(ordered, base, projection, shader);
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            type.clearRenderState();
        }
    }

    /**
     * The chest and bed meshes, drawn after the block geometry with their own
     * texture sheet bound. The shader reads its sampler from the render
     * system on every draw, so swapping the texture between draws is all it
     * takes, and the block atlas goes back at the end for whoever is next.
     */
    private static void drawSheets(List<Section> ordered, Matrix4f base, Matrix4f projection, ShaderInstance shader) {
        boolean swapped = false;
        for (Section section : ordered) {
            for (Mesh mesh : section.sheets) {
                if (mesh.buffer == null) continue;
                RenderSystem.setShaderTexture(0, mesh.texture);
                swapped = true;
                mesh.draw(section, base, projection, shader);
            }
        }
        if (swapped) {
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        }
    }

    @Override
    public void close() {
        closed = true;
        pending.clear();
        for (Section section : sections) section.discard();
        sections.clear();
    }

    /**
     * One uploaded buffer and what it takes to re-sort it. A section has one
     * for its block geometry and one more for each texture sheet a chest or
     * bed in it needed.
     */
    private final class Mesh {
        final ResourceLocation texture;
        VertexBuffer buffer;
        BufferBuilder.SortState sortState;
        Vector3f sortedEye;
        /** Vertices behind {@link #sortState}; needed to size the re-sort buffer. */
        int sortVertices;
        /** The format the vertices went up in, which a shader mod may have widened. */
        VertexFormat format;

        Mesh(ResourceLocation texture) { this.texture = texture; }

        boolean needsSort() {
            return buffer != null && sortState != null
                    && (sortedEye == null || sortedEye.distanceSquared(eye) >= 0.25F);
        }

        void discard() {
            if (buffer != null) buffer.close();
            buffer = null;
            sortState = null;
            sortedEye = null;
            sortVertices = 0;
            format = null;
        }

        /**
         * Re-sorts the existing quads for the current eye without rebuilding any
         * block models.
         *
         * <p>The builder has to be big enough up front to hold the vertex data
         * it will never actually be given. {@code DrawState.vertexBufferSize}
         * ignores the index-only flag, so {@code VertexBuffer.upload} always
         * slices out {@code vertexCount * vertexSize} bytes, and a buffer
         * smaller than that makes the slice throw. Vanilla never trips over
         * this because chunk re-sorts borrow one of the big pooled builders,
         * which is what the shared one here is.</p>
         */
        /**
         * @return false when the mesh cannot be re-sorted and wants baking
         *         again instead
         */
        boolean sort(Section section) {
            BufferBuilder indices = sortBuilder(sortCapacity(sortVertices, format));
            indices.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            indices.restoreSortState(sortState);
            indices.setQuadSorting(VertexSorting.byDistance(eye.x - section.x, eye.y - section.y, eye.z - section.z));
            BufferBuilder.RenderedBuffer rendered = indices.end();
            // A shader pack coming or going changes the layout every builder
            // writes. Uploading indices in the new layout over vertices in the
            // old one would point the attributes at the wrong bytes, and the
            // ghost would fly off in every direction until something rebaked
            // it. Only a fresh bake can put the vertices in the new layout.
            if (!rendered.drawState().format().equals(format)) {
                rendered.release();
                return false;
            }
            buffer.bind();
            try { buffer.upload(rendered); }
            finally { VertexBuffer.unbind(); }
            sortedEye = new Vector3f(eye);
            return true;
        }

        /** Takes the finished builder, sorted from the current eye, and uploads it. Empty geometry leaves no buffer. */
        void upload(Section section, BufferBuilder builder) {
            builder.setQuadSorting(VertexSorting.byDistance(eye.x - section.x, eye.y - section.y, eye.z - section.z));
            BufferBuilder.SortState sorted = builder.getSortState();
            BufferBuilder.RenderedBuffer rendered = builder.end();
            if (rendered.isEmpty()) { rendered.release(); return; }
            int vertices = rendered.drawState().vertexCount();
            VertexFormat uploaded = rendered.drawState().format();
            buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            // upload() releases the rendered buffer, so read the count first
            try { buffer.upload(rendered); }
            finally { VertexBuffer.unbind(); }
            sortState = sorted;
            sortVertices = vertices;
            format = uploaded;
            sortedEye = new Vector3f(eye);
        }

        void draw(Section section, Matrix4f base, Matrix4f projection, ShaderInstance shader) {
            if (buffer == null) return;
            buffer.bind();
            buffer.drawWithShader(new Matrix4f(base).translate(section.x, section.y, section.z), projection, shader);
        }
    }

    private final class Section {
        final int x, y, z;
        final Mesh blocks = new Mesh(TextureAtlas.LOCATION_BLOCKS);
        final List<Mesh> sheets = new ArrayList<>();
        boolean queued;

        Section(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        boolean overlaps(int min, int max) { return y <= max && y + SECTION - 1 >= min; }
        boolean straddles(int min, int max) {
            int top = y + SECTION - 1;
            return (min > y && min <= top) || (max >= y && max < top);
        }
        double distanceSquared() { return eye.distanceSquared(x + 8.0F, y + 8.0F, z + 8.0F); }
        boolean hasGeometry() {
            if (blocks.buffer != null) return true;
            for (Mesh mesh : sheets) if (mesh.buffer != null) return true;
            return false;
        }
        boolean needsSort() {
            if (blocks.needsSort()) return true;
            for (Mesh mesh : sheets) if (mesh.needsSort()) return true;
            return false;
        }
        void discard() {
            blocks.discard();
            for (Mesh mesh : sheets) mesh.discard();
            sheets.clear();
        }
        void sort() {
            sortMesh(blocks);
            for (Mesh mesh : sheets) sortMesh(mesh);
        }
        private void sortMesh(Mesh mesh) {
            if (!mesh.needsSort()) return;
            try {
                if (!mesh.sort(this)) {
                    queue(this);
                }
            } catch (Exception e) {
                // A mesh that cannot be re-sorted still draws, just in its
                // old order. Losing the whole frame would be worse.
                mesh.sortState = null;
                mesh.sortVertices = 0;
                SimpleSchematics.LOG.error("Could not re-sort a section of {}", schematic.meta().name, e);
            }
        }
        void bake() {
            discard();
            if (!overlaps(layerMin, layerMax)) return;
            var dispatcher = Minecraft.getInstance().getBlockRenderer();
            RandomSource random = RandomSource.create();
            PoseStack pose = new PoseStack();
            BufferBuilder builder = ready(BAKE);
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            // Builders for the chest and bed sheets, begun only when something
            // asks for them, since most sections have neither. The ones in use
            // this time are noted so that only they are ended.
            Map<ResourceLocation, BufferBuilder> sheetBuilders = new LinkedHashMap<>();
            Function<ResourceLocation, VertexConsumer> sheetFor = texture -> {
                // a conduit's shell lives on the block atlas, so it joins the block mesh
                if (texture.equals(TextureAtlas.LOCATION_BLOCKS)) return builder;
                return sheetBuilders.computeIfAbsent(texture, t -> {
                    BufferBuilder b = ready(SHEET_BUILDERS.computeIfAbsent(t, k -> new BufferBuilder(16384)));
                    b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                    return b;
                });
            };
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            try {
                for (int by = Math.max(y, layerMin); by < Math.min(y + SECTION, layerMax + 1); by++) {
                    for (int bz = z; bz < Math.min(z + SECTION, schematic.length()); bz++) {
                        for (int bx = x; bx < Math.min(x + SECTION, schematic.width()); bx++) {
                            cursor.set(bx, by, bz);
                            BlockState state = view.getBlockState(cursor);
                            if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE) continue;
                            var model = dispatcher.getBlockModel(state);
                            pose.pushPose();
                            pose.translate(bx - x, by - y, bz - z);
                            try {
                                if (state.getRenderShape() != RenderShape.MODEL) {
                                    // beds, chests and the like: a block entity renderer's job, stood in for
                                    EntityBlockStandIn.bake(view, state, cursor, pose, builder,
                                            model.getParticleIcon(ModelData.EMPTY), sheetFor);
                                    continue;
                                }
                                random.setSeed(state.getSeed(cursor));
                                for (RenderType type : model.getRenderTypes(state, random, ModelData.EMPTY)) {
                                    dispatcher.getModelRenderer().tesselateBlock(view, model, state, cursor,
                                            pose, builder, true, random, state.getSeed(cursor),
                                            OverlayTexture.NO_OVERLAY, ModelData.EMPTY, type);
                                }
                            } finally { pose.popPose(); }
                        }
                    }
                }
                // Every material is translucent in a hologram, not just glass.
                blocks.upload(this, builder);
                for (Map.Entry<ResourceLocation, BufferBuilder> entry : sheetBuilders.entrySet()) {
                    Mesh mesh = new Mesh(entry.getKey());
                    mesh.upload(this, entry.getValue());
                    if (mesh.buffer != null) sheets.add(mesh);
                }
            } catch (Exception e) {
                if (builder.building()) builder.end().release();
                for (BufferBuilder b : sheetBuilders.values()) if (b.building()) b.end().release();
                throw e;
            }
        }
    }
}
