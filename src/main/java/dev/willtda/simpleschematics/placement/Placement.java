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

import java.util.Collections;
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
    /**
     * Chests you have marked as holding materials for this build, and the last
     * contents seen in each. The counts are needed because a client only ever
     * sees inside a container while it is open.
     */
    private final Map<BlockPos, Map<String, Integer>> banks = new LinkedHashMap<>();

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
