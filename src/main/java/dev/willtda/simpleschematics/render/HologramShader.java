package dev.willtda.simpleschematics.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.willtda.simpleschematics.util.Ids;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.IOException;

/** One terrain-format shader for all ghost surfaces, including normally solid blocks. */
public final class HologramShader {
    private static ShaderInstance shader;

    private HologramShader() {
    }

    /** Builds the shader from the resource packs. The loader registers it and hands it back through {@link #set}. */
    public static ShaderInstance create(ResourceProvider resources) throws IOException {
        return new ShaderInstance(resources, Ids.mod("hologram"),
                DefaultVertexFormat.BLOCK);
    }

    public static void set(ShaderInstance loaded) {
        shader = loaded;
    }

    public static ShaderInstance get() {
        return shader;
    }
}
