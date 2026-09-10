package dev.willtda.simpleschematics.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/** Restores the caller's render state even when a model or shader fails. */
final class GhostRenderState implements AutoCloseable {
    private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    private final boolean offset = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
    private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    private final int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
    private final int destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    private final int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
    private final int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    private final float offsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
    private final float offsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
    private final float[] colour = RenderSystem.getShaderColor().clone();
    private final int texture = RenderSystem.getShaderTexture(0);

    GhostRenderState(float alpha, boolean writeDepth) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(writeDepth);
        // Reflected geometry reverses winding. Both sides must remain visible.
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        if (!writeDepth) {
            // A depth bias removes coplanar flicker without moving block corners.
            RenderSystem.enablePolygonOffset();
            RenderSystem.polygonOffset(-1.0F, -2.0F);
        } else {
            RenderSystem.disablePolygonOffset();
        }
    }

    @Override
    public void close() {
        RenderSystem.depthMask(depthMask);
        RenderSystem.depthFunc(depthFunc);
        RenderSystem.polygonOffset(offsetFactor, offsetUnits);
        if (offset) RenderSystem.enablePolygonOffset(); else RenderSystem.disablePolygonOffset();
        if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        RenderSystem.blendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
        if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        RenderSystem.setShaderColor(colour[0], colour[1], colour[2], colour[3]);
        RenderSystem.setShaderTexture(0, texture);
    }
}
