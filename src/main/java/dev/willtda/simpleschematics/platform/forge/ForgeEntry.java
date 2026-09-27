package dev.willtda.simpleschematics.platform.forge;

import dev.willtda.simpleschematics.SimpleSchematics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Where Forge starts the mod. On a dedicated server it does nothing at all,
 * which is what lets the mod be installed on one without doing any harm.
 */
@Mod(SimpleSchematics.MOD_ID)
public final class ForgeEntry {

    public ForgeEntry() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeClient.init();
        }
    }
}
