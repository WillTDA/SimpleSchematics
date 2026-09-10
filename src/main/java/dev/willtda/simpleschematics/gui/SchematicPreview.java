package dev.willtda.simpleschematics.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.render.BakedSchematic;
import dev.willtda.simpleschematics.render.WorldRenderer;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.Util;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a schematic in a box on screen.
 *
 * <p>The build is rendered live from the same baked geometry as the in world
 * hologram, turning slowly on its own until you take hold of it. Dragging spins
 * it, scrolling pushes in and out. Only if that is impossible, because the
 * build is enormous or there is no world loaded to borrow lighting from, does
 * it fall back to the thumbnail baked into the file or a plain summary.</p>
 */
public final class SchematicPreview implements AutoCloseable {

    private static final Map<String, ResourceLocation> THUMBNAILS = new HashMap<>();

    /** A couple of bad frames can happen on a driver hiccup; a run of them cannot. */
    private static final int FAILURE_LIMIT = 3;
    private static int failures;

    private static final float MIN_ZOOM = 0.25F;
    private static final float MAX_ZOOM = 4.0F;

    private final String key;
    private final Schematic schematic;
    private BakedSchematic baked;
    private int generation = -1;
    /** Degrees per second the turntable turns at when left alone. */
    private static final float IDLE_SPEED = 12.0F;
    /** How long after you let go before it picks itself back up. */
    private static final long RESUME_DELAY_MS = 1500L;
    /** Fraction of the flick left after a second; a flick dies in about a second. */
    private static final float DECAY_PER_SECOND = 0.02F;
    /** Below this the spin is not visible, so it is treated as stopped. */
    private static final float STOPPED = 2.0F;

    private float yaw = 35.0F;
    private float pitch = 22.0F;
    private float zoom = 1.0F;
    /** Degrees per second left over from a flick, shed a bit at a time. */
    private float velocityYaw;
    private float velocityPitch;
    private boolean dragging;
    private long lastInput;
    private long lastFrame;

    /** The box the last frame was drawn into, so the screen can hit test it. */
    private int boxX;
    private int boxY;
    private int boxWidth;
    private int boxHeight;
    private boolean live;

    public SchematicPreview(String key, Schematic schematic) {
        this.key = key;
        this.schematic = schematic;
    }

    /** True while the build is actually being drawn in 3D and can be turned. */
    public boolean interactive() {
        return live;
    }

    public boolean contains(double mouseX, double mouseY) {
        return live && mouseX >= boxX && mouseX < boxX + boxWidth
                && mouseY >= boxY && mouseY < boxY + boxHeight;
    }

    /** Called when the mouse goes down on the model, so it stops dead under your hand. */
    public void beginDrag() {
        dragging = true;
        velocityYaw = 0.0F;
        velocityPitch = 0.0F;
        lastInput = Util.getMillis();
    }

    /**
     * Dragging takes it off its turntable and hands it to you. The speed of the
     * last drag is kept so that letting go mid flick leaves it spinning.
     */
    public void drag(float deltaYaw, float deltaPitch) {
        long now = Util.getMillis();
        float seconds = Math.min((now - lastInput) / 1000.0F, 0.1F);
        dragging = true;
        yaw += deltaYaw;
        pitch = Mth.clamp(pitch + deltaPitch, -89.0F, 89.0F);
        lastInput = now;
        if (seconds > 0.004F) {
            // Blend, so one stuttery frame does not read as a flick.
            velocityYaw = Mth.lerp(0.5F, velocityYaw, deltaYaw / seconds);
            velocityPitch = Mth.lerp(0.5F, velocityPitch, deltaPitch / seconds);
        }
    }

    public void endDrag() {
        dragging = false;
        lastInput = Util.getMillis();
    }

    public void zoom(float steps) {
        zoom = Mth.clamp(zoom * (float) Math.pow(1.15D, steps), MIN_ZOOM, MAX_ZOOM);
        lastInput = Util.getMillis();
    }

    /** Back to the slow turn it started with. */
    public void resetView() {
        yaw = 35.0F;
        pitch = 22.0F;
        zoom = 1.0F;
        velocityYaw = 0.0F;
        velocityPitch = 0.0F;
        lastInput = 0L;
    }

    /**
     * Turntable, flick and coast in one place.
     *
     * <p>A flick keeps its speed and sheds it exponentially until it is too slow
     * to see, at which point it stops. Once it has been still and untouched for
     * three seconds the slow idle turn fades back in, rather than snapping on.</p>
     */
    private void advance() {
        long now = Util.getMillis();
        float seconds = lastFrame == 0L ? 0.0F : Math.min((now - lastFrame) / 1000.0F, 0.1F);
        lastFrame = now;
        if (dragging || seconds <= 0.0F) {
            return;
        }

        if (Math.abs(velocityYaw) > STOPPED || Math.abs(velocityPitch) > STOPPED) {
            yaw += velocityYaw * seconds;
            pitch = Mth.clamp(pitch + velocityPitch * seconds, -89.0F, 89.0F);
            float keep = (float) Math.pow(DECAY_PER_SECOND, seconds);
            velocityYaw *= keep;
            velocityPitch *= keep;
            return;
        }
        velocityYaw = 0.0F;
        velocityPitch = 0.0F;

        long idle = now - lastInput;
        if (idle < RESUME_DELAY_MS) {
            return;
        }
        // Ease in over the first half second so it does not jolt back into motion.
        float ramp = Math.min(1.0F, (idle - RESUME_DELAY_MS) / 500.0F);
        yaw += IDLE_SPEED * ramp * seconds;
    }

