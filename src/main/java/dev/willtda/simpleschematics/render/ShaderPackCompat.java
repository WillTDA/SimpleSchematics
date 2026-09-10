package dev.willtda.simpleschematics.render;

import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.config.SSConfig;

import java.lang.reflect.Method;

/**
 * Whether a shader pack has taken the level render away from us.
 *
 * <p>Iris, and its Forge port Oculus, replace the whole level pipeline and
 * ignore a mod's own core shader. That is documented behaviour rather than a
 * bug: there is no sensible way to merge two arbitrary shader programs that
 * both want to draw the same geometry, so their guidance is that a mod needing
 * a shader should keep a path that does not. This is the switch onto that
 * path.</p>
 *
 * <p>Asked by reflection. A compile dependency on a mod most people do not have
 * would mean a build that needs the network up and a jar that carries a class
 * reference into every installation, all to answer one boolean. The lookup
 * happens once and gives up permanently the moment anything about it fails, so
 * a version that moved the class costs nothing beyond the ghosts looking the
 * way they did before.</p>
 */
public final class ShaderPackCompat {

    /** Iris, and Oculus from its 1.7 releases, then where older Oculus kept it. */
    private static final String[] CANDIDATES = {
            "net.irisshaders.iris.api.v0.IrisApi",
            "net.coderbot.iris.api.v0.IrisApi"
    };

    /** What the user has asked for, over the top of what we can detect. */
    public enum Mode {
        /** Use the compatible path only while a pack is actually loaded. */
        AUTO,
        /** Always use it, for a shader mod this does not know how to ask. */
        ALWAYS,
        /** Never use it, to get the mod's own shader back. */
        NEVER
    }

    private static Object api;
    private static Method inUse;
    private static Method shadowPass;
    private static boolean looked;

    private ShaderPackCompat() {
    }

    /** True when the ghosts should go through a vanilla render type instead. */
    public static boolean shaderPackInUse() {
        Mode mode = SSConfig.INSTANCE.shaderPackCompat.get();
        if (mode == Mode.ALWAYS) {
            return true;
        }
        if (mode == Mode.NEVER) {
            return false;
        }
        if (!look()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(inUse.invoke(api));
        } catch (Throwable t) {
            forget("Could not ask the shader mod whether a pack is loaded", t);
            return false;
        }
    }

    /**
     * Whether the level is being drawn into a shadow map right now.
     *
     * <p>A pack renders the world again from the sun to build its shadows, and
     * the stage events fire for that pass too. A hologram is not there, so it
     * has no business casting a shadow of itself across the ground.</p>
     */
    public static boolean renderingShadowPass() {
        // look() first: it is what populates the method being checked.
        if (!look() || shadowPass == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(shadowPass.invoke(api));
        } catch (Throwable t) {
            forget("Could not ask the shader mod about the shadow pass", t);
            return false;
        }
    }

    /** Whether a shader mod is installed at all, for the settings screen to say so. */
    public static boolean shaderModPresent() {
        return look();
    }

    private static synchronized boolean look() {
        if (looked) {
            return inUse != null;
        }
        looked = true;
        for (String name : CANDIDATES) {
            try {
                Class<?> type = Class.forName(name);
                Method getInstance = type.getMethod("getInstance");
                Object instance = getInstance.invoke(null);
                Method method = type.getMethod("isShaderPackInUse");
                if (instance == null) {
                    continue;
                }
                api = instance;
                inUse = method;
                // Added later than the rest of the API, so its absence is not a failure.
                try {
                    shadowPass = type.getMethod("isRenderingShadowPass");
                } catch (NoSuchMethodException older) {
                    shadowPass = null;
                }
                SimpleSchematics.LOG.info("Found a shader mod through {}, ghosts will follow its pipeline", name);
                return true;
            } catch (ClassNotFoundException expected) {
                // the usual case: no shader mod installed
            } catch (Throwable t) {
                SimpleSchematics.LOG.warn("Found {} but could not use it", name, t);
            }
        }
        return false;
    }

    private static synchronized void forget(String message, Throwable cause) {
        SimpleSchematics.LOG.warn(message, cause);
        api = null;
        inUse = null;
        shadowPass = null;
        looked = true;
    }
}
