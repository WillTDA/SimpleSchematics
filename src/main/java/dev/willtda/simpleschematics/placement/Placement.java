package dev.willtda.simpleschematics.placement;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.willtda.simpleschematics.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Where a schematic sits in a particular world.
 *
 * <p>Placements are stored outside the world save, keyed by world or server, so
 * they survive a relog and travel with the rest of your data folder.</p>
 */
public final class Placement {

    private final String id;
    private String schematicKey;
    private String displayName;
    private BlockPos origin;
    private Rotation rotation = Rotation.NONE;
    private Mirror mirror = Mirror.NONE;
    private boolean visible = true;
    /** Whether the corner overlay follows this build. Remembered between sessions. */
    private boolean resourceList;
    /** Whether the build list follows this build. Remembered the same way. */
    private boolean buildList;
    /** The layer you were last looking at, or -1 for the whole build. */
    private int layer = -1;
    /** The schematic file the layer was remembered against, so a changed file resets it. */
    private String layerStamp = "";
    /** The exact file and transform last printed, used only for the resume prompt. */
    private String printStamp = "";
    /**
     * Chests you have marked as holding materials for this build, and the last
     * contents seen in each. The counts are needed because a client only ever
     * sees inside a container while it is open.
     */
    private final Map<BlockPos, Map<String, Integer>> banks = new LinkedHashMap<>();
    /**
     * Blocks you have said are fine as they are, by schematic index, whatever
     * the schematic wants there. A deliberate change to a build would otherwise
     * be a red block, a missing block and a line on both lists for ever.
     */
    private final Set<Integer> accepted = new HashSet<>();

    public Placement(String schematicKey, String displayName, BlockPos origin) {
        this(java.util.UUID.randomUUID().toString(), schematicKey, displayName, origin);
    }

    private Placement(String id, String schematicKey, String displayName, BlockPos origin) {
        this.id = id;
        this.schematicKey = schematicKey;
        this.displayName = displayName;
        this.origin = origin.immutable();
    }

    /** Stable across relogs, so per placement state such as the diff can be keyed on it. */
    public String id() {
        return id;
    }

    public boolean hasPrintProgress(String stamp) {
        return printStamp.equals(stamp);
    }

    public void markPrintStarted(String stamp) {
        printStamp = stamp;
    }

    public String schematicKey() {
        return schematicKey;
    }

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(String name) {
        this.displayName = name;
    }

    public BlockPos origin() {
        return origin;
    }

    public void setOrigin(BlockPos pos) {
        this.origin = pos.immutable();
    }

    public void move(int dx, int dy, int dz) {
        this.origin = this.origin.offset(dx, dy, dz);
    }

    public Rotation rotation() {
        return rotation;
    }

    public void setRotation(Rotation rotation) {
        this.rotation = rotation;
    }

    public void rotateClockwise() {
        this.rotation = this.rotation.getRotated(Rotation.CLOCKWISE_90);
    }

    public Mirror mirror() {
        return mirror;
    }

    public void setMirror(Mirror mirror) {
        this.mirror = mirror;
    }

    public void cycleMirror() {
        this.mirror = switch (mirror) {
            case NONE -> Mirror.LEFT_RIGHT;
            case LEFT_RIGHT -> Mirror.FRONT_BACK;
            case FRONT_BACK -> Mirror.NONE;
        };
    }

    public boolean visible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void toggleVisible() {
        this.visible = !this.visible;
    }

    public boolean resourceList() {
        return resourceList;
    }

    public void setResourceList(boolean value) {
        this.resourceList = value;
    }

    public boolean toggleResourceList() {
        this.resourceList = !this.resourceList;
        return this.resourceList;
    }

    public boolean buildList() {
        return buildList;
    }

    public void setBuildList(boolean value) {
        this.buildList = value;
    }

    // ---- remembered layer -------------------------------------------------

    /** The layer to come back to for this version of the schematic, or -1 for everything. */
    public int rememberedLayer(String stamp) {
        return stamp.equals(layerStamp) ? layer : -1;
    }

    public void rememberLayer(int layer, String stamp) {
        this.layer = layer;
        this.layerStamp = layer < 0 ? "" : stamp;
    }

    // ---- material banks ---------------------------------------------------

    public Set<BlockPos> bankPositions() {
        return Collections.unmodifiableSet(banks.keySet());
    }

    public boolean isBank(BlockPos pos) {
        return banks.containsKey(pos);
    }

    /** @return true if the chest is now a bank, false if it has just stopped being one */
    public boolean toggleBank(BlockPos pos) {
        BlockPos key = pos.immutable();
        if (banks.remove(key) != null) {
            return false;
        }
        banks.put(key, new LinkedHashMap<>());
        return true;
    }