    public void render(GuiGraphics graphics, int x, int y, int width, int height, float partialTick) {
        boxX = x;
        boxY = y;
        boxWidth = width;
        boxHeight = height;
        live = false;

        graphics.fill(x, y, x + width, y + height, 0x40000000);
        if (width < 24 || height < 24) {
            return;
        }

        if (canRenderLive()) {
            advance();
            try {
                renderLive(graphics, x, y, width, height);
                live = true;
                return;
            } catch (Exception e) {
                failures++;
                close();
                SimpleSchematics.LOG.error("The live preview could not be drawn, falling back to a summary", e);
            }
        }

        ResourceLocation thumbnail = thumbnailFor(key, schematic);
        if (thumbnail != null) {
            int side = Math.min(width, height) - 8;
            int tx = x + (width - side) / 2;
            int ty = y + (height - side) / 2;
            graphics.blit(thumbnail, tx, ty, 0, 0, side, side, side, side);
            return;
        }
        drawStats(graphics, x, y, width, height);
    }

    private boolean canRenderLive() {
        return failures < FAILURE_LIMIT
                && Minecraft.getInstance().level != null
                && schematic.blockCount() <= SSConfig.INSTANCE.maxPreviewBlocks.get()
                && schematic.volume() <= 4_000_000L;
    }

    private void renderLive(GuiGraphics graphics, int x, int y, int width, int height) {
        if (generation != WorldRenderer.reloadGeneration()) {
            close();
            generation = WorldRenderer.reloadGeneration();
        }
        if (baked == null) {
            baked = new BakedSchematic(schematic);
        }
        baked.bakeStep();

        int longest = Math.max(schematic.width(), Math.max(schematic.height(), schematic.length()));
        float scale = (Math.min(width, height) * 0.62F) / Math.max(1, longest) * zoom;

        graphics.flush();
        graphics.enableScissor(x, y, x + width, y + height);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x + width / 2.0F, y + height / 2.0F, 200.0F);
            graphics.pose().scale(scale, -scale, scale);
            graphics.pose().mulPose(Axis.XP.rotationDegrees(pitch));
            graphics.pose().mulPose(Axis.YP.rotationDegrees(yaw));
            graphics.pose().translate(-schematic.width() / 2.0F, -schematic.height() / 2.0F, -schematic.length() / 2.0F);
            baked.draw(graphics.pose(), RenderSystem.getProjectionMatrix(), 1.0F, true);
        } finally {
            graphics.pose().popPose();
            // The hologram writes depth so its own faces occlude properly. Left
            // behind, that depth sits in front of everything the screen draws
            // afterwards and swallows the buttons, so wipe it inside the box.
            // glClear obeys the scissor, so nothing outside the box is touched.
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            graphics.disableScissor();
        }

        if (!baked.isReady()) {
            graphics.drawString(Minecraft.getInstance().font,
                    Component.translatable("simpleschematics.preview.loading", baked.pendingCount()),
                    x + 4, y + height - 11, 0xFF9CA3AF, false);
        }
    }

    private void drawStats(GuiGraphics graphics, int x, int y, int width, int height) {
        var font = Minecraft.getInstance().font;
        String size = schematic.width() + " x " + schematic.height() + " x " + schematic.length();
        String blocks = Component.translatable("simpleschematics.gui.blocks",
                String.format("%,d", schematic.blockCount())).getString();
        graphics.drawCenteredString(font, size, x + width / 2, y + height / 2 - 10, 0xFFE5E7EB);
        graphics.drawCenteredString(font, blocks, x + width / 2, y + height / 2 + 2, 0xFF9CA3AF);
    }

    @Override
    public void close() {
        if (baked != null) {
            baked.close();
            baked = null;
        }
    }

    // ---- thumbnails -------------------------------------------------------

    /** Uploads the embedded thumbnail once, then reuses it. */
    private static ResourceLocation thumbnailFor(String key, Schematic schematic) {
        if (!schematic.hasPreviewImage()) {
            return null;
        }
        if (THUMBNAILS.containsKey(key)) {
            return THUMBNAILS.get(key);
        }
        try {
            int w = schematic.meta().previewWidth;
            int h = schematic.meta().previewHeight;
            int[] pixels = schematic.meta().previewPixels;
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = pixels[y * w + x];
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int bch = argb & 0xFF;
                    // NativeImage stores ABGR, so red and blue swap places
                    image.setPixelRGBA(x, y, (a << 24) | (bch << 16) | (g << 8) | r);
                }
            }
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation id = Minecraft.getInstance().getTextureManager()
                    .register(SimpleSchematics.MOD_ID + "_preview", texture);
            THUMBNAILS.put(key, id);
            return id;
        } catch (Exception e) {
            SimpleSchematics.LOG.warn("Could not build a thumbnail for {}", key, e);
            THUMBNAILS.put(key, null);
            return null;
        }
    }

    public static void clearThumbnails() {
        for (ResourceLocation id : THUMBNAILS.values()) {
            if (id != null) {
                Minecraft.getInstance().getTextureManager().release(id);
            }
        }
        THUMBNAILS.clear();
        failures = 0;
    }
}
