package dev.willtda.simpleschematics.printing;

import dev.willtda.simpleschematics.SimpleSchematics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SimpleSchematics.MOD_ID, value = Dist.CLIENT)
public final class PrintSounds {
    private PrintSounds() { }

    @SubscribeEvent
    public static void onSound(PlaySoundEvent event) {
        if (PrintManager.suppressPlacementSound()) event.setSound(null);
    }
}
