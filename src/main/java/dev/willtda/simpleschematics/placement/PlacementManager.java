package dev.willtda.simpleschematics.placement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.util.DataPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps track of what is placed where, and writes it straight to disk so
 * nothing is lost on a relog or a crash. Placements for every world live in one
 * file, keyed by world or server, so the whole lot moves as a unit.
 */
public final class PlacementManager {

    public static final PlacementManager INSTANCE = new PlacementManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, List<Placement>> byWorld = new LinkedHashMap<>();
    /** Which placement was selected in each world, by id, so a relog lands you back on it. */
    private final Map<String, String> selectedByWorld = new LinkedHashMap<>();
    private String currentWorld = "unknown";
    private int selected = -1;
    private boolean loaded;
    private boolean dirty;

    private PlacementManager() {
    }

    // ---- lifecycle --------------------------------------------------------

    public void onJoinWorld() {
        load();
        currentWorld = DataPaths.currentWorldKey();
        List<Placement> list = current();
        selected = list.isEmpty() ? -1 : 0;
        String remembered = selectedByWorld.get(currentWorld);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(remembered)) {
                selected = i;
                break;
            }
        }
        SimpleSchematics.LOG.info("Restored {} placement(s) for {}", list.size(), currentWorld);
    }

    public void onLeaveWorld() {
        saveIfDirty();
        selected = -1;
    }

    /** Called on a timer so a crash never costs you more than a few seconds. */
    public void tick() {
        if (dirty) {
            saveIfDirty();
        }
    }

    // ---- access -----------------------------------------------------------

    public List<Placement> current() {
        if (!loaded) {
            load();
        }
        return byWorld.computeIfAbsent(currentWorld, k -> new ArrayList<>());
    }

    public Placement selected() {
        List<Placement> list = current();
        if (selected < 0 || selected >= list.size()) {
            return null;
        }
        return list.get(selected);
    }

    public int selectedIndex() {
        return selected;
    }

    public void select(int index) {
        List<Placement> list = current();
        this.selected = (index < 0 || index >= list.size()) ? -1 : index;
        rememberSelection();
    }

    /** Written with the placements, so the build you were on is the one you come back to. */
    private void rememberSelection() {
        Placement placement = selected();
        String id = placement == null ? null : placement.id();
        if (!java.util.Objects.equals(selectedByWorld.get(currentWorld), id)) {
            if (id == null) {
                selectedByWorld.remove(currentWorld);
            } else {
                selectedByWorld.put(currentWorld, id);
            }
            markDirty();
        }
    }

    public void select(Placement placement) {
        select(current().indexOf(placement));
    }

    public void add(Placement placement) {
        List<Placement> list = current();
        list.add(placement);
        selected = list.size() - 1;
        rememberSelection();
        markDirty();
    }

    public void remove(Placement placement) {
        List<Placement> list = current();
        int index = list.indexOf(placement);
        if (index >= 0) {
            list.remove(index);
            if (selected >= list.size()) {
                selected = list.size() - 1;
            }
            rememberSelection();
            markDirty();
        }
    }

    public void clearCurrentWorld() {
        current().clear();
        selected = -1;
        rememberSelection();
        markDirty();
    }

    public void markDirty() {
        dirty = true;
    }

    // ---- storage ----------------------------------------------------------

    public void load() {
        loaded = true;
        byWorld.clear();
        selectedByWorld.clear();
        Path file = DataPaths.placementsFile();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return;
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonObject worlds = root.has("worlds") ? root.getAsJsonObject("worlds") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : worlds.entrySet()) {
                List<Placement> list = new ArrayList<>();
                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    try {
                        list.add(Placement.fromJson(element.getAsJsonObject()));
                    } catch (Exception e) {
                        SimpleSchematics.LOG.warn("Skipping a placement that could not be read", e);
                    }
                }
                byWorld.put(entry.getKey(), list);
            }
            if (root.has("selected")) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("selected").entrySet()) {
                    selectedByWorld.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (Exception e) {
            SimpleSchematics.LOG.error("Could not read the saved placements", e);
        }
    }

    public void saveIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject worlds = new JsonObject();
        for (Map.Entry<String, List<Placement>> entry : byWorld.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            JsonArray array = new JsonArray();
            for (Placement placement : entry.getValue()) {
                array.add(placement.toJson());
            }
            worlds.add(entry.getKey(), array);
        }
        root.add("worlds", worlds);
        JsonObject selectedIds = new JsonObject();
        for (Map.Entry<String, String> entry : selectedByWorld.entrySet()) {
            if (byWorld.containsKey(entry.getKey()) && !byWorld.get(entry.getKey()).isEmpty()) {
                selectedIds.addProperty(entry.getKey(), entry.getValue());
            }
        }
        root.add("selected", selectedIds);

        Path file = DataPaths.placementsFile();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            SimpleSchematics.LOG.error("Could not save the placements", e);
        }
    }
}