    /**
     * Replaces what this chest is holding rather than adding to it, so opening
     * the same chest twice cannot count its contents twice.
     */
    public void setBankContents(BlockPos pos, Map<String, Integer> contents) {
        BlockPos key = pos.immutable();
        if (banks.containsKey(key)) {
            banks.put(key, new LinkedHashMap<>(contents));
        }
    }

    public Map<BlockPos, Map<String, Integer>> banks() {
        return Collections.unmodifiableMap(banks);
    }

    // ---- accepted blocks --------------------------------------------------

    /** Whether the block at this schematic index is to be taken as correct however it stands. */
    public boolean isAccepted(int index) {
        return accepted.contains(index);
    }

    /** @return whether the block is accepted after the flip */
    public boolean toggleAccepted(int index) {
        if (accepted.remove(index)) {
            return false;
        }
        accepted.add(index);
        return true;
    }

    public void setAccepted(int index, boolean value) {
        if (value) {
            accepted.add(index);
        } else {
            accepted.remove(index);
        }
    }

    public int acceptedCount() {
        return accepted.size();
    }

    public void clearAccepted() {
        accepted.clear();
    }

    /** The index the verifier and the bake both use for a local coordinate. */
    public static int indexOf(Schematic schematic, int x, int y, int z) {
        return (y * schematic.length() + z) * schematic.width() + x;
    }

    /** Footprint after rotation, which swaps width and length on the quarter turns. */
    public Vec3i effectiveSize(Schematic schematic) {
        boolean swap = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
        return swap
                ? new Vec3i(schematic.length(), schematic.height(), schematic.width())
                : new Vec3i(schematic.width(), schematic.height(), schematic.length());
    }

