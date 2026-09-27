package dev.willtda.simpleschematics.util;

import dev.willtda.simpleschematics.SimpleSchematics;
import net.minecraft.resources.ResourceLocation;

/** Builds resource locations, whose constructor 1.21 made private. */
public final class Ids {

    private Ids() {
    }

    public static ResourceLocation of(String namespace, String path) {
        //? if >=1.21 {
        /*return ResourceLocation.fromNamespaceAndPath(namespace, path);
        *///?} else {
        return new ResourceLocation(namespace, path);
        //?}
    }

    /** One of the mod's own. */
    public static ResourceLocation mod(String path) {
        return of(SimpleSchematics.MOD_ID, path);
    }
}
