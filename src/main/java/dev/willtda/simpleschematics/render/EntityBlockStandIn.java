package dev.willtda.simpleschematics.render;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.datafixers.util.Pair;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ShulkerModel;
import net.minecraft.client.model.SkullModelBase;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SpriteCoordinateExpander;
import net.minecraft.client.renderer.blockentity.ConduitRenderer;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ConduitBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotPatterns;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

import java.util.Calendar;
import java.util.List;
import java.util.function.Function;

/**
 * Ghost geometry for blocks that have no baked model.
 *
 * <p>Beds, chests, signs, banners, shulker boxes and heads are drawn by a block
 * entity renderer in the real world. Their block model holds nothing but a
 * particle texture, so the bake produced no quads and the hologram had a hole
 * where the bed should be, while the resource list quite rightly still asked
 * for it. Running the real renderers is not an option: they draw solid model
 * parts through their own render types, outside the hologram shader.</p>
 *
 * <p>The vanilla ones get the real thing all the same. Their model parts and
 * textures are ordinary client resources, so the parts are baked straight into
 * a mesh of their own that is drawn with their texture bound instead of the
 * block atlas, with the transform lifted from each renderer so the ghost
 * stands exactly where the block will. Chests are shut, banners hang still,
 * signs carry no text.</p>
 *
 * <p>Anything else, which in practice means a mod's block entity, falls back
 * to each box of the block's shape wearing its particle texture. Not the real
 * thing, but the right size in the right place, which is what you need to
 * build against.</p>
 */
final class EntityBlockStandIn {

    private EntityBlockStandIn() {
    }

    /**
     * @param sheets hands back the builder for a texture, made on first use,
     *               so a section only carries meshes for textures it needs
     */
    static void bake(SchematicLevel level, BlockState state, BlockPos pos, PoseStack pose,
                     VertexConsumer builder, TextureAtlasSprite sprite,
                     Function<ResourceLocation, VertexConsumer> sheets) {
        Block block = state.getBlock();
        Sink sink = new Sink(level, sheets);
        CompoundTag tag = level.blockEntityTag(pos);
        if (block instanceof ChestBlock || block instanceof EnderChestBlock) {
            chest(state, pose, sink);
        } else if (block instanceof BedBlock bed) {
            bed(state, bed, pose, sink);
        } else if (block instanceof SignBlock sign) {
            sign(state, sign, pose, sink);
        } else if (block instanceof AbstractBannerBlock banner) {
            banner(state, banner, tag, pose, sink);
        } else if (block instanceof ShulkerBoxBlock shulker) {
            shulkerBox(state, shulker, pose, sink);
        } else if (block instanceof AbstractSkullBlock skull) {
            skull(state, skull, tag, pose, sink);
        } else if (block instanceof DecoratedPotBlock) {
            decoratedPot(state, tag, pose, sink);
        } else if (block instanceof ConduitBlock) {
            conduit(pose, sink);
        } else {
            boxes(level, state, pos, pose, builder, sprite);
        }
    }

    /** Where the model parts write to: one builder per texture, shaded, with sprite UVs mapped. */
    private record Sink(SchematicLevel level, Function<ResourceLocation, VertexConsumer> sheets) {
        /** A sprite on an atlas, the way the block entity renderers draw almost everything. */
        VertexConsumer of(Material material) {
            return new SpriteCoordinateExpander(plain(material.atlasLocation()), material.sprite());
        }

        /** A whole texture on its own, which is how heads are drawn. */
        VertexConsumer plain(ResourceLocation texture) {
            return new Shaded(sheets.apply(texture), level);
        }
    }

    private static ModelPart layer(ModelLayerLocation location) {
        return Minecraft.getInstance().getEntityModels().bakeLayer(location);
    }

    // ---- chests -----------------------------------------------------------

