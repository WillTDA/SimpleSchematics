package dev.willtda.simpleschematics.render;

import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.resource.MaterialResolver;
import dev.willtda.simpleschematics.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares what a placement says should be there against what actually is.
 *
 * <p>The comparison runs a slice at a time on the client tick so a large build
 * never costs a frame, and it loops forever rather than listening for block
 * updates. That is deliberate: a rescan is cheap, and it means the highlight
 * fixes itself after a chunk reload, a server correction or an undo, none of
 * which reliably produce a client side event.</p>
 *
 * <p>Expected states follow the placement's mirror and rotation. By default,
 * placement properties such as facing and half must match, while connections
 * and power may settle as construction progresses. Strict mode checks every
 * property.</p>
 *
 * <p>The same pass also adds up what the correctly placed blocks cost, which is
 * how the resource list knows what is already standing. That is why the
 * verifier is fed the selected placement even while the highlight is off: the
 * caller decides what gets checked, and the highlight switch only decides what
 * gets drawn.</p>
 */
public final class SchematicVerifier {

    public static final SchematicVerifier INSTANCE = new SchematicVerifier();

    /** One entry per mismatched block, carrying the layer so the layer filter can hide it. */
    public record Mark(BlockPos pos, int layer) {
    }

    /**
     * A finished comparison, safe to read from the render thread.
     *
     * @param missing what the empty positions still cost, in items rather than
     *                positions, so an unplaced door reads as one and not two
     * @param placed  what the correctly placed blocks cost, by item, in the same
     *                terms the resource list counts requirements
     */
    public record Diff(List<Mark> wrong, List<Mark> extra, int wrongCount, int extraCount,
                       int missing, int correct, int total, boolean truncated,
                       Map<Item, Integer> placed) {

        public boolean hasAnything() {
            return wrongCount > 0 || extraCount > 0;
        }

        public boolean isFinished() {
            return wrongCount == 0 && extraCount == 0 && missing == 0;
        }
    }

    private static final Diff EMPTY =
            new Diff(List.of(), List.of(), 0, 0, 0, 0, 0, false, Map.of());

    private final Map<String, State> states = new HashMap<>();
    private boolean enabled = true;

    private SchematicVerifier() {
    }

    public boolean isEnabled() {
        return enabled && SSConfig.INSTANCE.highlightMismatches.get();
    }

    /**
     * Switching the highlight off does not throw the results away. The resource
     * list may still be reading the selected placement's, and anything nobody
     * wants any more is dropped on the next tick.
     */
    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    /**
     * The master switch in the settings would otherwise let this report itself
     * on while nothing appeared, so asking for the highlight here turns that
     * switch back on rather than lying about the result.
     *
     * @return whether the highlight is actually going to draw
     */
    public boolean toggle() {
        if (!enabled && !SSConfig.INSTANCE.highlightMismatches.get()) {
            SSConfig.INSTANCE.highlightMismatches.set(true);
            SSConfig.SPEC.save();
        }
        setEnabled(!enabled);
        return isEnabled();
    }

    public void clear() {
        states.clear();
    }

    public void forget(String placementId) {
        states.remove(placementId);
    }

    /** The published result, or an empty diff while the first pass is still running. */
    public Diff resultFor(Placement placement) {
        State state = states.get(placement.id());
        return state == null || state.published == null ? EMPTY : state.published;
    }

    /**
     * Advances every active placement a little. Call once per client tick.
     *
     * @param active the placements worth checking: those the highlight wants,
     *               already filtered by visibility and distance, and the one
     *               the resource list is following
     */
    public void tick(List<Placement> active, Map<String, Schematic> schematics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || active.isEmpty()) {
            states.clear();
            return;
        }

