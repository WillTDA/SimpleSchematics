package dev.willtda.simpleschematics.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.willtda.simpleschematics.SimpleSchematics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;

import java.io.IOException;
import java.io.UncheckedIOException;

/** One terrain-format shader for all ghost surfaces, including normally solid blocks. */
public final class HologramShader {
    private static ShaderInstance shader;

    private HologramShader() {
    }

    public static void register(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    new ResourceLocation(SimpleSchematics.MOD_ID, "hologram"), DefaultVertexFormat.BLOCK),
                    loaded -> shader = loaded);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load the Simple Schematics hologram shader", e);
        }
    }

    public static ShaderInstance get() {
        return shader;
    }
}