    /** The vanilla chest, the way its renderer would stand it, with the lid down. */
    private static void chest(BlockState state, PoseStack pose, Sink sink) {
        ChestType type = state.hasProperty(ChestBlock.TYPE) ? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
        VertexConsumer consumer = sink.of(chestMaterial(state.getBlock(), type));
        ModelPart root = layer(switch (type) {
            case LEFT -> ModelLayers.DOUBLE_CHEST_LEFT;
            case RIGHT -> ModelLayers.DOUBLE_CHEST_RIGHT;
            default -> ModelLayers.CHEST;
        });

        pose.pushPose();
        try {
            float yaw = state.getValue(ChestBlock.FACING).toYRot();
            pose.translate(0.5F, 0.5F, 0.5F);
            pose.mulPose(Axis.YP.rotationDegrees(-yaw));
            pose.translate(-0.5F, -0.5F, -0.5F);
            for (String part : new String[] {"lid", "lock", "bottom"}) {
                ModelPart piece = root.getChild(part);
                piece.xRot = 0.0F;
                piece.render(pose, consumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            }
        } finally {
            pose.popPose();
        }
    }

    private static Material chestMaterial(Block block, ChestType type) {
        if (block instanceof EnderChestBlock) {
            return Sheets.ENDER_CHEST_LOCATION;
        }
        if (block instanceof TrappedChestBlock) {
            return pick(type, Sheets.CHEST_TRAP_LOCATION,
                    Sheets.CHEST_TRAP_LOCATION_LEFT, Sheets.CHEST_TRAP_LOCATION_RIGHT);
        }
        // the same three days the real renderer wraps them up for
        Calendar calendar = Calendar.getInstance();
        boolean christmas = calendar.get(Calendar.MONTH) + 1 == 12
                && calendar.get(Calendar.DAY_OF_MONTH) >= 24 && calendar.get(Calendar.DAY_OF_MONTH) <= 26;
        return christmas
                ? pick(type, Sheets.CHEST_XMAS_LOCATION,
                        Sheets.CHEST_XMAS_LOCATION_LEFT, Sheets.CHEST_XMAS_LOCATION_RIGHT)
                : pick(type, Sheets.CHEST_LOCATION,
                        Sheets.CHEST_LOCATION_LEFT, Sheets.CHEST_LOCATION_RIGHT);
    }

    /** What {@code Sheets.chooseMaterial} does, which is private. */
    private static Material pick(ChestType type, Material single, Material left, Material right) {
        return switch (type) {
            case LEFT -> left;
            case RIGHT -> right;
            default -> single;
        };
    }

    // ---- beds -------------------------------------------------------------

    /** One half of a bed, transformed exactly as the bed renderer does it. */
    private static void bed(BlockState state, BedBlock bed, PoseStack pose, Sink sink) {
        boolean head = state.getValue(BedBlock.PART) == BedPart.HEAD;
        ModelPart root = layer(head ? ModelLayers.BED_HEAD : ModelLayers.BED_FOOT);
        VertexConsumer consumer = sink.of(Sheets.BED_TEXTURES[bed.getColor().getId()]);

        pose.pushPose();
        try {
            pose.translate(0.0F, 0.5625F, 0.0F);
            pose.mulPose(Axis.XP.rotationDegrees(90.0F));
            pose.translate(0.5F, 0.5F, 0.5F);
            pose.mulPose(Axis.ZP.rotationDegrees(180.0F + state.getValue(BedBlock.FACING).toYRot()));
            pose.translate(-0.5F, -0.5F, -0.5F);
            root.render(pose, consumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        } finally {
            pose.popPose();
        }
    }

    // ---- signs ------------------------------------------------------------

    /**
     * Standing, wall, ceiling hanging and wall hanging signs, board and post
     * or chains as the block calls for. The text is not drawn: it is a font
     * render rather than a model, and a ghost is there to show where the sign
     * goes rather than what it says.
     */
    private static void sign(BlockState state, SignBlock sign, PoseStack pose, Sink sink) {
        WoodType wood = SignBlock.getWoodType(sign);
        boolean hanging = sign instanceof CeilingHangingSignBlock || sign instanceof WallHangingSignBlock;
        float yaw = -sign.getYRotationDegrees(state);

        pose.pushPose();
        try {
            if (hanging) {
                ModelPart root = layer(ModelLayers.createHangingSignModelName(wood));
                boolean wall = sign instanceof WallHangingSignBlock;
                root.getChild("plank").visible = wall;
                root.getChild("vChains").visible = false;
                root.getChild("normalChains").visible = true;
                if (!wall) {
                    boolean attached = state.getValue(BlockStateProperties.ATTACHED);
                    root.getChild("normalChains").visible = !attached;
                    root.getChild("vChains").visible = attached;
                }
                pose.translate(0.5D, 0.9375D, 0.5D);
                pose.mulPose(Axis.YP.rotationDegrees(yaw));
                pose.translate(0.0F, -0.3125F, 0.0F);
                pose.scale(1.0F, -1.0F, -1.0F);
                root.render(pose, sink.of(Sheets.getHangingSignMaterial(wood)),
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            } else {
                ModelPart root = layer(ModelLayers.createSignModelName(wood));
                boolean standing = sign instanceof StandingSignBlock;
                root.getChild("stick").visible = standing;
                float scale = 0.6666667F;
                pose.translate(0.5F, 0.75F * scale, 0.5F);
                pose.mulPose(Axis.YP.rotationDegrees(yaw));
                if (!standing) {
                    pose.translate(0.0F, -0.3125F, -0.4375F);
                }
                pose.scale(scale, -scale, -scale);
                root.render(pose, sink.of(Sheets.getSignMaterial(wood)),
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            }
        } finally {
            pose.popPose();
        }
    }

    // ---- banners ----------------------------------------------------------

    /**
     * The pole, the bar and the flag, with every pattern the schematic saved
     * against it laid over the base colour. The flag hangs still rather than
     * waving, since the bake is done once.
     */
    private static void banner(BlockState state, AbstractBannerBlock banner, CompoundTag tag,
                               PoseStack pose, Sink sink) {
        ModelPart root = layer(ModelLayers.BANNER);
        ModelPart flag = root.getChild("flag");
        ModelPart pole = root.getChild("pole");
        ModelPart bar = root.getChild("bar");
        ListTag saved = tag != null && tag.contains(BannerBlockEntity.TAG_PATTERNS, Tag.TAG_LIST)
                ? tag.getList(BannerBlockEntity.TAG_PATTERNS, Tag.TAG_COMPOUND)
                : null;
        List<Pair<Holder<BannerPattern>, DyeColor>> patterns =
                BannerBlockEntity.createPatterns(banner.getColor(), saved);

        pose.pushPose();
        try {
            if (banner instanceof BannerBlock) {
                pose.translate(0.5F, 0.5F, 0.5F);
                pose.mulPose(Axis.YP.rotationDegrees(
                        -RotationSegment.convertToDegrees(state.getValue(BannerBlock.ROTATION))));
                pole.visible = true;
            } else {
                pose.translate(0.5F, -0.16666667F, 0.5F);
                pose.mulPose(Axis.YP.rotationDegrees(-state.getValue(WallBannerBlock.FACING).toYRot()));
                pose.translate(0.0F, -0.3125F, -0.4375F);
                pole.visible = false;
            }
            pose.scale(0.6666667F, -0.6666667F, -0.6666667F);
            VertexConsumer base = sink.of(ModelBakery.BANNER_BASE);
            pole.render(pose, base, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            bar.render(pose, base, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            flag.xRot = 0.0F;
            flag.y = -32.0F;
            flag.render(pose, base, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            for (int i = 0; i < 17 && i < patterns.size(); i++) {
                Pair<Holder<BannerPattern>, DyeColor> pair = patterns.get(i);
                float[] rgb = pair.getSecond().getTextureDiffuseColors();
                pair.getFirst().unwrapKey().map(Sheets::getBannerMaterial).ifPresent(material ->
                        flag.render(pose, sink.of(material), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                                rgb[0], rgb[1], rgb[2], 1.0F));
            }
        } finally {
            pose.popPose();
        }
    }

    // ---- shulker boxes ----------------------------------------------------

    private static void shulkerBox(BlockState state, ShulkerBoxBlock shulker, PoseStack pose, Sink sink) {
        DyeColor colour = shulker.getColor();
        Material material = colour == null
                ? Sheets.DEFAULT_SHULKER_TEXTURE_LOCATION
                : Sheets.SHULKER_TEXTURE_LOCATION.get(colour.getId());
        ShulkerModel<?> model = new ShulkerModel<>(layer(ModelLayers.SHULKER));

        pose.pushPose();
        try {
            pose.translate(0.5F, 0.5F, 0.5F);
            pose.scale(0.9995F, 0.9995F, 0.9995F);
            pose.mulPose(state.getValue(ShulkerBoxBlock.FACING).getRotation());
            pose.scale(1.0F, -1.0F, -1.0F);
            pose.translate(0.0F, -1.0F, 0.0F);
            // shut, which is the lid at rest
            model.getLid().setPos(0.0F, 24.0F, 0.0F);
            model.getLid().yRot = 0.0F;
            model.renderToBuffer(pose, sink.of(material), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    1.0F, 1.0F, 1.0F, 1.0F);
        } finally {
            pose.popPose();
        }
    }

    // ---- heads ------------------------------------------------------------

    /**
     * Every kind of head the game or a mod registers a model for. A player
     * head wears its owner's skin when that skin is already known to the
     * client, and the default skin otherwise; a ghost is not worth a lookup.
     */
    private static void skull(BlockState state, AbstractSkullBlock skull, CompoundTag tag,
                              PoseStack pose, Sink sink) {
        SkullBlock.Type type = skull.getType();
        SkullModelBase model = SkullBlockRenderer.createSkullRenderers(Minecraft.getInstance().getEntityModels()).get(type);
        if (model == null) {
            return;
        }
        Direction facing = skull instanceof WallSkullBlock ? state.getValue(WallSkullBlock.FACING) : null;
        float yaw = facing != null
                ? facing.getOpposite().toYRot()
                : RotationSegment.convertToDegrees(state.getValue(SkullBlock.ROTATION));

        pose.pushPose();
        try {
            if (facing == null) {
                pose.translate(0.5F, 0.0F, 0.5F);
            } else {
                pose.translate(0.5F - facing.getStepX() * 0.25F, 0.25F, 0.5F - facing.getStepZ() * 0.25F);
            }
            pose.scale(-1.0F, -1.0F, 1.0F);
            model.setupAnim(0.0F, yaw, 0.0F);
            model.renderToBuffer(pose, sink.plain(skinFor(type, tag)), LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        } finally {
            pose.popPose();
        }
    }

    private static ResourceLocation skinFor(SkullBlock.Type type, CompoundTag tag) {
        ResourceLocation skin = SkullBlockRenderer.SKIN_BY_TYPE.get(type);
        if (type != SkullBlock.Types.PLAYER || tag == null || !tag.contains("SkullOwner", Tag.TAG_COMPOUND)) {
            return skin != null ? skin : DefaultPlayerSkin.getDefaultSkin();
        }
        GameProfile profile = NbtUtils.readGameProfile(tag.getCompound("SkullOwner"));
        if (profile == null) {
            return DefaultPlayerSkin.getDefaultSkin();
        }
        Minecraft mc = Minecraft.getInstance();
        var known = mc.getSkinManager().getInsecureSkinInformation(profile);
        MinecraftProfileTexture texture = known.get(MinecraftProfileTexture.Type.SKIN);
        return texture != null
                ? mc.getSkinManager().registerTexture(texture, MinecraftProfileTexture.Type.SKIN)
                : DefaultPlayerSkin.getDefaultSkin(UUIDUtil.getOrCreatePlayerUUID(profile));
    }

    // ---- decorated pots ---------------------------------------------------

    private static void decoratedPot(BlockState state, CompoundTag tag, PoseStack pose, Sink sink) {
        ModelPart base = layer(ModelLayers.DECORATED_POT_BASE);
        ModelPart sides = layer(ModelLayers.DECORATED_POT_SIDES);
        DecoratedPotBlockEntity.Decorations decorations = DecoratedPotBlockEntity.Decorations.load(tag);

        pose.pushPose();
        try {
            pose.translate(0.5D, 0.0D, 0.5D);
            pose.mulPose(Axis.YP.rotationDegrees(180.0F - state.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot()));
            pose.translate(-0.5D, 0.0D, -0.5D);
            VertexConsumer plain = sink.of(Sheets.getDecoratedPotMaterial(DecoratedPotPatterns.BASE));
            for (String part : new String[] {"neck", "top", "bottom"}) {
                base.getChild(part).render(pose, plain, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            }
            potSide(sides.getChild("front"), decorations.front(), pose, sink);
            potSide(sides.getChild("back"), decorations.back(), pose, sink);
            potSide(sides.getChild("left"), decorations.left(), pose, sink);
            potSide(sides.getChild("right"), decorations.right(), pose, sink);
        } finally {
            pose.popPose();
        }
    }

    private static void potSide(ModelPart side, Item sherd, PoseStack pose, Sink sink) {
        Material material = Sheets.getDecoratedPotMaterial(DecoratedPotPatterns.getResourceKey(sherd));
        if (material == null) {
            material = Sheets.getDecoratedPotMaterial(DecoratedPotPatterns.getResourceKey(Items.BRICK));
        }
        if (material != null) {
            side.render(pose, sink.of(material), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }
    }

    // ---- conduits ---------------------------------------------------------

    /** The dormant shell. Its texture is on the block atlas, so it lands in the block mesh. */
    private static void conduit(PoseStack pose, Sink sink) {
        ModelPart shell = layer(ModelLayers.CONDUIT_SHELL).getChild("shell");
        pose.pushPose();
        try {
            pose.translate(0.5F, 0.5F, 0.5F);
            shell.render(pose, sink.of(ConduitRenderer.SHELL_TEXTURE), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        } finally {
            pose.popPose();
        }
    }

    /**
     * Gives model part vertices the same directional shade block quads get.
     *
     * <p>The entity renderers light their parts from the normal in the shader.
     * The hologram shader has no such pass, it multiplies by the vertex colour
     * and nothing else, so a chest came out flat. The normal is still in every
     * vertex, and the nearest face direction gives the shade the block
     * renderer would have used.</p>
     */
    private static final class Shaded implements VertexConsumer {
        private final VertexConsumer delegate;
        private final BlockAndTintGetter level;

        Shaded(VertexConsumer delegate, BlockAndTintGetter level) {
            this.delegate = delegate;
            this.level = level;
        }

        @Override
        public void vertex(float x, float y, float z, float r, float g, float b, float a,
                           float u, float v, int overlay, int light, float nx, float ny, float nz) {
            float shade = level.getShade(Direction.getNearest(nx, ny, nz), true);
            delegate.vertex(x, y, z, r * shade, g * shade, b * shade, a, u, v, overlay, light, nx, ny, nz);
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            return delegate.vertex(x, y, z);
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return delegate.color(r, g, b, a);
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return delegate.uv(u, v);
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return delegate.overlayCoords(u, v);
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return delegate.uv2(u, v);
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return delegate.normal(x, y, z);
        }

        @Override
        public void endVertex() {
            delegate.endVertex();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            delegate.defaultColor(r, g, b, a);
        }

        @Override
        public void unsetDefaultColor() {
            delegate.unsetDefaultColor();
        }
    }

    // ---- the box fallback -------------------------------------------------

    private static void boxes(BlockAndTintGetter level, BlockState state, BlockPos pos, PoseStack pose,
                              VertexConsumer builder, TextureAtlasSprite sprite) {
        Matrix4f matrix = pose.last().pose();
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        for (AABB box : state.getShape(level, pos).toAabbs()) {
            for (Direction side : Direction.values()) {
                // A face flush with the block edge is culled the way a model's
                // would be, so two halves of a thing do not draw a seam between
                // them. A face inside the block is always drawn.
                if (onEdge(box, side)) {
                    neighbour.setWithOffset(pos, side);
                    if (!Block.shouldRenderFace(state, level, pos, side, neighbour)) {
                        continue;
                    }
                }
                face(builder, matrix, box, side, sprite, level.getShade(side, true));
            }
        }
    }

    private static boolean onEdge(AABB box, Direction side) {
        return switch (side) {
            case DOWN -> box.minY <= 0.0D;
            case UP -> box.maxY >= 1.0D;
            case NORTH -> box.minZ <= 0.0D;
            case SOUTH -> box.maxZ >= 1.0D;
            case WEST -> box.minX <= 0.0D;
            case EAST -> box.maxX >= 1.0D;
        };
    }

    /**
     * One face of a box, wound anticlockwise seen from outside like vanilla's
     * model quads, with the texture stretched over the face's extent.
     */
    private static void face(VertexConsumer builder, Matrix4f matrix, AABB box, Direction side,
                             TextureAtlasSprite sprite, float shade) {
        float x0 = (float) box.minX;
        float y0 = (float) box.minY;
        float z0 = (float) box.minZ;
        float x1 = (float) box.maxX;
        float y1 = (float) box.maxY;
        float z1 = (float) box.maxZ;
        switch (side) {
            case DOWN -> quad(builder, matrix, sprite, shade, side,
                    x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, z0, x1, z1);
            case UP -> quad(builder, matrix, sprite, shade, side,
                    x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, z0, x1, z1);
            case NORTH -> quad(builder, matrix, sprite, shade, side,
                    x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0, x0, y0, x1, y1);
            case SOUTH -> quad(builder, matrix, sprite, shade, side,
                    x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y0, x1, y1);
            case WEST -> quad(builder, matrix, sprite, shade, side,
                    x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1, z0, y0, z1, y1);
            case EAST -> quad(builder, matrix, sprite, shade, side,
                    x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0, z0, y0, z1, y1);
        }
    }

    /**
     * Four corners in order, then the extent of the face in the two axes it
     * spans, in block units, which the sprite is mapped across.
     */
    private static void quad(VertexConsumer builder, Matrix4f matrix, TextureAtlasSprite sprite, float shade,
                             Direction side,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float u0, float v0, float u1, float v1) {
        float minU = sprite.getU(u0 * 16.0D);
        float maxU = sprite.getU(u1 * 16.0D);
        float minV = sprite.getV(v0 * 16.0D);
        float maxV = sprite.getV(v1 * 16.0D);
        vertex(builder, matrix, side, shade, ax, ay, az, minU, minV);
        vertex(builder, matrix, side, shade, bx, by, bz, minU, maxV);
        vertex(builder, matrix, side, shade, cx, cy, cz, maxU, maxV);
        vertex(builder, matrix, side, shade, dx, dy, dz, maxU, minV);
    }

    private static void vertex(VertexConsumer builder, Matrix4f matrix, Direction side, float shade,
                               float x, float y, float z, float u, float v) {
        builder.vertex(matrix, x, y, z)
                .color(shade, shade, shade, 1.0F)
                .uv(u, v)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(side.getStepX(), side.getStepY(), side.getStepZ())
                .endVertex();
    }
}
