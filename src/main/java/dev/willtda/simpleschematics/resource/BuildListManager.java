package dev.willtda.simpleschematics.resource;

import dev.willtda.simpleschematics.client.ClientState;
import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.render.SchematicVerifier;
import dev.willtda.simpleschematics.schematic.Schematic;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What still has to go down, for the build you have selected and the layer you
 * are looking at.
 *
 * <p>This is a different question from the resource list's. That one asks what
 * you need to go and fetch, and nets off your inventory and chests. This one
 * asks what is missing from the world in front of you, so a block you are
 * carrying but have not placed is still on it, and a wrong block is on it too
 * because the right one is not there yet.</p>
 *
 * <p>Whether a block is standing comes from the verifier's last full pass, the
 * same source the mismatch highlight draws from. Only loaded chunks can be
 * checked, so a build you have not been near reads as untouched.</p>
 */
public final class BuildListManager {

    public static final BuildListManager INSTANCE = new BuildListManager();

    /** One line: an item, how many of it are still to place, of how many the range wants. */
    public record Row(Item item, int remaining, int required) {
    }

    private List<Row> rows = new ArrayList<>();
    private int total;
    private String signature = "";
    /** The mask the rows were last counted from, compared by identity because a pass replaces it. */
    private BitSet countedFrom;

    private BuildListManager() {
    }

    /** Called every client tick. Recounts only when the target, the layer or the verifier's answer has moved. */
    public void tick() {
        ClientState state = ClientState.INSTANCE;
        if (!state.buildListVisible()) {
            return;
        }
        String key = state.targetSchematicKey();
        Schematic schematic = state.targetSchematic();
        if (key == null || schematic == null) {
            if (!rows.isEmpty()) {
                rows = new ArrayList<>();
                total = 0;
            }
            signature = "";
            countedFrom = null;
            return;
        }

        Placement placement = state.followedPlacement();
        BitSet mask = placement == null ? null : SchematicVerifier.INSTANCE.correctMaskFor(placement);
        String next = key + "|" + state.layerView() + "|" + state.layer()
                + "|" + (placement == null ? "" : placement.id());
        if (next.equals(signature) && mask == countedFrom) {
            return;
        }
        signature = next;
        countedFrom = mask;
        recount(key, schematic, placement, mask, state);
    }

    public List<Row> rows() {
        return rows;
    }

    /** Blocks still to place across every row. */
    public int total() {
        return total;
    }

    /**
     * Whole build: the cached requirement less what the verifier costed as
     * standing, which never walks the volume. One layer: a walk of that slice
     * against the mask, which is bounded by the footprint rather than the
     * height and is cheap even for a large build.
     */
    private void recount(String key, Schematic schematic, Placement placement, BitSet mask, ClientState state) {
        Map<Item, Integer> remaining = new HashMap<>();
        Map<Item, Integer> required;

        if (state.layerView() == ClientState.LayerView.SINGLE) {
            int y = Math.min(state.layer(), Math.max(0, schematic.height() - 1));
            required = new HashMap<>();
            // A block only part way there, an empty pot under a potted plant,
            // is not in the mask, so what is standing of it is taken off here.
            Map<Integer, MaterialResolver.Cost> partial = placement == null || mask == null
                    ? Map.of()
                    : SchematicVerifier.INSTANCE.resultFor(placement).partial();
            int w = schematic.width();
            int l = schematic.length();
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    BlockState block = schematic.getBlockState(x, y, z);
                    if (block.isAir()) {
                        continue;
                    }
                    MaterialResolver.Cost cost = MaterialResolver.costOf(block);
                    cost.addTo(required);
                    int index = (y * l + z) * w + x;
                    if (mask != null && mask.get(index)) {
                        continue;
                    }
                    cost.addTo(remaining);
                    MaterialResolver.Cost standing = partial.get(index);
                    if (standing != null) {
                        for (MaterialResolver.Cost.Entry entry : standing.entries()) {
                            remaining.merge(entry.item(), -entry.amount(), Integer::sum);
                        }
                    }
                }
            }
        } else {
            required = ResourceListManager.INSTANCE.requiredFor(key, schematic);
            Map<Item, Integer> placed = placement == null || mask == null
                    ? Map.of()
                    : SchematicVerifier.INSTANCE.resultFor(placement).placed();
            for (Map.Entry<Item, Integer> entry : required.entrySet()) {
                int left = entry.getValue() - placed.getOrDefault(entry.getKey(), 0);
                if (left > 0) {
                    remaining.put(entry.getKey(), left);
                }
            }
        }

        List<Row> built = new ArrayList<>(remaining.size());
        int sum = 0;
        for (Map.Entry<Item, Integer> entry : remaining.entrySet()) {
            Item item = entry.getKey();
            if (entry.getValue() <= 0) {
                continue;
            }
            built.add(new Row(item, entry.getValue(), required.getOrDefault(item, entry.getValue())));
            sum += entry.getValue();
        }
        // the most work first, so the top of the list is where the next stack goes
        built.sort(Comparator.comparingInt(Row::remaining).reversed()
                .thenComparing(r -> ResourceListManager.itemName(r.item())));
        rows = built;
        total = sum;
    }

    /** Forces the next tick to count again, for when the target has been replaced under it. */
    public void invalidate() {
        signature = "";
        countedFrom = null;
    }

    public void invalidateAll() {
        rows = new ArrayList<>();
        total = 0;
        invalidate();
    }
}
