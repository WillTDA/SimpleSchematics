package dev.willtda.simpleschematics;

import com.mojang.logging.LogUtils;
import dev.willtda.simpleschematics.config.SSConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Simple Schematics.
 *
 * <p>Entry point. Everything meaningful lives on the client, so this class does
 * nothing more than register the config spec and hand over to the client
 * bootstrap when we are actually on a client.</p>
 */
@Mod(SimpleSchematics.MOD_ID)
public final class SimpleSchematics {

    public static final String MOD_ID = "simpleschematics";
    public static final String MOD_NAME = "Simple Schematics";
    public static final Logger LOG = LogUtils.getLogger();

    public SimpleSchematics() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SSConfig.SPEC, MOD_ID + "-client.toml");

        if (FMLEnvironment.dist == Dist.CLIENT) {
            dev.willtda.simpleschematics.client.SimpleSchematicsClient.bootstrap();
        }
    }
}
