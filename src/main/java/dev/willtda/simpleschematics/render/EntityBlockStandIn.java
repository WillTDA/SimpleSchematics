package dev.willtda.simpleschematics.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * Ghost geometry for blocks that have no baked model.
 *
 * <p>Beds, chests, signs, banners, shulker boxes and heads are drawn by a block
 * entity renderer in the real world. Their block model holds nothing but a
 * particle texture, so the bake produced no quads and the hologram had a hole
 * where the bed should be, while the resource list quite rightly still asked
 * for it. Running the real renderers is not an option: they draw solid model
 * parts through their own render types, outside the hologram shader.</p>
 *
 * <p>Instead each box of the block's own shape is drawn wearing that particle
 * texture. A bed becomes a low slab in its wool colour, a chest a planks box,
 * a sign a post. Not the real thing, but the right size in the right place,
 * which is what you need to build against.</p>
 */
final class EntityBlockStandIn {

    private EntityBlockStandIn() {
    }

    static void bake(BlockAndTintGetter level, BlockState state, BlockPos pos, PoseStack pose,
                     VertexConsumer builder, TextureAtlasSprite sprite) {
        Matrix4f matrix = pose.last().pose();
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        for (AABB box : state.getShape(level, pos).toAabbs()) {
            for (Direction side : Direction.values()) {
                // A face flush with the block edge is culled the way a model's
                // would be, so the two halves of a bed do not draw a seam between
                // them. A face inside the block is always drawn.
                if (onEdge(box, side)) {
                    neighbour.setWithOffset(pos, side);
                    if (!Block.shouldRenderFace(state, level, pos, side, neighbour)) {
                        continue;
                    }
                }
                face(builder, matrix, box, side, sprite, level.getShade(side, true));
            }
        }
    }

    private static boolean onEdge(AABB box, Direction side) {
        return switch (side) {
            case DOWN -> box.minY <= 0.0D;
            case UP -> box.maxY >= 1.0D;
            case NORTH -> box.minZ <= 0.0D;
            case SOUTH -> box.maxZ >= 1.0D;
            case WEST -> box.minX <= 0.0D;
            case EAST -> box.maxX >= 1.0D;
        };
    }

    /**
     * One face of a box, wound anticlockwise seen from outside like vanilla's
     * model quads, with the texture stretched over the face's extent.
     */
    private static void face(VertexConsumer builder, Matrix4f matrix, AABB box, Direction side,
                             TextureAtlasSprite sprite, float shade) {
        float x0 = (float) box.minX;
        float y0 = (float) box.minY;
        float z0 = (float) box.minZ;
        float x1 = (float) box.maxX;
        float y1 = (float) box.maxY;
        float z1 = (float) box.maxZ;
        switch (side) {
            case DOWN -> quad(builder, matrix, sprite, shade, side,
                    x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, z0, x1, z1);
            case UP -> quad(builder, matrix, sprite, shade, side,
                    x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, z0, x1, z1);
            case NORTH -> quad(builder, matrix, sprite, shade, side,
                    x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0, x0, y0, x1, y1);
            case SOUTH -> quad(builder, matrix, sprite, shade, side,
                    x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y0, x1, y1);
            case WEST -> quad(builder, matrix, sprite, shade, side,
                    x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1, z0, y0, z1, y1);
            case EAST -> quad(builder, matrix, sprite, shade, side,
                    x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0, z0, y0, z1, y1);
        }
    }

    /**
     * Four corners in order, then the extent of the face in the two axes it
     * spans, in block units, which the sprite is mapped across.
     */
    private static void quad(VertexConsumer builder, Matrix4f matrix, TextureAtlasSprite sprite, float shade,
                             Direction side,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float u0, float v0, float u1, float v1) {
        float minU = sprite.getU(u0 * 16.0D);
        float maxU = sprite.getU(u1 * 16.0D);
        float minV = sprite.getV(v0 * 16.0D);
        float maxV = sprite.getV(v1 * 16.0D);
        vertex(builder, matrix, side, shade, ax, ay, az, minU, minV);
        vertex(builder, matrix, side, shade, bx, by, bz, minU, maxV);
        vertex(builder, matrix, side, shade, cx, cy, cz, maxU, maxV);
        vertex(builder, matrix, side, shade, dx, dy, dz, maxU, minV);
    }

    private static void vertex(VertexConsumer builder, Matrix4f matrix, Direction side, float shade,
                               float x, float y, float z, float u, float v) {
        builder.vertex(matrix, x, y, z)
                .color(shade, shade, shade, 1.0F)
                .uv(u, v)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(side.getStepX(), side.getStepY(), side.getStepZ())
                .endVertex();
    }
}
