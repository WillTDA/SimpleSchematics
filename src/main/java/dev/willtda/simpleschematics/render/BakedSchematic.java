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
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;

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
     */
    private static int sortCapacity(int vertices) {
        long vertexBytes = (long) vertices * DefaultVertexFormat.BLOCK.getVertexSize();
        long indexBytes = (long) VertexFormat.Mode.QUADS.indexCount(vertices) * 4L + 16L;
        long elements = (vertexBytes + indexBytes + 5L) / 6L;
        return (int) Math.max(4096L, Math.min(elements, Integer.MAX_VALUE / 6L));
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
        if (closed || shader == null || !Float.isFinite(alpha) || alpha <= 0.0F) return;
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
                try {
                    section.sort();
                } catch (Exception e) {
                    // A section that cannot be re-sorted still draws, just in
                    // its old order. Losing the whole frame would be worse.
                    section.sortState = null;
                    section.sortVertices = 0;
                    SimpleSchematics.LOG.error("Could not re-sort a section of {}", schematic.meta().name, e);
                }
                remaining--;
            }
        }
        List<Section> ordered = sections.stream()
                .filter(s -> !s.queued && s.buffer != null && s.overlaps(layerMin, layerMax))
                .sorted(Comparator.comparingDouble(Section::distanceSquared).reversed()).toList();
        if (ordered.isEmpty()) return;

        try (GhostRenderState ignored = new GhostRenderState(Math.min(1.0F, alpha), depthWrite)) {
            shader.safeGetUniform("WorldPass").set(depthWrite ? 0.0F : 1.0F);
            shader.safeGetUniform("NearFade").set(SSConfig.INSTANCE.hologramNearFade.get() ? 1.0F : 0.0F);
            shader.safeGetUniform("FadeStart").set(0.35F);
            shader.safeGetUniform("FadeEnd").set(SSConfig.INSTANCE.hologramFadeDistance.get().floatValue());
            // No vanilla render-type setup may run here: solid/cutout types
            // disable blending or write depth and would undo the ghost pass.
            for (Section section : ordered) {
                section.buffer.bind();
                section.buffer.drawWithShader(new Matrix4f(base).translate(section.x, section.y, section.z), projection, shader);
            }
        } finally {
            VertexBuffer.unbind();
        }
    }

    @Override
    public void close() {
        closed = true;
        pending.clear();
        for (Section section : sections) section.discard();
        sections.clear();
    }

    private final class Section {
        final int x, y, z;
        VertexBuffer buffer;
        BufferBuilder.SortState sortState;
        Vector3f sortedEye;
        /** Vertices behind {@link #sortState}; needed to size the re-sort buffer. */
        int sortVertices;
        boolean queued;

        Section(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        boolean overlaps(int min, int max) { return y <= max && y + SECTION - 1 >= min; }
        boolean straddles(int min, int max) {
            int top = y + SECTION - 1;
            return (min > y && min <= top) || (max >= y && max < top);
        }
        double distanceSquared() { return eye.distanceSquared(x + 8.0F, y + 8.0F, z + 8.0F); }
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
        }
        /**
         * Re-sorts the existing quads for the current eye without rebuilding any
         * block models.
         *
         * <p>The builder has to be allocated up front to hold the vertex data it
         * will never actually be given. {@code DrawState.vertexBufferSize} ignores
         * the index-only flag, so {@code VertexBuffer.upload} always slices out
         * {@code vertexCount * vertexSize} bytes, and a buffer smaller than that
         * makes the slice throw. Vanilla never trips over this because chunk
         * re-sorts borrow one of the big pooled builders.</p>
         */
        void sort() {
            BufferBuilder indices = new BufferBuilder(sortCapacity(sortVertices));
            indices.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            indices.restoreSortState(sortState);
            indices.setQuadSorting(VertexSorting.byDistance(eye.x - x, eye.y - y, eye.z - z));
            buffer.bind();
            try { buffer.upload(indices.end()); }
            finally { VertexBuffer.unbind(); }
            sortedEye = new Vector3f(eye);
        }
        void bake() {
            discard();
            if (!overlaps(layerMin, layerMax)) return;
            var dispatcher = Minecraft.getInstance().getBlockRenderer();
            RandomSource random = RandomSource.create();
            PoseStack pose = new PoseStack();
            BufferBuilder builder = new BufferBuilder(262144);
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            try {
                for (int by = Math.max(y, layerMin); by < Math.min(y + SECTION, layerMax + 1); by++) {
                    for (int bz = z; bz < Math.min(z + SECTION, schematic.length()); bz++) {
                        for (int bx = x; bx < Math.min(x + SECTION, schematic.width()); bx++) {
                            cursor.set(bx, by, bz);
                            BlockState state = view.getBlockState(cursor);
                            if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;
                            var model = dispatcher.getBlockModel(state);
                            pose.pushPose();
                            pose.translate(bx - x, by - y, bz - z);
                            try {
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
                builder.setQuadSorting(VertexSorting.byDistance(eye.x - x, eye.y - y, eye.z - z));
                BufferBuilder.SortState sorted = builder.getSortState();
                BufferBuilder.RenderedBuffer rendered = builder.end();
                if (rendered.isEmpty()) { rendered.release(); return; }
                int vertices = rendered.drawState().vertexCount();
                buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind();
                // upload() releases the rendered buffer, so read the count first
                try { buffer.upload(rendered); }
                finally { VertexBuffer.unbind(); }
                sortState = sorted;
                sortVertices = vertices;
                sortedEye = new Vector3f(eye);
            } catch (Exception e) {
                if (builder.building()) builder.end().release();
                throw e;
            }
        }
    }
}
