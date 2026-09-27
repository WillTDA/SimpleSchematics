package dev.willtda.simpleschematics.printing;

import dev.willtda.simpleschematics.resource.MaterialResolver;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * The first look at the world before printing: what is done, what is left,
 * and what is in the way.
 *
 * <p>It only counts. Nothing it finds stops a print on its own any more: a
 * block in the way, a chunk that is not loaded or a corner past the world
 * border is left out and reported, and everything else goes ahead. Refusing
 * the whole build over one of those meant a large build could not be printed
 * at all from anywhere you could stand.</p>
 *
 * <p>It works to a time budget each tick rather than a fixed count, over the
 * plan's blocks rather than the whole volume.</p>
 */
final class PrintSurvey {

    private static final long BUDGET_NANOS = 4_000_000L;
    private static final int PREDICTION_WAIT_TICKS = 100;

    private final PrintPlan plan;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final Map<Item, Integer> required = new HashMap<>();
    private int cursor;
    private int waited;

    int correct;
    int missing;
    int obstructed;
    int unloaded;
    int outside;
    int banks;

    PrintSurvey(PrintPlan plan) {
        this.plan = plan;
    }

    /** @return true once every block has been looked at */
    boolean advance(ClientLevel level) {
        // A block the player has just placed or broken still shows the guess
        // until the server answers. Waiting a moment keeps it from being counted
        // as whatever the guess was.
        if (cursor == 0 && waited < PREDICTION_WAIT_TICKS && PrintAcknowledgement.any(level)) {
            waited++;
            return false;
        }
        long deadline = System.nanoTime() + BUDGET_NANOS;
        while (cursor < plan.size()) {
            if ((cursor & 255) == 0 && System.nanoTime() > deadline) {
                return false;
            }
            int index = plan.target(cursor++);
            if (plan.accepted(index)) {
                continue;
            }
            BlockState wanted = plan.wanted(index);
            plan.world(index, pos);
            if (!PrintPlan.inWorld(level, pos)) {
                outside++;
                continue;
            }
            if (!level.hasChunkAt(pos)) {
                unloaded++;
                MaterialResolver.costOf(wanted).addTo(required);
                continue;
            }
            BlockState actual = level.getBlockState(pos);
            if (PrintPlacement.matches(wanted, actual)) {
                correct++;
                continue;
            }
            if (plan.isBank(level, pos)) {
                banks++;
                continue;
            }
            if (PrintManager.isObstruction(wanted, actual)) {
                // Still wanted in the end, so its materials count towards the total.
                obstructed++;
                MaterialResolver.costOf(wanted).addTo(required);
                continue;
            }
            missing++;
            PrintManager.remainingCost(wanted, actual).addTo(required);
        }
        return true;
    }

    /** Positions a print can do something about, given whether it may replace blocks. */
    int work(boolean replace) {
        return missing + unloaded + (replace ? obstructed : 0);
    }

    /** What the blocks still to place are worth, loaded or not. */
    Map<Item, Integer> required() {
        return required;
    }
}
