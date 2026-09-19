package dev.willtda.simpleschematics.printing;

import com.electronwill.nightconfig.core.CommentedConfig;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.resource.MaterialResolver;

import net.minecraft.core.Direction;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Run with scripts/check_print_placement.ps1 after compileJava. Uses real Minecraft states. */
public final class PrintPlacementTest {
    private static int checks;

    public static void main(String[] args) {
        PrintTestBootstrap.initialise();
        CommentedConfig config = CommentedConfig.inMemory();
        SSConfig.SPEC.correct(config);
        SSConfig.SPEC.setConfig(config);
        SSConfig.INSTANCE.strictStateMatch.set(false);

        BlockState slab = Blocks.OAK_SLAB.defaultBlockState();
        BlockState doubleSlab = slab.setValue(SlabBlock.TYPE, SlabType.DOUBLE);
        check(cost(doubleSlab, Items.OAK_SLAB) == 2, "A double slab needs two items");
        for (SlabType half : new SlabType[]{SlabType.BOTTOM, SlabType.TOP}) {
            for (boolean water : new boolean[]{false, true}) {
                BlockState existing = slab.setValue(SlabBlock.TYPE, half).setValue(SlabBlock.WATERLOGGED, water);
                check(PrintPlacement.isPartial(doubleSlab, existing), "Either wet or dry half completes a double slab");
                check(standing(doubleSlab, existing, Items.OAK_SLAB) == 1, "Credit the existing slab once");
                check(remaining(doubleSlab, existing, Items.OAK_SLAB) == 1, "Spend only one more slab");
            }
        }
        check(!PrintPlacement.isPartial(doubleSlab, doubleSlab), "Completed slabs are not partial");
        check(!PrintPlacement.isPartial(slab, slab.setValue(SlabBlock.TYPE, SlabType.TOP)), "A wrong half needs replacement");
        check(!PrintPlacement.matches(slab, slab.setValue(SlabBlock.WATERLOGGED, true)), "Relaxed matching still respects water");
        check(!PrintPlacement.isPartial(doubleSlab, Blocks.STONE_SLAB.defaultBlockState()), "Different slab types cannot combine");
        check(remaining(doubleSlab, Blocks.AIR.defaultBlockState(), Items.OAK_SLAB) == 2, "An empty position still needs both slabs");

        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST);
        for (ChestType type : new ChestType[]{ChestType.LEFT, ChestType.RIGHT}) {
            BlockState wanted = chest.setValue(ChestBlock.TYPE, type);
            check(PrintPlacement.isPartial(wanted, chest), "The first half of a double chest can be single");
            check(!PrintPlacement.matches(wanted, chest), "A lone chest is not a finished double chest");
            check(standing(wanted, chest, Items.CHEST) == 1, "Credit the already placed chest");
            check(remaining(wanted, chest, Items.CHEST) == 0, "Joining chests must not spend a third item");
            BlockState backwards = chest.setValue(ChestBlock.FACING, Direction.WEST);
            check(!PrintPlacement.isPartial(wanted, backwards), "A backwards chest needs replacement");
            check(standing(wanted, backwards, Items.CHEST) == 0, "Do not credit a backwards chest");
            check(!PrintPlacement.isPartial(wanted, chest.setValue(ChestBlock.WATERLOGGED, true)), "Chest water must match");
            check(!PrintPlacement.isPartial(wanted, Blocks.TRAPPED_CHEST.defaultBlockState()), "Chest kinds must match");
        }

        for (int want = 1; want <= 4; want++) {
            BlockState wanted = Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, want);
            for (int have = 1; have <= 4; have++) {
                BlockState actual = wanted.setValue(BlockStateProperties.CANDLES, have);
                check(PrintPlacement.isPartial(wanted, actual) == (have < want), "Candle stacks only grow toward the target");
                check(PrintPlacement.matches(wanted, actual) == (have == want), "Candle count is structural");
                if (have < want) check(remaining(wanted, actual, Items.CANDLE) == want - have, "Charge only missing candles");
            }
        }
        for (int count = 1; count < 8; count++) {
            BlockState wanted = Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 8);
            BlockState actual = wanted.setValue(BlockStateProperties.LAYERS, count);
            check(remaining(wanted, actual, Items.SNOW) == 8 - count, "Snow layers consume one item each");
        }
        BlockState pickles = Blocks.SEA_PICKLE.defaultBlockState().setValue(BlockStateProperties.PICKLES, 4);
        check(remaining(pickles, pickles.setValue(BlockStateProperties.PICKLES, 1), Items.SEA_PICKLE) == 3,
                "Sea pickle clusters consume the remaining count");
        BlockState eggs = Blocks.TURTLE_EGG.defaultBlockState().setValue(BlockStateProperties.EGGS, 3);
        check(remaining(eggs, eggs.setValue(BlockStateProperties.EGGS, 1), Items.TURTLE_EGG) == 2,
                "Turtle eggs consume the remaining count");

        BlockState upper = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        BlockState head = Blocks.RED_BED.defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.HEAD);
        check(MaterialResolver.costOf(upper).isNothing() && PrintPlacement.isSecondaryPart(upper), "A door top is created by its bottom");
        check(MaterialResolver.costOf(head).isNothing() && PrintPlacement.isSecondaryPart(head), "A bed head is created by its foot");
        check(cost(Blocks.OAK_DOOR.defaultBlockState(), Items.OAK_DOOR) == 1, "One door item places both halves");
        check(cost(Blocks.RED_BED.defaultBlockState(), Items.RED_BED) == 1, "One bed item places both halves");
        check(remaining(Blocks.POTTED_POPPY.defaultBlockState(), Blocks.FLOWER_POT.defaultBlockState(), Items.FLOWER_POT) == 0,
                "An existing flower pot is not charged again");
        check(remaining(Blocks.POTTED_POPPY.defaultBlockState(), Blocks.FLOWER_POT.defaultBlockState(), Items.POPPY) == 1,
                "A pot still needs its plant");
        check(remaining(Blocks.RED_CANDLE_CAKE.defaultBlockState(), Blocks.CAKE.defaultBlockState(), Items.CAKE) == 0,
                "A cake is not charged again when adding a candle");
        check(remaining(Blocks.RED_CANDLE_CAKE.defaultBlockState(), Blocks.CAKE.defaultBlockState(), Items.RED_CANDLE) == 1,
                "A candle cake still needs the correct candle");

        SSConfig.INSTANCE.strictStateMatch.set(true);
        check(!PrintPlacement.matches(Blocks.CANDLE.defaultBlockState(), Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.LIT, true)),
                "Strict matching still checks transient properties");
        System.out.println("Print placement checks passed: " + checks);
    }

    private static int cost(BlockState wanted, Item item) {
        return amount(MaterialResolver.costOf(wanted), item);
    }

    private static int standing(BlockState wanted, BlockState actual, Item item) {
        return amount(MaterialResolver.standing(wanted, actual), item);
    }

    private static int remaining(BlockState wanted, BlockState actual, Item item) {
        return amount(PrintManager.remainingCost(wanted, actual), item);
    }

    private static int amount(MaterialResolver.Cost cost, Item item) {
        return cost.entries().stream().filter(entry -> entry.item() == item).mapToInt(MaterialResolver.Cost.Entry::amount).sum();
    }

    private static void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }
}
