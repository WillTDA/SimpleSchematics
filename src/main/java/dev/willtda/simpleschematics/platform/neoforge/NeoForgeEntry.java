package dev.willtda.simpleschematics.platform.neoforge;

//? if neoforge {
/*import dev.willtda.simpleschematics.SimpleSchematics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/^*
 * Where NeoForge starts the mod. It is only built on a client, so on a
 * dedicated server the mod is loaded and nothing of it ever runs.
 ^/
@Mod(value = SimpleSchematics.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeEntry {

    public NeoForgeEntry(IEventBus modBus, ModContainer container) {
        NeoForgeClient.init(modBus, container);
    }
}
*///?}