    public AABB bounds(Schematic schematic) {
        Vec3i size = effectiveSize(schematic);
        return new AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size.getX(), origin.getY() + size.getY(), origin.getZ() + size.getZ());
    }

    /**
     * Maps a local schematic coordinate to a world position, applying the
     * mirror first and then the rotation, which is the order the vanilla
     * structure system uses.
     */
    public BlockPos toWorld(Schematic schematic, int x, int y, int z) {
        int w = schematic.width();
        int l = schematic.length();

        int mx = x;
        int mz = z;
        switch (mirror) {
            case LEFT_RIGHT -> mz = l - 1 - z;
            case FRONT_BACK -> mx = w - 1 - x;
            default -> {
            }
        }

        int rx;
        int rz;
        switch (rotation) {
            case CLOCKWISE_90 -> {
                rx = l - 1 - mz;
                rz = mx;
            }
            case CLOCKWISE_180 -> {
                rx = w - 1 - mx;
                rz = l - 1 - mz;
            }
            case COUNTERCLOCKWISE_90 -> {
                rx = mz;
                rz = w - 1 - mx;
            }
            default -> {
                rx = mx;
                rz = mz;
            }
        }
        return new BlockPos(origin.getX() + rx, origin.getY() + y, origin.getZ() + rz);
    }

    /**
     * The local coordinate a world position lands on, or null when it is
     * outside the build. Undoes {@link #toWorld} step by step: the rotation
     * first, then the mirror, which is its own inverse.
     */
    public BlockPos toLocal(Schematic schematic, BlockPos world) {
        int w = schematic.width();
        int l = schematic.length();
        int rx = world.getX() - origin.getX();
        int y = world.getY() - origin.getY();
        int rz = world.getZ() - origin.getZ();

        int mx;
        int mz;
        switch (rotation) {
            case CLOCKWISE_90 -> {
                mx = rz;
                mz = l - 1 - rx;
            }
            case CLOCKWISE_180 -> {
                mx = w - 1 - rx;
                mz = l - 1 - rz;
            }
            case COUNTERCLOCKWISE_90 -> {
                mx = w - 1 - rz;
                mz = rx;
            }
            default -> {
                mx = rx;
                mz = rz;
            }
        }

        int x = mx;
        int z = mz;
        switch (mirror) {
            case LEFT_RIGHT -> z = l - 1 - mz;
            case FRONT_BACK -> x = w - 1 - mx;
            default -> {
            }
        }
        if (x < 0 || x >= w || y < 0 || y >= schematic.height() || z < 0 || z >= l) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    /**
     * The local coordinate a point in the world lands on, left continuous
     * rather than snapped to a block. This is for measuring the camera against
     * the schematic in its own space, so that a walk over its blocks can skip
     * the distant ones without transforming any of them. It undoes
     * {@link #toWorld} the same way {@link #toLocal} does, on block centres
     * rather than block corners.
     */
    public Vec3 toLocalPoint(Schematic schematic, Vec3 world) {
        double w = schematic.width();
        double l = schematic.length();
        double rx = world.x - origin.getX();
        double y = world.y - origin.getY();
        double rz = world.z - origin.getZ();

        double mx;
        double mz;
        switch (rotation) {
            case CLOCKWISE_90 -> {
                mx = rz;
                mz = l - rx;
            }
            case CLOCKWISE_180 -> {
                mx = w - rx;
                mz = l - rz;
            }
            case COUNTERCLOCKWISE_90 -> {
                mx = w - rz;
                mz = rx;
            }
            default -> {
                mx = rx;
                mz = rz;
            }
        }

        double x = mx;
        double z = mz;
        switch (mirror) {
            case LEFT_RIGHT -> z = l - mz;
            case FRONT_BACK -> x = w - mx;
            default -> {
            }
        }
        return new Vec3(x, y, z);
    }

    // ---- persistence ------------------------------------------------------

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("schematic", schematicKey);
        json.addProperty("name", displayName);
        json.addProperty("x", origin.getX());
        json.addProperty("y", origin.getY());
        json.addProperty("z", origin.getZ());
        json.addProperty("rotation", rotation.name());
        json.addProperty("mirror", mirror.name());
        json.addProperty("visible", visible);
        json.addProperty("resourceList", resourceList);
        json.addProperty("buildList", buildList);
        if (!printStamp.isEmpty()) json.addProperty("printStamp", printStamp);
        if (layer >= 0) {
            json.addProperty("layer", layer);
            json.addProperty("layerStamp", layerStamp);
        }
        JsonArray bankArray = new JsonArray();
        for (Map.Entry<BlockPos, Map<String, Integer>> entry : banks.entrySet()) {
            JsonObject bank = new JsonObject();
            bank.addProperty("x", entry.getKey().getX());
            bank.addProperty("y", entry.getKey().getY());
            bank.addProperty("z", entry.getKey().getZ());
            JsonObject items = new JsonObject();
            entry.getValue().forEach(items::addProperty);
            bank.add("items", items);
            bankArray.add(bank);
        }
        json.add("banks", bankArray);
        if (!accepted.isEmpty()) {
            JsonArray acceptedArray = new JsonArray();
            for (int index : accepted) {
                acceptedArray.add(index);
            }
            json.add("accepted", acceptedArray);
        }
        return json;
    }

    public static Placement fromJson(JsonObject json) {
        Placement placement = new Placement(
                json.has("id") ? json.get("id").getAsString() : java.util.UUID.randomUUID().toString(),
                json.get("schematic").getAsString(),
                json.has("name") ? json.get("name").getAsString() : json.get("schematic").getAsString(),
                new BlockPos(json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt()));
        try {
            placement.rotation = Rotation.valueOf(json.get("rotation").getAsString());
        } catch (Exception ignored) {
            placement.rotation = Rotation.NONE;
        }
        try {
            placement.mirror = Mirror.valueOf(json.get("mirror").getAsString());
        } catch (Exception ignored) {
            placement.mirror = Mirror.NONE;
        }
        placement.visible = !json.has("visible") || json.get("visible").getAsBoolean();
        placement.resourceList = json.has("resourceList") && json.get("resourceList").getAsBoolean();
        placement.buildList = json.has("buildList") && json.get("buildList").getAsBoolean();
        placement.printStamp = json.has("printStamp") ? json.get("printStamp").getAsString() : "";
        if (json.has("layer") && json.has("layerStamp")) {
            placement.layer = json.get("layer").getAsInt();
            placement.layerStamp = json.get("layerStamp").getAsString();
        }
        if (json.has("accepted")) {
            for (JsonElement element : json.getAsJsonArray("accepted")) {
                placement.accepted.add(element.getAsInt());
            }
        }
        if (json.has("banks")) {
            for (JsonElement element : json.getAsJsonArray("banks")) {
                JsonObject bank = element.getAsJsonObject();
                Map<String, Integer> items = new LinkedHashMap<>();
                if (bank.has("items")) {
                    for (Map.Entry<String, JsonElement> item : bank.getAsJsonObject("items").entrySet()) {
                        items.put(item.getKey(), item.getValue().getAsInt());
                    }
                }
                placement.banks.put(new BlockPos(bank.get("x").getAsInt(),
                        bank.get("y").getAsInt(), bank.get("z").getAsInt()), items);
            }
        }
        return placement;
    }
}
