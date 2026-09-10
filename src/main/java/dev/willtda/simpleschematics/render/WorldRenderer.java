package dev.willtda.simpleschematics.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.willtda.simpleschematics.client.ClientState;
import dev.willtda.simpleschematics.client.EditMode;
import dev.willtda.simpleschematics.client.InputHandler;
import dev.willtda.simpleschematics.client.ScanSelection;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.placement.PlacementManager;
import dev.willtda.simpleschematics.schematic.Schematic;
import dev.willtda.simpleschematics.schematic.SchematicLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Everything the mod draws into the world. */
public final class WorldRenderer {

    private WorldRenderer() {
    }

    /** One baked copy per schematic, shared between every placement that uses it. */
    private static final Map<String, BakedSchematic> CACHE = new HashMap<>();
    private static final MultiBufferSource.BufferSource OVERLAYS =
            MultiBufferSource.immediate(new BufferBuilder(16384));
    private static int reloadGeneration;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // In Forge 1.20.1 AFTER_LEVEL supplies the projection pose rather than
        // the level's camera pose. Keep the terrain stage to preserve alignment.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientState state = ClientState.INSTANCE;
        if (mc.level == null || mc.player == null || !state.isEnabled() || mc.options.hideGui) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        Matrix4f projection = event.getProjectionMatrix();

