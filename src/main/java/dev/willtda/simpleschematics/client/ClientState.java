package dev.willtda.simpleschematics.client;

import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.placement.PlacementManager;
import dev.willtda.simpleschematics.schematic.Schematic;
import dev.willtda.simpleschematics.schematic.SchematicLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The bits of state that everything else asks about: whether the mod is on,
 * which mode you are in, which layer you are looking at, and whether you are
 * currently dragging a schematic around before placing it.
 */
public final class ClientState {

    public static final ClientState INSTANCE = new ClientState();

    /** How much of the hologram to draw. */
    public enum LayerView {
        /** Everything at once. */
        ALL,
        /** A single horizontal slice. */
        SINGLE
    }

    private boolean enabled;
    private boolean initialised;
    private EditMode mode = EditMode.SCAN;
    private boolean modeLoaded;
    private boolean modeDirty;

    private final ScanSelection selection = new ScanSelection();

    private LayerView layerView = LayerView.ALL;
    private int layer;

    /** Set while a schematic follows your crosshair, before you commit to a spot. */
    private String pendingSchematicKey;

    private boolean resourceListVisible;
    private boolean renderHolograms = true;

    private ClientState() {
    }

    // ---- on and off -------------------------------------------------------

    public boolean isEnabled() {
        if (!initialised) {
            initialised = true;
            enabled = SSConfig.INSTANCE.enabledOnLaunch.get();
        }
        return enabled;
    }

    public void setEnabled(boolean value) {
        initialised = true;
        this.enabled = value;
    }

    /** @return the state it landed on, so the caller can announce it */
    public boolean toggleEnabled() {
        setEnabled(!isEnabled());
        return enabled;
    }

    /**
     * Whether the scroll shortcuts should respond right now. By default that
     * means the mod is on and the tool item is in your hand, but the tool
     * requirement can be switched off in the config.
     */
    public boolean shortcutsActive() {
        return isEnabled() && holdingActivationItem();
    }

    /**
     * The tool half of {@link #shortcutsActive()} on its own. Switching the mod
     * back on has to work while it is off, so that shortcut asks only whether
     * the activation item is in hand.
     */
    public boolean holdingActivationItem() {
        if (!SSConfig.INSTANCE.toolRequiredForHotkeys.get()) {
            return true;
        }
        return isHoldingTool();
    }

    // ---- hologram rendering ----------------------------------------------

    public boolean renderHolograms() {
        return renderHolograms;
    }

    public boolean toggleRenderHolograms() {
        renderHolograms = !renderHolograms;
        return renderHolograms;
    }

