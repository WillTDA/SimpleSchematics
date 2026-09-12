package dev.willtda.simpleschematics.resource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.client.ClientState;
import dev.willtda.simpleschematics.client.InputHandler;
import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.placement.PlacementManager;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.render.SchematicVerifier;
import dev.willtda.simpleschematics.schematic.Schematic;
import dev.willtda.simpleschematics.util.DataPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out what a build needs, what you are carrying, and what is left.
 *
 * <p>Progress is written to disk against the schematic, so ticking something
 * off survives a relog and travels with the rest of your data folder.</p>
 */
public final class ResourceListManager {

    public static final ResourceListManager INSTANCE = new ResourceListManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /**
     * Five times a second. The list is meant to read as live while you empty a
     * shulker into a chest, and a recount is a walk over one inventory.
     */
    private static final int REFRESH_INTERVAL_TICKS = 4;

    /**
     * One line of the list.
     *
     * <p>Ticking a row off says the item is dealt with, whatever the counting
     * found, so a ticked row wants nothing. That has to be true here rather
     * than only in {@link #complete()}: the total in the header is added up
     * from {@link #missing()}, and a ticked row was still being counted there.</p>
     *
     * @param placed what is already standing correctly in the world, which
     *               counts towards the requirement the same as anything carried
     * @param manual the correction entered by hand, on top of what was counted
     */
    public record Row(Item item, int required, int placed, int available, int manual, boolean ticked) {
        public int have() {
            return ticked ? required : Math.min(required, placed + available + manual);
        }

        public int missing() {
            return ticked ? 0 : Math.max(0, required - placed - available - manual);
        }

        public boolean complete() {
            return missing() == 0;
        }
    }

    /** Everything saved against one schematic. */
    private static final class Progress {
        final Map<Item, Integer> manual = new HashMap<>();
        final Set<Item> ticked = new HashSet<>();
        boolean dirty;
    }

    private final Map<String, Map<Item, Integer>> requiredCache = new HashMap<>();
    private final Map<String, Progress> progressCache = new HashMap<>();
    private final Map<Item, Integer> available = new HashMap<>();

    private String activeKey;
    private List<Row> rows = new ArrayList<>();
    private int tickCounter;

    private ResourceListManager() {
    }

    // ---- lifecycle --------------------------------------------------------

    public void tick() {
        String key = ClientState.INSTANCE.targetSchematicKey();
        if (key == null) {
            if (activeKey != null) {
                activeKey = null;
                rows = new ArrayList<>();
            }
            return;
        }
        if (!key.equals(activeKey)) {
            activeKey = key;
            refreshNow();
            return;
        }
        // Cheap, and it only writes when something actually moved. Doing it every
        // tick means shutting a chest straight after a transfer still records it.
        snapshotOpenBank();
        if (++tickCounter >= REFRESH_INTERVAL_TICKS) {
            tickCounter = 0;
            refreshNow();
        }
    }

    public void refreshNow() {
        activeKey = ClientState.INSTANCE.targetSchematicKey();
        if (activeKey == null) {
            rows = new ArrayList<>();
            return;
        }
        Schematic schematic = ClientState.INSTANCE.targetSchematic();
        if (schematic == null) {
            rows = new ArrayList<>();
            return;
        }
        Map<Item, Integer> required = requiredFor(activeKey, schematic);
        Map<Item, Integer> placed = countPlaced();
        countAvailable();
        Progress progress = progressFor(activeKey);

        List<Row> built = new ArrayList<>(required.size());
        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            Item item = entry.getKey();
            int need = entry.getValue();
            int standing = Math.min(need, placed.getOrDefault(item, 0));
            int have = available.getOrDefault(item, 0);
            int manual = progress.manual.getOrDefault(item, 0);
            built.add(new Row(item, need, standing, have, manual, progress.ticked.contains(item)));
        }