        pose.pushPose();
        try (GhostRenderState ignored = new GhostRenderState(1.0F, false)) {
            pose.translate(-camera.x, -camera.y, -camera.z);
            if (state.mode() == EditMode.SCAN) {
                if (state.isHoldingTool() && mc.screen == null) {
                    drawSelection(pose, state.selection());
                }
            } else if (state.renderHolograms()) {
                drawHolograms(mc, pose, projection, camera, state);
            }
        } finally {
            pose.popPose();
        }
    }

    // ---- scan -------------------------------------------------------------

    private static void drawSelection(PoseStack pose, ScanSelection selection) {
        MultiBufferSource.BufferSource source = OVERLAYS;

        int startColour = SSConfig.colour(SSConfig.INSTANCE.startCornerColour.get(), 0x36D399);
        int endColour = SSConfig.colour(SSConfig.INSTANCE.endCornerColour.get(), 0xF87272);
        int boxColour = SSConfig.colour(SSConfig.INSTANCE.selectionBoxColour.get(), 0x60A5FA);
        float fill = SSConfig.INSTANCE.selectionFillOpacity.get().floatValue();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();

        if (fill > 0.0F) {
            VertexConsumer filled = source.getBuffer(RenderType.debugFilledBox());
            if (selection.isComplete()) {
                fillBox(pose, filled, selection.boundingBox(), boxColour, fill);
            }
            if (selection.start() != null) {
                fillBox(pose, filled, ScanSelection.blockBox(selection.start()), startColour, 0.45F);
            }
            if (selection.end() != null) {
                fillBox(pose, filled, ScanSelection.blockBox(selection.end()), endColour, 0.45F);
            }
            source.endBatch(RenderType.debugFilledBox());
        }

        VertexConsumer lines = source.getBuffer(RenderType.lines());
        if (selection.isComplete()) {
            lineBox(pose, lines, selection.boundingBox(), boxColour, 1.0F);
        }
        if (selection.start() != null) {
            lineBox(pose, lines, grow(ScanSelection.blockBox(selection.start())), startColour, 1.0F);
        }
        if (selection.end() != null) {
            lineBox(pose, lines, grow(ScanSelection.blockBox(selection.end())), endColour, 1.0F);
        }

        // a faint marker on the block you are pointing at, so corners land where you expect
        BlockPos looking = InputHandler.lookedAtBlock();
        if (looking != null && SSConfig.INSTANCE.showTargetBlockOutline.get()) {
            lineBox(pose, lines, grow(ScanSelection.blockBox(looking)), 0xFFFFFF, 0.35F);
        }
        source.endBatch(RenderType.lines());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    // ---- build ------------------------------------------------------------

    private static void drawHolograms(Minecraft mc, PoseStack pose, Matrix4f projection,
                                      Vec3 camera, ClientState state) {
        float alpha = SSConfig.INSTANCE.hologramOpacity.get().floatValue();
        double maxDistance = SSConfig.INSTANCE.hologramRenderDistance.get();
        boolean outline = SSConfig.INSTANCE.hologramOutline.get();

        MultiBufferSource.BufferSource source = OVERLAYS;

        // the one following your crosshair, if any
        if (state.hasPending() && state.isHoldingTool() && mc.screen == null) {
            SchematicLibrary.Entry entry = SchematicLibrary.INSTANCE.byKey(state.pendingSchematicKey());
            Schematic schematic = entry == null ? null : entry.get();
            BlockPos origin = InputHandler.pendingOrigin();
            if (schematic != null && origin != null) {
                drawOne(pose, projection, schematic, state.pendingSchematicKey(), null, origin,
                        Rotation.NONE, Mirror.NONE, alpha, state);
                drawBlockOutlines(pose, source, schematic, state.pendingSchematicKey(), null,
                        origin, camera, state);
                if (outline) {
                    VertexConsumer lines = source.getBuffer(RenderType.lines());
                    lineBox(pose, lines, boxFor(origin, schematic.width(), schematic.height(), schematic.length()),
                            0x36D399, 0.9F);
                    source.endBatch(RenderType.lines());
                }
            }
        }

        for (Placement placement : PlacementManager.INSTANCE.current()) {
            if (!placement.visible()) {
                continue;
            }
            SchematicLibrary.Entry entry = SchematicLibrary.INSTANCE.byKey(placement.schematicKey());
            Schematic schematic = entry == null ? null : entry.get();
            if (schematic == null) {
                continue;
            }
            Vec3i size = placement.effectiveSize(schematic);
            AABB box = boxFor(placement.origin(), size.getX(), size.getY(), size.getZ());
            if (!box.inflate(maxDistance).contains(camera)) {
                continue;
            }

            drawOne(pose, projection, schematic, placement.schematicKey(), placement, placement.origin(),
                    placement.rotation(), placement.mirror(), alpha, state);

            drawBanks(pose, source, placement, camera);
            drawBlockOutlines(pose, source, schematic, placement.schematicKey(), placement,
                    placement.origin(), camera, state);
            drawMismatches(pose, source, placement, camera, state);

            if (outline) {
                boolean selected = placement == PlacementManager.INSTANCE.selected();
                VertexConsumer lines = source.getBuffer(RenderType.lines());
                lineBox(pose, lines, box, selected ? 0x60A5FA : 0x9CA3AF, selected ? 0.9F : 0.35F);
                source.endBatch(RenderType.lines());
            }
        }
    }

    /**
     * A box around every chest handed to this build, so which ones are feeding
     * the resource list is never a guess. Only the selected placement's banks
     * are drawn solidly; the rest stay faint so a world full of builds does not
     * turn into a light show.
     */
    private static void drawBanks(PoseStack pose, MultiBufferSource.BufferSource source,
                                  Placement placement, Vec3 camera) {
        if (placement.banks().isEmpty()) {
            return;
        }
        boolean selected = placement == PlacementManager.INSTANCE.selected();
        double reach = SSConfig.INSTANCE.hologramRenderDistance.get();
        double reachSq = reach * reach;
        VertexConsumer lines = source.getBuffer(RenderType.lines());
        boolean drew = false;
        for (BlockPos pos : placement.bankPositions()) {
            double dx = pos.getX() + 0.5D - camera.x;
            double dy = pos.getY() + 0.5D - camera.y;
            double dz = pos.getZ() + 0.5D - camera.z;
            if (dx * dx + dy * dy + dz * dz > reachSq) {
                continue;
            }
            lineBox(pose, lines, grow(ScanSelection.blockBox(pos)), 0xFBBF24, selected ? 0.9F : 0.3F);
            drew = true;
        }
        if (drew) {
            source.endBatch(RenderType.lines());
        }
    }

    /**
     * An edge around every block you can actually see, so the hologram reads as
     * a grid rather than a wash of colour.
     *
     * <p>The box is grown by a hair. The ghost is drawn with a polygon offset
     * pulling it toward the camera, so an edge sitting exactly on the block
     * bounds would z-fight along every shared face; a fraction of a block
     * outward puts the line clear of the surface at any distance without moving
     * it anywhere the eye can see.</p>
     */
    private static void drawBlockOutlines(PoseStack pose, MultiBufferSource.BufferSource source,
                                          Schematic schematic, String key, Placement placement,
                                          BlockPos origin, Vec3 camera, ClientState state) {
        if (!SSConfig.INSTANCE.hologramBlockOutline.get()) {
            return;
        }
        float alpha = SSConfig.INSTANCE.hologramBlockOutlineOpacity.get().floatValue();
        if (alpha <= 0.0F) {
            return;
        }
        int colour = SSConfig.colour(SSConfig.INSTANCE.hologramBlockOutlineColour.get(), 0xFFFFFF);
        double reach = SSConfig.INSTANCE.hologramBlockOutlineDistance.get();
        double reachSq = reach * reach;

        int minLayer = 0;
        int maxLayer = schematic.height() - 1;
        if (state.layerView() == ClientState.LayerView.SINGLE) {
            minLayer = Math.min(state.layer(), maxLayer);
            maxLayer = minLayer;
        }

        BlockOutlines outlines = BlockOutlines.of(key, schematic);
        VertexConsumer lines = source.getBuffer(RenderType.lines());
        int drawn = 0;
        int budget = SSConfig.INSTANCE.maxHighlights.get();

        for (int i = 0; i < outlines.count() && drawn < budget; i++) {
            int ly = outlines.y(i);
            if (ly < minLayer || ly > maxLayer) {
                continue;
            }
            int lx = outlines.x(i);
            int lz = outlines.z(i);
            BlockPos world = placement == null
                    ? origin.offset(lx, ly, lz)
                    : placement.toWorld(schematic, lx, ly, lz);
            double dx = world.getX() + 0.5D - camera.x;
            double dy = world.getY() + 0.5D - camera.y;
            double dz = world.getZ() + 0.5D - camera.z;
            if (dx * dx + dy * dy + dz * dz > reachSq) {
                continue;
            }
            lineBox(pose, lines, ScanSelection.blockBox(world).inflate(0.0015D), colour, alpha);
            drawn++;
        }
        if (drawn > 0) {
            source.endBatch(RenderType.lines());
        }
    }

    private static void drawOne(PoseStack pose, Matrix4f projection, Schematic schematic, String key,
                                Placement placement, BlockPos origin, Rotation rotation, Mirror mirror,
                                float alpha, ClientState state) {
        BakedSchematic baked = CACHE.computeIfAbsent(renderKey(key, placement), k -> new BakedSchematic(schematic));
        if (placement != null) {
            SchematicVerifier.INSTANCE.applyCorrectMask(placement, baked);
        } else {
            baked.clearCorrectMask();
        }
        baked.setTintOrigin(origin);
        applyLayerFilter(baked, schematic, state);
        baked.bakeStep();

        pose.pushPose();
        try {
            pose.translate(origin.getX(), origin.getY(), origin.getZ());
            applyTransform(pose, schematic, rotation, mirror);
            baked.draw(pose, projection, alpha);
        } finally {
            pose.popPose();
        }
    }

    /**
     * Hiding blocks you have already placed makes the bake specific to one
     * placement, so it can no longer be shared between two copies of the same
     * schematic. When the feature is off the plain schematic key is used and
     * the sharing comes back.
     */
    private static String renderKey(String schematicKey, Placement placement) {
        if (placement != null
                && SSConfig.INSTANCE.hideCorrectBlocks.get()
                && SchematicVerifier.INSTANCE.isEnabled()) {
            return schematicKey + "#" + placement.id();
        }
        return schematicKey;
    }

    private static String baseKey(String renderKey) {
        int cut = renderKey.indexOf('#');
        return cut < 0 ? renderKey : renderKey.substring(0, cut);
    }

    /** Red for a wrong block, amber for something that is in the way. */
    private static void drawMismatches(PoseStack pose, MultiBufferSource.BufferSource source,
                                       Placement placement, Vec3 camera, ClientState state) {
        if (!SchematicVerifier.INSTANCE.isEnabled()) {
            return;
        }
        SchematicVerifier.Diff diff = SchematicVerifier.INSTANCE.resultFor(placement);
        if (!diff.hasAnything()) {
            return;
        }

        int wrongColour = SSConfig.colour(SSConfig.INSTANCE.mismatchColour.get(), 0xF87272);
        int extraColour = SSConfig.colour(SSConfig.INSTANCE.extraBlockColour.get(), 0xFBBF24);
        float fill = SSConfig.INSTANCE.highlightFillOpacity.get().floatValue();
        boolean showExtra = SSConfig.INSTANCE.highlightExtraBlocks.get();
        double maxDistance = SSConfig.INSTANCE.hologramRenderDistance.get();
        double maxSq = maxDistance * maxDistance;

        boolean single = state.layerView() == ClientState.LayerView.SINGLE;
        int layer = state.layer();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();

        if (fill > 0.0F) {
            VertexConsumer filled = source.getBuffer(RenderType.debugFilledBox());
            for (SchematicVerifier.Mark mark : diff.wrong()) {
                if (skip(mark, single, layer, camera, maxSq)) {
                    continue;
                }
                fillBox(pose, filled, ScanSelection.blockBox(mark.pos()), wrongColour, fill);
            }
            if (showExtra) {
                for (SchematicVerifier.Mark mark : diff.extra()) {
                    if (skip(mark, single, layer, camera, maxSq)) {
                        continue;
                    }
                    fillBox(pose, filled, ScanSelection.blockBox(mark.pos()), extraColour, fill);
                }
            }
            source.endBatch(RenderType.debugFilledBox());
        }

        VertexConsumer lines = source.getBuffer(RenderType.lines());
        for (SchematicVerifier.Mark mark : diff.wrong()) {
            if (skip(mark, single, layer, camera, maxSq)) {
                continue;
            }
            lineBox(pose, lines, grow(ScanSelection.blockBox(mark.pos())), wrongColour, 0.9F);
        }
        if (showExtra) {
            for (SchematicVerifier.Mark mark : diff.extra()) {
                if (skip(mark, single, layer, camera, maxSq)) {
                    continue;
                }
                lineBox(pose, lines, grow(ScanSelection.blockBox(mark.pos())), extraColour, 0.9F);
            }
        }
        source.endBatch(RenderType.lines());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static boolean skip(SchematicVerifier.Mark mark, boolean single, int layer,
                                Vec3 camera, double maxSq) {
        if (single && mark.layer() != layer) {
            return true;
        }
        return camera.distanceToSqr(mark.pos().getX() + 0.5, mark.pos().getY() + 0.5, mark.pos().getZ() + 0.5) > maxSq;
    }

    /**
     * Transform the whole bake, including stair and other directional models.
     * Matrix multiplication applies the mirror first, then the rotation,
     * matching Placement.toWorld and the verifier's block state transform.
     */
    private static void applyTransform(PoseStack pose, Schematic schematic, Rotation rotation, Mirror mirror) {
        int w = schematic.width();
        int l = schematic.length();
        switch (rotation) {
            case CLOCKWISE_90 -> {
                pose.translate(l, 0, 0);
                pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-90.0F));
            }
            case CLOCKWISE_180 -> {
                pose.translate(w, 0, l);
                pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F));
            }
            case COUNTERCLOCKWISE_90 -> {
                pose.translate(0, 0, w);
                pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90.0F));
            }
            default -> {
            }
        }
        switch (mirror) {
            case LEFT_RIGHT -> {
                pose.translate(0, 0, l);
                pose.scale(1.0F, 1.0F, -1.0F);
            }
            case FRONT_BACK -> {
                pose.translate(w, 0, 0);
                pose.scale(-1.0F, 1.0F, 1.0F);
            }
            default -> {
            }
        }
    }

    private static void applyLayerFilter(BakedSchematic baked, Schematic schematic, ClientState state) {
        int min;
        int max;
        if (state.layerView() == ClientState.LayerView.SINGLE) {
            min = Math.min(state.layer(), schematic.height() - 1);
            max = min;
        } else {
            min = 0;
            max = schematic.height() - 1;
        }
        baked.setLayerRange(min, max);
    }

    // ---- housekeeping -----------------------------------------------------

    /** Drops baked data for schematics that are no longer referenced. */
    public static void pruneCache() {
        Iterator<Map.Entry<String, BakedSchematic>> it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, BakedSchematic> entry = it.next();
            String base = baseKey(entry.getKey());
            boolean used = base.equals(ClientState.INSTANCE.pendingSchematicKey())
                    && entry.getKey().indexOf('#') < 0;
            if (!used) {
                for (Placement placement : PlacementManager.INSTANCE.current()) {
                    if (entry.getKey().equals(renderKey(placement.schematicKey(), placement))) {
                        used = true;
                        break;
                    }
                }
            }
            if (!used) {
                entry.getValue().close();
                it.remove();
            }
        }
    }

    public static void invalidate(String key) {
        Iterator<Map.Entry<String, BakedSchematic>> it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, BakedSchematic> entry = it.next();
            if (baseKey(entry.getKey()).equals(key)) {
                entry.getValue().close();
                it.remove();
            }
        }
        BlockOutlines.invalidate(key);
    }

    public static void invalidateAll() {
        reloadGeneration++;
        for (BakedSchematic baked : CACHE.values()) {
            baked.close();
        }
        CACHE.clear();
        BlockOutlines.invalidateAll();
    }

    public static int reloadGeneration() {
        return reloadGeneration;
    }

    // ---- small helpers ----------------------------------------------------

    private static AABB boxFor(BlockPos origin, int w, int h, int l) {
        return new AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + w, origin.getY() + h, origin.getZ() + l);
    }

    private static AABB grow(AABB box) {
        return box.inflate(0.002);
    }

    private static void lineBox(PoseStack pose, VertexConsumer consumer, AABB box, int rgb, float alpha) {
        LevelRenderer.renderLineBox(pose, consumer, box,
                ((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, alpha);
    }

    private static void fillBox(PoseStack pose, VertexConsumer consumer, AABB box, int rgb, float alpha) {
        LevelRenderer.addChainedFilledBoxVertices(pose, consumer,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                ((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, alpha);
    }
}
