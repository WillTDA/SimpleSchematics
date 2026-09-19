package dev.willtda.simpleschematics.printing;

import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.render.SchematicVerifier;
import dev.willtda.simpleschematics.resource.MaterialResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Plans ordinary item uses without changing the world or the player's view. */
public final class PrintPlacement {
    public record Attempt(BlockHitResult hit, float yaw, float pitch, boolean sneak) {
    }

    private static final List<IntegerProperty> COUNTS = List.of(
            BlockStateProperties.CANDLES, BlockStateProperties.PICKLES,
            BlockStateProperties.EGGS, BlockStateProperties.LAYERS);
    private static final double[] HIT_OFFSETS = {0.5, 0.25, 0.75};
    private static final Set<Class<?>> FAILED_ITEMS = new HashSet<>();
    // Resolving the protected hook needs Forge's mapping service, unlike state comparisons.
    private static final class PlacementAccess {
        private static final Method METHOD = ObfuscationReflectionHelper.findMethod(
                BlockItem.class, "m_5965_", BlockPlaceContext.class);
    }

    private PrintPlacement() {
    }

    /**
     * A null result leaves the block queued: it may need a support, a clearer
     * line of sight, or a manual interaction that cannot safely be predicted.
     */
    @Nullable
    public static Attempt find(Minecraft mc, BlockPos pos, BlockState wanted, ItemStack stack) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || stack.isEmpty()
                || !mc.level.isLoaded(pos) || mc.level.isOutsideBuildHeight(pos)
                || !mc.level.getWorldBorder().isWithinBounds(pos)
                || !(stack.getItem() instanceof BlockItem item)) {
            return null;
        }
        BlockState actual = mc.level.getBlockState(pos);
        if (matches(wanted, actual) || isSecondaryPart(wanted)
                || (!actual.canBeReplaced() && !isPartial(wanted, actual))) {
            return null;
        }
        // An item carrying inventory NBT must never unpack its contents in Survival.
        if (!mc.player.isCreative() && stack.getTagElement(BlockItem.BLOCK_ENTITY_TAG) != null) {
            return null;
        }
        // A tagged state can overwrite the state predicted by the item's placement rules.
        if (stack.getTagElement(BlockItem.BLOCK_STATE_TAG) != null) {
            return null;
        }
        if (wanted.getBlock() instanceof FallingBlock
                && FallingBlock.isFree(mc.level.getBlockState(pos.below()))) {
            return null;
        }

        List<BlockHitResult> hits = new ArrayList<>();
        if (!actual.isAir() && (isPartial(wanted, actual) || actual.canBeReplaced())) {
            addVisibleHits(mc, pos, hits);
        }
        for (Direction side : Direction.values()) {
            BlockPos support = pos.relative(side);
            if (!mc.level.isLoaded(support)) continue;
            addVisibleHits(mc, support, side.getOpposite(), hits);
        }
        if (hits.isEmpty()) return null;

        // Filling a pot or adding a cake's candle is a block interaction, not a placement.
        if (isCompositeUse(wanted, actual, stack)) {
            for (BlockHitResult hit : hits) {
                if (hit.getBlockPos().equals(pos)) {
                    return new Attempt(hit, mc.player.getYRot(), mc.player.getXRot(), false);
                }
            }
            return null;
        }

        List<Float> yaws = rotations(wanted, mc.player.getYRot());
        float[] pitches = {mc.player.getXRot(), 0.0F, 75.0F, -75.0F};
        for (BlockHitResult hit : hits) {
            // Sneaking prevents opening containers or toggling redstone supports.
            // Countable blocks require normal use to add to the existing stack.
            boolean sneak = !(hit.getBlockPos().equals(pos) && actual.getBlock() == wanted.getBlock()
                    && hasCount(wanted));
            for (float yaw : yaws) {
                for (float pitch : pitches) {
                    VirtualContext context = new VirtualContext(mc, stack, hit, yaw, pitch, sneak);
                    if (!context.getClickedPos().equals(pos) || !context.canPlace()) continue;
                    try {
                        BlockPlaceContext updated = item.updatePlacementContext(context);
                        if (updated == null || !updated.getClickedPos().equals(pos)) continue;
                        BlockState predicted = (BlockState) PlacementAccess.METHOD.invoke(item, updated);
                        if (predicted == null || predicted.equals(actual)
                                || !(matches(wanted, predicted) || isPartial(wanted, predicted))
                                || !predicted.canSurvive(mc.level, pos)
                                || !safePartner(mc, pos, predicted, context)
                                || harmsCactus(mc, pos, predicted)) {
                            continue;
                        }
                        return new Attempt(hit, yaw, pitch, sneak);
                    } catch (ReflectiveOperationException | RuntimeException exception) {
                        if (FAILED_ITEMS.add(item.getClass())) {
                            SimpleSchematics.LOG.warn("Cannot predict print placement for {}", item, exception);
                        }
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /** Water and chest pairing are physical requirements even in relaxed verification. */
    public static boolean matches(BlockState wanted, BlockState actual) {
        if (!SchematicVerifier.matches(wanted, actual)) return false;
        if (wanted.hasProperty(BlockStateProperties.WATERLOGGED)
                && !wanted.getValue(BlockStateProperties.WATERLOGGED)
                .equals(actual.getValue(BlockStateProperties.WATERLOGGED))) return false;
        return !(wanted.getBlock() instanceof ChestBlock)
                || wanted.getValue(ChestBlock.TYPE) == actual.getValue(ChestBlock.TYPE);
    }

    /** Whether the existing block can be completed without mining it. */
    public static boolean isPartial(BlockState wanted, BlockState actual) {
        if (wanted.getBlock() instanceof FlowerPotBlock pot && pot.getContent() != Blocks.AIR) {
            return actual.is(Blocks.FLOWER_POT);
        }
        if (wanted.getBlock() instanceof CandleCakeBlock) {
            return actual.is(Blocks.CAKE) && actual.getValue(CakeBlock.BITES) == 0;
        }
        if (wanted.getBlock() != actual.getBlock()) return false;
        if (wanted.getBlock() instanceof SlabBlock
                && wanted.getValue(SlabBlock.TYPE) == SlabType.DOUBLE
                && actual.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) {
            return matches(wanted.setValue(SlabBlock.TYPE, actual.getValue(SlabBlock.TYPE))
                    .setValue(SlabBlock.WATERLOGGED, actual.getValue(SlabBlock.WATERLOGGED)), actual);
        }
        if (wanted.getBlock() instanceof ChestBlock
                && wanted.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                && actual.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return matches(wanted.setValue(ChestBlock.TYPE, ChestType.SINGLE), actual);
        }
        for (IntegerProperty count : COUNTS) {
            if (wanted.hasProperty(count) && actual.getValue(count) < wanted.getValue(count)) {
                return matches(wanted.setValue(count, actual.getValue(count)), actual);
            }
        }
        return false;
    }

    public static boolean isSecondaryPart(BlockState state) {
        return (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
                || (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD);
    }

    private static boolean hasCount(BlockState state) {
        for (IntegerProperty count : COUNTS) {
            if (state.hasProperty(count)) return true;
        }
        return false;
    }

    private static boolean isCompositeUse(BlockState wanted, BlockState actual, ItemStack stack) {
        if (wanted.getBlock() instanceof FlowerPotBlock pot && actual.is(Blocks.FLOWER_POT)) {
            return stack.is(pot.getContent().asItem());
        }
        if (wanted.getBlock() instanceof CandleCakeBlock && actual.is(Blocks.CAKE)
                && actual.getValue(CakeBlock.BITES) == 0 && stack.getItem() instanceof BlockItem item) {
            return item.getBlock() instanceof CandleBlock
                    && MaterialResolver.costOf(wanted).entries().stream().anyMatch(entry -> stack.is(entry.item()));
        }
        return false;
    }

    private static boolean safePartner(Minecraft mc, BlockPos pos, BlockState predicted, BlockPlaceContext context) {
        BlockPos partner;
        BlockState partnerState;
        if (predicted.getBlock() instanceof BedBlock) {
            partner = pos.relative(predicted.getValue(BedBlock.FACING));
            partnerState = predicted.setValue(BedBlock.PART, BedPart.HEAD);
        } else if (predicted.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            partner = pos.above();
            partnerState = predicted.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        } else {
            return true;
        }
        return !mc.level.isOutsideBuildHeight(partner) && mc.level.isLoaded(partner)
                && mc.level.getWorldBorder().isWithinBounds(partner)
                && mc.level.getBlockState(partner).canBeReplaced(context)
                && mc.level.isUnobstructed(partnerState, partner, CollisionContext.of(mc.player));
    }

    private static boolean harmsCactus(Minecraft mc, BlockPos pos, BlockState predicted) {
        if (!predicted.isSolid() && !predicted.getFluidState().is(FluidTags.LAVA)) return false;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (mc.level.getBlockState(pos.relative(side)).getBlock() instanceof CactusBlock) return true;
        }
        return false;
    }

    private static List<Float> rotations(BlockState wanted, float current) {
        List<Float> result = new ArrayList<>();
        if (wanted.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = wanted.getValue(BlockStateProperties.HORIZONTAL_FACING);
            result.add(facing.toYRot());
            result.add(facing.getOpposite().toYRot());
        }
        if (wanted.hasProperty(BlockStateProperties.ROTATION_16)) {
            float angle = wanted.getValue(BlockStateProperties.ROTATION_16) * 22.5F;
            result.add(angle);
            result.add(angle + 180.0F);
        }
        result.add(current);
        for (float yaw : new float[]{0.0F, 90.0F, 180.0F, 270.0F}) {
            if (!result.contains(yaw)) result.add(yaw);
        }
        return result;
    }

    private static void addVisibleHits(Minecraft mc, BlockPos pos, List<BlockHitResult> hits) {
        for (Direction face : Direction.values()) addVisibleHits(mc, pos, face, hits);
    }

    private static void addVisibleHits(Minecraft mc, BlockPos pos, Direction face, List<BlockHitResult> hits) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return;
        VoxelShape shape = state.getShape(mc.level, pos, CollisionContext.of(mc.player));
        if (shape.isEmpty()) return;
        AABB bounds = shape.bounds();
        Vec3 eye = mc.player.getEyePosition();
        double reach = mc.player.getBlockReach();
        for (double first : HIT_OFFSETS) {
            for (double second : HIT_OFFSETS) {
                double x = face.getAxis() == Direction.Axis.X
                        ? (face == Direction.EAST ? bounds.maxX : bounds.minX)
                        : Mth.lerp(first, bounds.minX, bounds.maxX);
                double y = face.getAxis() == Direction.Axis.Y
                        ? (face == Direction.UP ? bounds.maxY : bounds.minY)
                        : Mth.lerp(face.getAxis() == Direction.Axis.X ? first : second, bounds.minY, bounds.maxY);
                double z = face.getAxis() == Direction.Axis.Z
                        ? (face == Direction.SOUTH ? bounds.maxZ : bounds.minZ)
                        : Mth.lerp(second, bounds.minZ, bounds.maxZ);
                Vec3 point = new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                if (eye.distanceToSqr(point) > reach * reach) continue;
                Vec3 end = point.subtract(Vec3.atLowerCornerOf(face.getNormal()).scale(0.001));
                BlockHitResult visible = mc.level.clip(new ClipContext(eye, end,
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                if (visible.getType() == HitResult.Type.BLOCK && visible.getBlockPos().equals(pos)
                        && visible.getDirection() == face && !visible.isInside()) {
                    hits.add(visible);
                }
            }
        }
    }

    private static final class VirtualContext extends BlockPlaceContext {
        private final float yaw;
        private final float pitch;
        private final boolean sneak;

        VirtualContext(Minecraft mc, ItemStack stack, BlockHitResult hit, float yaw, float pitch, boolean sneak) {
            super(mc.level, mc.player, InteractionHand.MAIN_HAND, stack, hit);
            this.yaw = yaw;
            this.pitch = pitch;
            this.sneak = sneak;
            // The superclass asks virtual methods before these fields are assigned.
            this.replaceClicked = true;
            this.replaceClicked = mc.level.getBlockState(hit.getBlockPos()).canBeReplaced(this);
        }

        @Override
        public Direction getHorizontalDirection() {
            return Direction.fromYRot(yaw);
        }

        @Override
        public float getRotation() {
            return yaw;
        }

        @Override
        public boolean isSecondaryUseActive() {
            return sneak;
        }

        @Override
        public Direction getNearestLookingDirection() {
            return orderedDirections()[0];
        }

        @Override
        public Direction getNearestLookingVerticalDirection() {
            return pitch < 0.0F ? Direction.UP : Direction.DOWN;
        }

        @Override
        public Direction[] getNearestLookingDirections() {
            Direction[] directions = orderedDirections();
            if (!this.replacingClickedOnBlock()) {
                Direction first = getClickedFace().getOpposite();
                int index = 0;
                while (directions[index] != first) index++;
                System.arraycopy(directions, 0, directions, 1, index);
                directions[0] = first;
            }
            return directions;
        }

        private Direction[] orderedDirections() {
            // Keep vanilla's ordering and tie breaks without rotating the real player.
            float sinPitch = Mth.sin(pitch * Mth.DEG_TO_RAD);
            float cosPitch = Mth.cos(pitch * Mth.DEG_TO_RAD);
            float sinYaw = Mth.sin(-yaw * Mth.DEG_TO_RAD);
            float cosYaw = Mth.cos(-yaw * Mth.DEG_TO_RAD);
            Direction x = sinYaw > 0 ? Direction.EAST : Direction.WEST;
            Direction y = sinPitch < 0 ? Direction.UP : Direction.DOWN;
            Direction z = cosYaw > 0 ? Direction.SOUTH : Direction.NORTH;
            float ax = Math.abs(sinYaw);
            float ay = Math.abs(sinPitch);
            float az = Math.abs(cosYaw);
            if (ax > az) {
                if (ay > ax * cosPitch) return ordered(y, x, z);
                return az * cosPitch > ay ? ordered(x, z, y) : ordered(x, y, z);
            }
            if (ay > az * cosPitch) return ordered(y, z, x);
            return ax * cosPitch > ay ? ordered(z, x, y) : ordered(z, y, x);
        }

        private static Direction[] ordered(Direction first, Direction second, Direction third) {
            return new Direction[]{first, second, third,
                    third.getOpposite(), second.getOpposite(), first.getOpposite()};
        }
    }
}