        // biggest job first, which is what you want when planning a trip to the mine
        built.sort(Comparator
                .comparingInt((Row r) -> r.complete() ? 1 : 0)
                .thenComparing(Comparator.comparingInt(Row::missing).reversed())
                .thenComparing(Comparator.comparingInt(Row::required).reversed())
                .thenComparing(r -> itemName(r.item())));
        rows = built;

        if (progress.dirty) {
            saveProgress(activeKey, progress);
        }
    }

    public List<Row> rows() {
        return rows;
    }

    /** Rows after the hide and auto remove settings have been applied. */
    public List<Row> visibleRows() {
        boolean hideComplete = SSConfig.INSTANCE.hideCompletedRows.get()
                || SSConfig.INSTANCE.removeCollectedItems.get();
        if (!hideComplete) {
            return rows;
        }
        List<Row> filtered = new ArrayList<>(rows.size());
        for (Row row : rows) {
            if (!row.complete()) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    public boolean hasTarget() {
        return activeKey != null && !rows.isEmpty();
    }

    public int totalMissing() {
        int total = 0;
        for (Row row : rows) {
            total += row.missing();
        }
        return total;
    }

    public int totalRequired() {
        int total = 0;
        for (Row row : rows) {
            total += row.required();
        }
        return total;
    }

    // ---- editing ----------------------------------------------------------

    public void toggleTick(Item item) {
        if (activeKey == null) {
            return;
        }
        Progress progress = progressFor(activeKey);
        if (!progress.ticked.remove(item)) {
            progress.ticked.add(item);
        }
        progress.dirty = true;
        saveProgress(activeKey, progress);
        refreshNow();
    }

    public void adjustManual(Item item, int delta) {
        if (activeKey == null) {
            return;
        }
        Progress progress = progressFor(activeKey);
        int next = Math.max(0, progress.manual.getOrDefault(item, 0) + delta);
        if (next == 0) {
            progress.manual.remove(item);
        } else {
            progress.manual.put(item, next);
        }
        progress.dirty = true;
        saveProgress(activeKey, progress);
        refreshNow();
    }

    public void setManual(Item item, int value) {
        if (activeKey == null) {
            return;
        }
        Progress progress = progressFor(activeKey);
        if (value <= 0) {
            progress.manual.remove(item);
        } else {
            progress.manual.put(item, value);
        }
        progress.dirty = true;
        saveProgress(activeKey, progress);
        refreshNow();
    }

    /** Whether anything has been ticked off or corrected by hand yet. */
    public boolean hasProgress() {
        if (activeKey == null) {
            return false;
        }
        Progress progress = progressCache.get(activeKey);
        return progress != null && (!progress.manual.isEmpty() || !progress.ticked.isEmpty());
    }

    public void resetProgress() {
        if (activeKey == null) {
            return;
        }
        Progress progress = progressFor(activeKey);
        progress.manual.clear();
        progress.ticked.clear();
        progress.dirty = true;
        saveProgress(activeKey, progress);
        refreshNow();
    }

    /**
     * Copies what is in the open container into the bank it belongs to.
     *
     * <p>A client can only see inside a container while its screen is up, so
     * this is the one moment the contents can be recorded. It replaces the
     * stored contents rather than adding to them, which means opening the same
     * chest again simply corrects the figure instead of doubling it.</p>
     */
    public void snapshotOpenBank() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        BlockPos pos = openBankPos(mc);
        Placement placement = PlacementManager.INSTANCE.selected();
        if (pos == null || player == null || placement == null || !placement.isBank(pos)) {
            return;
        }

        Map<String, Integer> counted = new LinkedHashMap<>();
        for (Slot slot : ((AbstractContainerScreen<?>) mc.screen).getMenu().slots) {
            if (isPlayers(slot, player)) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                counted.merge(idOf(stack.getItem()), stack.getCount(), Integer::sum);
            }
        }
        if (!counted.equals(placement.banks().get(pos))) {
            placement.setBankContents(pos, counted);
            PlacementManager.INSTANCE.markDirty();
            refreshNow();
        }
    }

    /**
     * The block whose container screen is open, already reduced to the half of
     * a double chest that banks are stored under, or null.
     *
     * <p>It has to be a real container block, and it has to be a screen that is
     * not your own inventory. Without both checks, opening your inventory just
     * after closing a chest would still look like that chest was open: its bank
     * would drop out of the count, and the snapshot would write your armour and
     * crafting grid into it.</p>
     */
    private static BlockPos openBankPos(Minecraft mc) {
        if (mc.level == null || countableScreen(mc) == null) {
            return null;
        }
        BlockPos pos = InputHandler.lastUsedBlock();
        if (pos == null || !Banks.isContainer(mc.level, pos)) {
            return null;
        }
        return Banks.canonical(mc.level, pos);
    }

    /**
     * The open container screen worth counting, or null.
     *
     * <p>Your own inventory is excluded because everything in it is counted
     * directly, and the creative menu because its slots are backed by a list of
     * every item in the game.</p>
     */
    private static AbstractContainerScreen<?> countableScreen(Minecraft mc) {
        if (mc.screen instanceof InventoryScreen || mc.screen instanceof CreativeModeInventoryScreen) {
            return null;
        }
        return mc.screen instanceof AbstractContainerScreen<?> screen ? screen : null;
    }

    /**
     * Whether a slot is one the player already had counted.
     *
     * <p>Menus put the player's own inventory in among their slots, and the
     * ender chest screen is backed by the very inventory the ender chest
     * setting counts. Walking the slots and asking what each one belongs to is
     * exact, where assuming the last thirty six slots are the player's is only
     * true for chest shaped menus.</p>
     */
    private static boolean isPlayers(Slot slot, LocalPlayer player) {
        if (slot.container == player.getInventory()) {
            return true;
        }
        return SSConfig.INSTANCE.countEnderChest.get()
                && slot.container == player.getEnderChestInventory();
    }

    // ---- counting ---------------------------------------------------------

    /**
     * What the followed placement already has standing, from the verifier's
     * last full pass over it. A schematic still on your crosshair, or one
     * being read from the library, has nothing placed yet, and the first pass
     * after selecting a build takes a moment, during which the list reads as
     * if nothing were built.
     */
    private Map<Item, Integer> countPlaced() {
        if (!SSConfig.INSTANCE.countPlacedBlocks.get()) {
            return Map.of();
        }
        Placement placement = ClientState.INSTANCE.followedPlacement();
        return placement == null ? Map.of() : SchematicVerifier.INSTANCE.resultFor(placement).placed();
    }

    private void countAvailable() {
        available.clear();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        for (ItemStack stack : player.getInventory().items) {
            add(stack);
        }
        for (ItemStack stack : player.getInventory().offhand) {
            add(stack);
        }
        // Held on the cursor, so it has already left whichever slot it came from.
        add(player.containerMenu.getCarried());

        if (SSConfig.INSTANCE.countEnderChest.get()) {
            var ender = player.getEnderChestInventory();
            for (int i = 0; i < ender.getContainerSize(); i++) {
                add(ender.getItem(i));
            }
        }

        AbstractContainerScreen<?> screen = countableScreen(mc);
        if (SSConfig.INSTANCE.countOpenContainers.get() && screen != null) {
            for (Slot slot : screen.getMenu().slots) {
                if (!isPlayers(slot, player)) {
                    add(slot.getItem());
                }
            }
        }

        countBanks(mc);
    }

    /**
     * Everything sitting in the chests marked for this build.
     *
     * <p>A bank that is open on screen has just been counted slot by slot, so
     * it is skipped here rather than counted again from its stored contents.</p>
     */
    private void countBanks(Minecraft mc) {
        Placement placement = ClientState.INSTANCE.followedPlacement();
        if (placement == null || placement.banks().isEmpty()) {
            return;
        }
        BlockPos open = SSConfig.INSTANCE.countOpenContainers.get() ? openBankPos(mc) : null;
        for (Map.Entry<BlockPos, Map<String, Integer>> bank : placement.banks().entrySet()) {
            if (bank.getKey().equals(open)) {
                continue;
            }
            for (Map.Entry<String, Integer> entry : bank.getValue().entrySet()) {
                Item item = itemFromId(entry.getKey());
                if (item != null && item != Items.AIR) {
                    available.merge(item, entry.getValue(), Integer::sum);
                }
            }
        }
    }

    private void add(ItemStack stack) {
        if (stack != null && !stack.isEmpty() && stack.getItem() != Items.AIR) {
            available.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }

    /** Everything the whole schematic costs, by item. Cached against the key, shared with the build list. */
    Map<Item, Integer> requiredFor(String key, Schematic schematic) {
        Map<Item, Integer> cached = requiredCache.get(key);
        if (cached != null) {
            return cached;
        }
        Map<Item, Integer> totals = new HashMap<>();
        for (int y = 0; y < schematic.height(); y++) {
            for (int z = 0; z < schematic.length(); z++) {
                for (int x = 0; x < schematic.width(); x++) {
                    BlockState state = schematic.getBlockState(x, y, z);
                    if (!state.isAir()) {
                        MaterialResolver.costOf(state).addTo(totals);
                    }
                }
            }
        }
        requiredCache.put(key, totals);
        return totals;
    }

    public void invalidate(String key) {
        requiredCache.remove(key);
        progressCache.remove(key);
        if (key.equals(activeKey)) {
            refreshNow();
        }
    }

    public void invalidateAll() {
        requiredCache.clear();
        progressCache.clear();
        rows = new ArrayList<>();
        activeKey = null;
    }

    // ---- storage ----------------------------------------------------------

    private Progress progressFor(String key) {
        return progressCache.computeIfAbsent(key, this::loadProgress);
    }

    private Path progressFile(String key) {
        return DataPaths.resourceLists().resolve(DataPaths.sanitise(key) + ".json");
    }

    private Progress loadProgress(String key) {
        Progress progress = new Progress();
        Path file = progressFile(key);
        if (!Files.exists(file)) {
            return progress;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return progress;
            }
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("stored")) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("stored").entrySet()) {
                    Item item = itemFromId(entry.getKey());
                    if (item != null) {
                        progress.manual.put(item, entry.getValue().getAsInt());
                    }
                }
            }
            if (root.has("ticked")) {
                for (JsonElement element : root.getAsJsonArray("ticked")) {
                    Item item = itemFromId(element.getAsString());
                    if (item != null) {
                        progress.ticked.add(item);
                    }
                }
            }
        } catch (Exception e) {
            SimpleSchematics.LOG.warn("Could not read the saved progress for {}", key, e);
        }
        return progress;
    }

    private void saveProgress(String key, Progress progress) {
        progress.dirty = false;
        JsonObject root = new JsonObject();
        root.addProperty("schematic", key);
        JsonObject stored = new JsonObject();
        for (Map.Entry<Item, Integer> entry : progress.manual.entrySet()) {
            stored.addProperty(idOf(entry.getKey()), entry.getValue());
        }
        root.add("stored", stored);
        var ticked = new com.google.gson.JsonArray();
        for (Item item : progress.ticked) {
            ticked.add(idOf(item));
        }
        root.add("ticked", ticked);

        try (Writer writer = Files.newBufferedWriter(progressFile(key), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (Exception e) {
            SimpleSchematics.LOG.error("Could not save the progress for {}", key, e);
        }
    }

    private static String idOf(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "minecraft:air" : id.toString();
    }

    private static Item itemFromId(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) {
            return null;
        }
        return BuiltInRegistries.ITEM.get(location);
    }

    public static String itemName(Item item) {
        return item.getDescription().getString();
    }
}
