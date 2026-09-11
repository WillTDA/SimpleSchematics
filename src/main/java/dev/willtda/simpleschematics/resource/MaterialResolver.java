package dev.willtda.simpleschematics.resource;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.HashMap;
import java.util.Map;

/**
 * Works out what you actually need to buy or mine for a given block state.
 *
 * <p>The fiddly cases are the ones that would otherwise make a list wrong:
 * doors and beds occupy two blocks but cost one item, a double slab costs two,
 * candles and sea pickles cost one each, and a wall torch is still just a
 * torch.</p>
 */
public final class MaterialResolver {

    /** What one block state costs. A null item means it needs nothing at all. */
    public record Cost(Item item, int amount) {
        public static final Cost NOTHING = new Cost(null, 0);

        public boolean isNothing() {
            return item == null || amount <= 0;
        }
    }

    private static final Map<Block, Item> OVERRIDES = new HashMap<>();
    private static final Map<BlockState, Cost> CACHE = new HashMap<>();

    static {
        OVERRIDES.put(Blocks.WATER, Items.WATER_BUCKET);
        OVERRIDES.put(Blocks.LAVA, Items.LAVA_BUCKET);
        OVERRIDES.put(Blocks.WALL_TORCH, Items.TORCH);
        OVERRIDES.put(Blocks.SOUL_WALL_TORCH, Items.SOUL_TORCH);
        OVERRIDES.put(Blocks.REDSTONE_WALL_TORCH, Items.REDSTONE_TORCH);
        OVERRIDES.put(Blocks.REDSTONE_WIRE, Items.REDSTONE);
        OVERRIDES.put(Blocks.TRIPWIRE, Items.STRING);
        OVERRIDES.put(Blocks.FARMLAND, Items.DIRT);
        OVERRIDES.put(Blocks.DIRT_PATH, Items.DIRT);
        OVERRIDES.put(Blocks.BAMBOO_SAPLING, Items.BAMBOO);
        OVERRIDES.put(Blocks.CAVE_VINES, Items.GLOW_BERRIES);
        OVERRIDES.put(Blocks.CAVE_VINES_PLANT, Items.GLOW_BERRIES);
        OVERRIDES.put(Blocks.TWISTING_VINES_PLANT, Items.TWISTING_VINES);
        OVERRIDES.put(Blocks.WEEPING_VINES_PLANT, Items.WEEPING_VINES);
        OVERRIDES.put(Blocks.KELP_PLANT, Items.KELP);
        OVERRIDES.put(Blocks.BIG_DRIPLEAF_STEM, Items.BIG_DRIPLEAF);
        OVERRIDES.put(Blocks.SWEET_BERRY_BUSH, Items.SWEET_BERRIES);
        OVERRIDES.put(Blocks.ATTACHED_MELON_STEM, Items.MELON_SEEDS);
        OVERRIDES.put(Blocks.ATTACHED_PUMPKIN_STEM, Items.PUMPKIN_SEEDS);
        OVERRIDES.put(Blocks.MELON_STEM, Items.MELON_SEEDS);
        OVERRIDES.put(Blocks.PUMPKIN_STEM, Items.PUMPKIN_SEEDS);
        OVERRIDES.put(Blocks.POTATOES, Items.POTATO);
        OVERRIDES.put(Blocks.CARROTS, Items.CARROT);
        OVERRIDES.put(Blocks.BEETROOTS, Items.BEETROOT_SEEDS);
        OVERRIDES.put(Blocks.WHEAT, Items.WHEAT_SEEDS);
        OVERRIDES.put(Blocks.COCOA, Items.COCOA_BEANS);
        OVERRIDES.put(Blocks.NETHER_PORTAL, Items.AIR);
        OVERRIDES.put(Blocks.END_PORTAL, Items.AIR);
        OVERRIDES.put(Blocks.FIRE, Items.AIR);
        OVERRIDES.put(Blocks.SOUL_FIRE, Items.AIR);
        OVERRIDES.put(Blocks.PISTON_HEAD, Items.AIR);
        OVERRIDES.put(Blocks.MOVING_PISTON, Items.AIR);
        OVERRIDES.put(Blocks.BUBBLE_COLUMN, Items.AIR);
    }

    private MaterialResolver() {
    }

    public static Cost costOf(BlockState state) {
        Cost cached = CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        Cost cost = compute(state);
        CACHE.put(state, cost);
        return cost;
    }

    private static Cost compute(BlockState state) {
        if (state.isAir()) {
            return Cost.NOTHING;
        }
        Block block = state.getBlock();

        // the second half of a two block tall thing is free
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return Cost.NOTHING;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            return Cost.NOTHING;
        }

        Item item = resolveItem(block);
        if (item == null || item == Items.AIR) {
            return Cost.NOTHING;
        }

        int amount = 1;
        if (block instanceof SlabBlock && state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            amount = 2;
        } else if (state.hasProperty(BlockStateProperties.CANDLES)) {
            amount = state.getValue(BlockStateProperties.CANDLES);
        } else if (state.hasProperty(BlockStateProperties.PICKLES)) {
            amount = state.getValue(BlockStateProperties.PICKLES);
        } else if (state.hasProperty(BlockStateProperties.EGGS)) {
            amount = state.getValue(BlockStateProperties.EGGS);
        } else if (state.hasProperty(BlockStateProperties.LAYERS)) {
            amount = state.getValue(BlockStateProperties.LAYERS);
        }

        return new Cost(item, amount);
    }

    private static Item resolveItem(Block block) {
        Item override = OVERRIDES.get(block);
        if (override != null) {
            return override == Items.AIR ? null : override;
        }

        Item item = block.asItem();
        if (item != Items.AIR) {
            return item;
        }

        // last resort, a few blocks share an id with their item but are not linked
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) {
            return null;
        }
        Item direct = BuiltInRegistries.ITEM.get(id);
        if (direct != Items.AIR) {
            return direct;
        }
        String path = id.getPath();
        String stripped = path.startsWith("wall_") ? path.substring(5) : path.replace("_wall_", "_");
        if (!stripped.equals(path)) {
            Item guess = BuiltInRegistries.ITEM.get(new ResourceLocation(id.getNamespace(), stripped));
            if (guess != Items.AIR) {
                return guess;
            }
        }
        return null;
    }

    /**
     * Formats a total as stacks plus a remainder: 68 as 64 + 4, and 3,214 as
     * 50 × 64 + 14.
     *
     * <p>A single stack is written as the stack size on its own rather than
     * 1 × 64, which is how anyone would say it. Exactly one stack with nothing
     * over is left blank, because the breakdown would only repeat the total
     * sitting next to it. The multiplication sign rather than the word keeps
     * the figures narrow enough that the item name still has room.</p>
     */
    public static String stackBreakdown(int total, int stackSize) {
        if (stackSize <= 1 || total < stackSize) {
            return "";
        }
        int stacks = total / stackSize;
        int remainder = total % stackSize;
        if (stacks == 1 && remainder == 0) {
            return "";
        }
        String head = stacks == 1
                ? String.valueOf(stackSize)
                : String.format("%,d", stacks) + " × " + stackSize;
        return remainder == 0 ? head : head + " + " + remainder;
    }
}
