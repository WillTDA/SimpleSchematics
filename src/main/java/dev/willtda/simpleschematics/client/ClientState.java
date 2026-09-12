package dev.willtda.simpleschematics.client;

import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.placement.PlacementManager;
import dev.willtda.simpleschematics.resource.ResourceListManager;
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
    /** The placement the layer state currently belongs to, so a change of selection restores its own. */
    private String layerOwner;

    /** Set while a schematic follows your crosshair, before you commit to a spot. */
    private String pendingSchematicKey;

    private boolean resourceListVisible;
    private boolean buildListVisible;
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
        storeLayer();
    }

    /**
     * Keeps the layer with the build it belongs to. Called every tick: when
     * the selection moves to another placement, that placement's remembered
     * layer comes back, and a schematic on your crosshair always starts whole.
     *
     * <p>The layer is remembered against a stamp of the schematic file. If
     * the file was replaced while you were away the layer means nothing any
     * more, so it falls back to everything rather than to a slice of a build
     * that may not have that many layers.</p>
     */
    public void syncLayer() {
        Placement placement = pendingSchematicKey == null ? PlacementManager.INSTANCE.selected() : null;
        String owner = placement == null ? null : placement.id();
        if (java.util.Objects.equals(owner, layerOwner)) {
            return;
        }
        layerOwner = owner;
        int remembered = placement == null ? -1 : placement.rememberedLayer(stampOf(placement));
        if (remembered < 0) {
            layerView = LayerView.ALL;
            layer = 0;
        } else {
            layerView = LayerView.SINGLE;
            layer = Math.min(remembered, maxLayer());
        }
    }

    private void storeLayer() {
        Placement placement = pendingSchematicKey == null ? PlacementManager.INSTANCE.selected() : null;
        if (placement == null || !placement.id().equals(layerOwner)) {
            return;
        }
        placement.rememberLayer(layerView == LayerView.SINGLE ? layer : -1, stampOf(placement));
        PlacementManager.INSTANCE.markDirty();
    }

    private static String stampOf(Placement placement) {
        SchematicLibrary.Entry entry = SchematicLibrary.INSTANCE.byKey(placement.schematicKey());
        return entry == null ? "" : entry.stamp();
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
                storeLayer();
                return true;
            }
            return false;
        }

        int next = layer + delta;
        if (next < 0) {
            layerView = LayerView.ALL;
            layer = 0;
            storeLayer();
            return true;
        }
        if (next > top) {
            layer = top;
            return false;
        }
        layer = next;
        storeLayer();
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

    /**
     * What to call the build the resource list is following: the placement's
     * own name, or the file name of a schematic still on your crosshair. Null
     * when there is nothing to follow.
     */
    public String targetName() {
        if (pendingSchematicKey != null) {
            SchematicLibrary.Entry entry = SchematicLibrary.INSTANCE.byKey(pendingSchematicKey);
            return entry == null ? null : entry.displayName;
        }
        Placement placement = PlacementManager.INSTANCE.selected();
        return placement == null ? null : placement.displayName();
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

    /**
     * Switches the corner list on or off for whatever you are working on.
     *
     * <p>The settings carry a master switch of their own, and leaving that off
     * used to be the one way to ask for the list, be told it was on, and see
     * nothing. Asking for it here turns that switch back on rather than
     * reporting a state the screen does not agree with.</p>
     *
     * @return the state it landed on, so the caller can announce it
     */
    public boolean toggleResourceList() {
        boolean visible = !resourceListVisible();
        setResourceListVisible(visible);
        if (visible) {
            if (!SSConfig.INSTANCE.resourceListEnabled.get()) {
                SSConfig.INSTANCE.resourceListEnabled.set(true);
                SSConfig.SPEC.save();
            }
            ResourceListManager.INSTANCE.refreshNow();
        }
        return visible;
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

    // ---- build list -------------------------------------------------------

    /**
     * Whether the build list is following the build you are working on. Kept
     * the same way as the resource list: on the placement when there is one,
     * otherwise on a flag that lasts the session.
     */
    public boolean buildListVisible() {
        Placement placement = pendingSchematicKey == null ? PlacementManager.INSTANCE.selected() : null;
        return placement != null ? placement.buildList() : buildListVisible;
    }

    /**
     * Switches the build list on or off for whatever you are working on, and
     * turns the master switch in the settings back on rather than reporting a
     * state nothing on screen agrees with.
     *
     * @return the state it landed on, so the caller can announce it
     */
    public boolean toggleBuildList() {
        boolean visible = !buildListVisible();
        setBuildListVisible(visible);
        if (visible && !SSConfig.INSTANCE.buildListEnabled.get()) {
            SSConfig.INSTANCE.buildListEnabled.set(true);
            SSConfig.SPEC.save();
        }
        return visible;
    }

    public void setBuildListVisible(boolean visible) {
        Placement placement = pendingSchematicKey == null ? PlacementManager.INSTANCE.selected() : null;
        if (placement != null) {
            placement.setBuildList(visible);
            PlacementManager.INSTANCE.markDirty();
        } else {
            this.buildListVisible = visible;
        }
    }

    public void reset() {
        selection.clear();
        pendingSchematicKey = null;
        layerView = LayerView.ALL;
        layer = 0;
        layerOwner = null;
        renderHolograms = true;
        resourceListVisible = false;
        buildListVisible = false;
    }
}
