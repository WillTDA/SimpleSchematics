package dev.willtda.simpleschematics;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Simple Schematics.
 *
 * <p>The mod's name and log. Everything meaningful lives on the client; the
 * loader's entry point, in {@code platform}, starts it there and does nothing
 * on a dedicated server.</p>
 */
public final class SimpleSchematics {

    public static final String MOD_ID = "simpleschematics";
    public static final String MOD_NAME = "Simple Schematics";
    public static final Logger LOG = LogUtils.getLogger();

    private SimpleSchematics() {
    }
}