    public boolean isHoldingTool() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        var tool = SSConfig.INSTANCE.resolveToolItem();
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.isEmpty() && stack.is(tool)) {
                return true;
            }
        }
        return false;
    }

    // ---- mode -------------------------------------------------------------

    /**
     * The mode is read back from the config the first time it is asked for,
     * which is after the config has actually loaded. Reading it in the field
     * initialiser would be too early and would always give Scan.
     */
    public EditMode mode() {
        if (!modeLoaded) {
            modeLoaded = true;
            mode = EditMode.byName(SSConfig.INSTANCE.lastMode.get());
        }
        return mode;
    }

    public void setMode(EditMode mode) {
        if (mode() != mode) {
            this.mode = mode;
            modeDirty = true;
            Feedback.value(net.minecraft.network.chat.Component.translatable("simpleschematics.feedback.mode"),
                    mode.label());
        }
    }

    public void cycleMode() {
        setMode(mode().next());
    }

    /**
     * Writes the remembered mode out, but only once it has settled. Scrolling
     * through the modes would otherwise rewrite the config file several times a
     * second for no reason.
     */
    public void flushMode() {
        if (!modeDirty) {
            return;
        }
        modeDirty = false;
        SSConfig.INSTANCE.lastMode.set(mode.name());
        SSConfig.SPEC.save();
    }

    // ---- selection --------------------------------------------------------

    public ScanSelection selection() {
        return selection;
    }

    // ---- layers -----------------------------------------------------------

    public LayerView layerView() {
        return layerView;
    }

    public int layer() {
        return layer;
    }

    public void setLayerView(LayerView view) {
        this.layerView = view;
    }

    public void toggleLayerView() {
        layerView = layerView == LayerView.ALL ? LayerView.SINGLE : LayerView.ALL;
        if (layerView == LayerView.SINGLE) {
            layer = Math.max(0, Math.min(layer, maxLayer()));
        }
    }

    /**
     * Moves the visible layer. Scrolling past the bottom drops back to showing
     * the whole build, and scrolling past the top simply stops.
     *
     * @return true if anything actually changed
     */
    public boolean shiftLayer(int delta) {
        int top = maxLayer();
        if (layerView == LayerView.ALL) {
            if (delta > 0) {
                layerView = LayerView.SINGLE;
                layer = 0;
                return true;
            }
            return false;
        }

        int next = layer + delta;
        if (next < 0) {
            layerView = LayerView.ALL;
            layer = 0;
            return true;
        }
        if (next > top) {
            layer = top;
            return false;
        }
        layer = next;
        return true;
    }

    /** Index of the topmost layer in the schematic you are currently targeting. */
    public int maxLayer() {
        Schematic schematic = targetSchematic();
        return schematic == null ? 0 : Math.max(0, schematic.height() - 1);
    }

    // ---- what you are pointing at ----------------------------------------

    public String pendingSchematicKey() {
        return pendingSchematicKey;
    }

    public void setPendingSchematicKey(String key) {
        this.pendingSchematicKey = key;
    }

    public boolean hasPending() {
        return pendingSchematicKey != null;
    }

    public void cancelPending() {
        this.pendingSchematicKey = null;
    }

    /**
     * The schematic the hologram, layer view and resource list all refer to.
     * That is whatever you are about to place, or otherwise the selected
     * placement.
     */
    public Schematic targetSchematic() {
        if (pendingSchematicKey != null) {
            SchematicLibrary.Entry entry = SchematicLibrary.INSTANCE.byKey(pendingSchematicKey);
            return entry == null ? null : entry.get();
        }
        Placement placement = PlacementManager.INSTANCE.selected();
        if (placement == null) {
            return null;
        }
        SchematicLibrary.Entry entry = SchematicLibrary.INSTANCE.byKey(placement.schematicKey());
        return entry == null ? null : entry.get();
    }

    public String targetSchematicKey() {
        if (pendingSchematicKey != null) {
            return pendingSchematicKey;
        }
        Placement placement = PlacementManager.INSTANCE.selected();
        return placement == null ? null : placement.schematicKey();
    }

    // ---- resource list ----------------------------------------------------

    /**
     * Whether the corner list should be following the build you are working on.
     *
     * <p>For a placement the answer lives on the placement itself and is written
     * out with it, so a build you are part way through still has its list the
     * next time you log in. A schematic you are only holding has nowhere to keep
     * that, so it falls back to a flag that lasts as long as the session.</p>
     */
    public boolean resourceListVisible() {
        Placement placement = pendingSchematicKey == null ? PlacementManager.INSTANCE.selected() : null;
        return placement != null ? placement.resourceList() : resourceListVisible;
    }

    public void setResourceListVisible(boolean visible) {
        Placement placement = pendingSchematicKey == null ? PlacementManager.INSTANCE.selected() : null;
        if (placement != null) {
            placement.setResourceList(visible);
            PlacementManager.INSTANCE.markDirty();
        } else {
            this.resourceListVisible = visible;
        }
    }

    public void reset() {
        selection.clear();
        pendingSchematicKey = null;
        layerView = LayerView.ALL;
        layer = 0;
        renderHolograms = true;
        resourceListVisible = false;
    }
}