        // drop anything no longer on screen so the map cannot grow without bound
        Iterator<Map.Entry<String, State>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            String id = it.next().getKey();
            boolean stillActive = false;
            for (Placement placement : active) {
                if (placement.id().equals(id)) {
                    stillActive = true;
                    break;
                }
            }
            if (!stillActive) {
                it.remove();
            }
        }

        int budget = SSConfig.INSTANCE.verifyBlocksPerTick.get();
        int share = Math.max(512, budget / active.size());
        for (Placement placement : active) {
            Schematic schematic = schematics.get(placement.schematicKey());
            if (schematic == null) {
                continue;
            }
            State state = states.computeIfAbsent(placement.id(), k -> new State(schematic));
            if (state.stale(placement, schematic)) {
                state.restart(placement, schematic);
            }
            state.advance(mc.level, placement, schematic, share);
        }
    }

    // -----------------------------------------------------------------------

    private static final class State {

        private Schematic schematic;
        private String signature = "";

        private int cursor;
        private List<Mark> workingWrong = new ArrayList<>();
        private List<Mark> workingExtra = new ArrayList<>();
        private int workingMissing;
        private int workingCorrect;
        private int workingWrongCount;
        private int workingExtraCount;
        private boolean workingTruncated;
        private BitSet workingMask;
        private Map<Item, Integer> workingPlaced = new HashMap<>();

        private Diff published;
        private BitSet correctMask;
        private BitSet dirtySections = new BitSet();
        private int maskVersion;

        State(Schematic schematic) {
            this.schematic = schematic;
            this.workingMask = new BitSet(schematic.blockCount());
            this.correctMask = new BitSet(schematic.blockCount());
        }

        boolean stale(Placement placement, Schematic current) {
            return schematic != current || !signature.equals(signatureOf(placement));
        }

        void restart(Placement placement, Schematic current) {
            this.schematic = current;
            this.signature = signatureOf(placement);
            this.cursor = 0;
            this.workingWrong = new ArrayList<>();
            this.workingExtra = new ArrayList<>();
            this.workingMissing = 0;
            this.workingCorrect = 0;
            this.workingWrongCount = 0;
            this.workingExtraCount = 0;
            this.workingTruncated = false;
            this.workingMask = new BitSet(current.blockCount());
            this.workingPlaced = new HashMap<>();
            this.correctMask = new BitSet(current.blockCount());
            this.dirtySections = new BitSet();
            this.published = null;
            this.maskVersion++;
        }

        private static String signatureOf(Placement placement) {
            return placement.schematicKey() + "|" + placement.origin().asLong()
                    + "|" + placement.rotation() + "|" + placement.mirror()
                    + "|" + SSConfig.INSTANCE.strictStateMatch.get();
        }

        void advance(Level level, Placement placement, Schematic schematic, int budget) {
            int w = schematic.width();
            int h = schematic.height();
            int l = schematic.length();
            int total = w * h * l;
            if (total == 0) {
                return;
            }
            int limit = SSConfig.INSTANCE.maxHighlights.get();

            int done = 0;
            while (done < budget && cursor < total) {
                int index = cursor++;
                done++;

                int x = index % w;
                int z = (index / w) % l;
                int y = index / (w * l);

                BlockState local = schematic.getBlockState(x, y, z);
                BlockState wanted = local.mirror(placement.mirror()).rotate(placement.rotation());
                BlockPos world = placement.toWorld(schematic, x, y, z);

                if (world.getY() < level.getMinBuildHeight() || world.getY() >= level.getMaxBuildHeight()) {
                    continue;
                }
                if (!level.isLoaded(world)) {
                    // An unloaded chunk cannot be read, so the block keeps whatever
                    // the last pass found. Walking away from a build would otherwise
                    // put its correctly placed blocks back on the resource list one
                    // chunk at a time, and reporting a phantom mismatch is no better.
                    if (correctMask.get(index)) {
                        markCorrect(index, local);
                    }
                    continue;
                }

                BlockState actual = level.getBlockState(world);
                boolean wantedAir = wanted.isAir();
                boolean actualEmpty = actual.isAir() || actual.canBeReplaced();
                // A block you have accepted as built is taken at its word: it
                // counts as standing, costs nothing more, and is never in the way.
                boolean accepted = placement.isAccepted(index);

                if (wantedAir) {
                    if (!actualEmpty && !accepted) {
                        workingExtraCount++;
                        if (workingExtra.size() < limit) {
                            workingExtra.add(new Mark(world, y));
                        } else {
                            workingTruncated = true;
                        }
                    }
                    continue;
                }

                if (accepted || sameBlock(wanted, actual)) {
                    markCorrect(index, local);
                } else if (actualEmpty) {
                    workingMissing += MaterialResolver.costOf(local).total();
                } else {
                    workingWrongCount++;
                    if (workingWrong.size() < limit) {
                        workingWrong.add(new Mark(world, y));
                    } else {
                        workingTruncated = true;
                    }
                }
            }

            if (cursor >= total) {
                publish(total);
                cursor = 0;
                workingWrong = new ArrayList<>();
                workingExtra = new ArrayList<>();
                workingMissing = 0;
                workingCorrect = 0;
                workingWrongCount = 0;
                workingExtraCount = 0;
                workingTruncated = false;
                workingMask = new BitSet(total);
                workingPlaced = new HashMap<>();
            }
        }

        /**
         * Costed from the unrotated state, which is the one the resource list
         * priced the requirement from, so the two totals are in the same units.
         */
        private void markCorrect(int index, BlockState local) {
            workingCorrect++;
            workingMask.set(index);
            MaterialResolver.costOf(local).addTo(workingPlaced);
        }

        private void publish(int total) {
            // work out which bake sections changed so only those get rebuilt
            BitSet changed = (BitSet) workingMask.clone();
            changed.xor(correctMask);
            if (!changed.isEmpty()) {
                BitSet sections = new BitSet();
                int w = schematic.width();
                int l = schematic.length();
                for (int i = changed.nextSetBit(0); i >= 0; i = changed.nextSetBit(i + 1)) {
                    int x = i % w;
                    int z = (i / w) % l;
                    int y = i / (w * l);
                    sections.set(BakedSchematic.sectionIndexOf(schematic, x, y, z));
                }
                dirtySections.or(sections);
                maskVersion++;
            }
            correctMask = (BitSet) workingMask.clone();

            published = new Diff(List.copyOf(workingWrong), List.copyOf(workingExtra),
                    workingWrongCount, workingExtraCount,
                    workingMissing, workingCorrect, total, workingTruncated,
                    Map.copyOf(workingPlaced));
        }

        private static final Set<String> PLACEMENT_PROPERTIES = Set.of(
                "facing", "axis", "half", "hinge", "part", "rotation", "face", "attachment",
                "layers", "candles", "pickles", "eggs");

        private static boolean sameBlock(BlockState wanted, BlockState actual) {
            if (SSConfig.INSTANCE.strictStateMatch.get()) return wanted.equals(actual);
            if (wanted.getBlock() != actual.getBlock()) return false;
            if (wanted.getBlock() instanceof DoorBlock && flippedDoor(wanted, actual)) return true;
            // A shut trapdoor looks the same whichever edge it hinges on, and the
            // hinge comes from which face you happened to click, so it only has
            // to match when the schematic has the trapdoor open and the hinge shows.
            boolean shutTrapdoor = wanted.getBlock() instanceof TrapDoorBlock && !wanted.getValue(TrapDoorBlock.OPEN);
            for (Property<?> property : wanted.getProperties()) {
                if (shutTrapdoor && property == TrapDoorBlock.FACING) continue;
                if ((PLACEMENT_PROPERTIES.contains(property.getName())
                        || (wanted.getBlock() instanceof SlabBlock && property.getName().equals("type")))
                        && !wanted.getValue(property).equals(actual.getValue(property))) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * The same door hung from the other side.
     *
     * <p>A door takes its facing from where you stood when you placed it, and
     * the game swaps the hinge to keep it on the same post, so a door put in
     * from inside the house comes out facing the other way from one put in
     * from the garden. The two are the same door on the same hinge, opening the
     * same way, with the closed panel flush against the other edge of the
     * block. Calling that wrong meant a red door and a closed ghost drawn over
     * an open one for anyone who happened to be indoors at the time.</p>
     */
    private static boolean flippedDoor(BlockState wanted, BlockState actual) {
        return wanted.getValue(DoorBlock.FACING) == actual.getValue(DoorBlock.FACING).getOpposite()
                && wanted.getValue(DoorBlock.HINGE) != actual.getValue(DoorBlock.HINGE)
                && wanted.getValue(DoorBlock.HALF) == actual.getValue(DoorBlock.HALF);
    }

    /** Whether a world block satisfies what the schematic wants, by the same rule the pass uses. */
    public static boolean matches(BlockState wanted, BlockState actual) {
        return State.sameBlock(wanted, actual);
    }

    /** Totals across every placement currently being checked. */
    public Diff summary() {
        int wrong = 0;
        int extra = 0;
        int missing = 0;
        int correct = 0;
        int total = 0;
        boolean truncated = false;
        for (State state : states.values()) {
            Diff diff = state.published;
            if (diff == null) {
                continue;
            }
            wrong += diff.wrongCount();
            extra += diff.extraCount();
            missing += diff.missing();
            correct += diff.correct();
            total += diff.total();
            truncated |= diff.truncated();
        }
        return new Diff(List.of(), List.of(), wrong, extra, missing, correct, total, truncated, Map.of());
    }

    /**
     * Which blocks of a placement were standing correctly at the last full
     * pass, indexed the way the schematic stores them, or null before the
     * first pass has finished. The build list reads this to say what is left.
     * Read only: the verifier replaces the set rather than editing it.
     */
    public BitSet correctMaskFor(Placement placement) {
        State state = states.get(placement.id());
        return state == null || state.published == null ? null : state.correctMask;
    }

    /** True once at least one placement has finished a pass. */
    public boolean hasResult() {
        for (State state : states.values()) {
            if (state.published != null) {
                return true;
            }
        }
        return false;
    }

    /** Hands the bake the set of positions it can skip, along with what changed. */
    public void applyCorrectMask(Placement placement, BakedSchematic baked) {
        if (!SSConfig.INSTANCE.hideCorrectBlocks.get() || !isEnabled()) {
            baked.clearCorrectMask();
            return;
        }
        State state = states.get(placement.id());
        if (state == null || state.published == null || state.stale(placement, baked.schematic())) {
            baked.clearCorrectMask();
            return;
        }
        baked.setCorrectMask(state.correctMask, state.dirtySections, state.maskVersion);
        state.dirtySections = new BitSet();
    }
}
