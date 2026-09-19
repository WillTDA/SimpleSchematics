package dev.willtda.simpleschematics.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Every setting the mod exposes. Backed by a standard Forge config spec so the
 * values survive restarts on their own, and surfaced through
 * {@link ConfigScreen} from the Mods list.
 */
public final class SSConfig {

    public static final SSConfig INSTANCE;
    public static final ForgeConfigSpec SPEC;

    static {
        Pair<SSConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(SSConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    // ---- general ----------------------------------------------------------
    public final ForgeConfigSpec.ConfigValue<String> toolItem;
    public final ForgeConfigSpec.BooleanValue enabledOnLaunch;
    public final ForgeConfigSpec.BooleanValue toolRequiredForHotkeys;
    public final ForgeConfigSpec.BooleanValue actionBarFeedback;
    public final ForgeConfigSpec.BooleanValue invertScroll;
    public final ForgeConfigSpec.BooleanValue menuKeyBlocksOtherMods;
    public final ForgeConfigSpec.IntValue maxSelectionReach;
    public final ForgeConfigSpec.ConfigValue<String> dataDirectory;
    /** Remembered rather than chosen, so the mod comes back the way you left it. */
    public final ForgeConfigSpec.ConfigValue<String> lastMode;

    // ---- scan -------------------------------------------------------------
    public final ForgeConfigSpec.ConfigValue<String> startCornerColour;
    public final ForgeConfigSpec.ConfigValue<String> endCornerColour;
    public final ForgeConfigSpec.ConfigValue<String> selectionBoxColour;
    public final ForgeConfigSpec.DoubleValue selectionFillOpacity;
    public final ForgeConfigSpec.BooleanValue showTargetBlockOutline;
    public final ForgeConfigSpec.BooleanValue swapScanCorners;
    public final ForgeConfigSpec.BooleanValue saveEntitiesByDefault;
    public final ForgeConfigSpec.BooleanValue saveContainerContentsByDefault;

    // ---- build / hologram -------------------------------------------------
    public final ForgeConfigSpec.BooleanValue renderHolograms;
    public final ForgeConfigSpec.DoubleValue hologramOpacity;
    public final ForgeConfigSpec.BooleanValue hologramOutline;
    public final ForgeConfigSpec.BooleanValue hologramBlockOutline;
    public final ForgeConfigSpec.ConfigValue<String> hologramBlockOutlineColour;
    public final ForgeConfigSpec.DoubleValue hologramBlockOutlineOpacity;
    public final ForgeConfigSpec.IntValue hologramBlockOutlineDistance;
    public final ForgeConfigSpec.BooleanValue hologramNearFade;
    public final ForgeConfigSpec.DoubleValue hologramFadeDistance;
    public final ForgeConfigSpec.BooleanValue hologramBreathe;
    public final ForgeConfigSpec.DoubleValue hologramBreatheDepth;
    public final ForgeConfigSpec.DoubleValue hologramBreathePeriod;
    public final ForgeConfigSpec.BooleanValue highlightMismatches;
    public final ForgeConfigSpec.IntValue hologramRenderDistance;
    public final ForgeConfigSpec.BooleanValue highlightExtraBlocks;
    public final ForgeConfigSpec.BooleanValue hideCorrectBlocks;
    public final ForgeConfigSpec.BooleanValue strictStateMatch;
    public final ForgeConfigSpec.ConfigValue<String> mismatchColour;
    public final ForgeConfigSpec.ConfigValue<String> extraBlockColour;
    public final ForgeConfigSpec.DoubleValue highlightFillOpacity;
    public final ForgeConfigSpec.IntValue maxHighlights;
    public final ForgeConfigSpec.IntValue verifyBlocksPerTick;
    public final ForgeConfigSpec.IntValue maxPreviewBlocks;
    public final ForgeConfigSpec.BooleanValue layerScrollSound;
    public final ForgeConfigSpec.DoubleValue layerScrollVolume;
    public final ForgeConfigSpec.BooleanValue snapPlacementToGrid;
    public final ForgeConfigSpec.EnumValue<dev.willtda.simpleschematics.render.ShaderPackCompat.Mode> shaderPackCompat;
    public final ForgeConfigSpec.BooleanValue autoSelectLookedAt;

    // ---- print ------------------------------------------------------------
    public final ForgeConfigSpec.EnumValue<PrintSource> printSource;
    public final ForgeConfigSpec.IntValue printDelay;
    public final ForgeConfigSpec.BooleanValue printSounds;
    public final ForgeConfigSpec.BooleanValue printParticles;
    public final ForgeConfigSpec.BooleanValue printWarnSurvival;
    public final ForgeConfigSpec.BooleanValue printWarnMissing;
    public final ForgeConfigSpec.BooleanValue printReplaceBlocks;
    public final ForgeConfigSpec.BooleanValue printEntities;
    public final ForgeConfigSpec.BooleanValue printContents;

    public enum PrintSource {
        INVENTORY, LINKED_CHESTS, BOTH
    }

    // ---- resource list ----------------------------------------------------
    public final ForgeConfigSpec.BooleanValue resourceListVisible;
    public final ForgeConfigSpec.BooleanValue resourceListEnabled;
    public final ForgeConfigSpec.EnumValue<Anchor> resourceListAnchor;
    public final ForgeConfigSpec.IntValue resourceListOffsetX;
    public final ForgeConfigSpec.IntValue resourceListOffsetY;
    public final ForgeConfigSpec.DoubleValue resourceListScale;
    public final ForgeConfigSpec.IntValue resourceListMaxWidth;
    public final ForgeConfigSpec.IntValue resourceListMaxRows;
    public final ForgeConfigSpec.DoubleValue resourceListBackgroundOpacity;
    public final ForgeConfigSpec.BooleanValue removeCollectedItems;
    public final ForgeConfigSpec.BooleanValue countOpenContainers;
    public final ForgeConfigSpec.BooleanValue countEnderChest;
    public final ForgeConfigSpec.BooleanValue showStackBreakdown;
    public final ForgeConfigSpec.BooleanValue hideCompletedRows;
    public final ForgeConfigSpec.BooleanValue countPlacedBlocks;
    public final ForgeConfigSpec.BooleanValue showBuildName;

    // ---- build list -------------------------------------------------------
    public final ForgeConfigSpec.BooleanValue buildListVisible;
    public final ForgeConfigSpec.BooleanValue buildListEnabled;
    public final ForgeConfigSpec.EnumValue<Anchor> buildListAnchor;
    public final ForgeConfigSpec.IntValue buildListOffsetX;
    public final ForgeConfigSpec.IntValue buildListOffsetY;
    public final ForgeConfigSpec.IntValue buildListMaxRows;

    public enum Anchor {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    private SSConfig(ForgeConfigSpec.Builder b) {
        b.comment("General behaviour").push("general");
        toolItem = b.comment("The item you hold to use Simple Schematics. Use a registry id, for example minecraft:stick")
                .define("toolItem", "minecraft:stick");
        enabledOnLaunch = b.comment("Start the game with the mod switched on")
                .define("enabledOnLaunch", true);
        toolRequiredForHotkeys = b.comment("Require the tool item in hand before scroll shortcuts do anything")
                .define("toolRequiredForHotkeys", true);
        actionBarFeedback = b.comment("Show confirmations above the hotbar")
                .define("actionBarFeedback", true);
        invertScroll = b.comment("Reverse the scroll direction for every Simple Schematics shortcut")
                .define("invertScroll", false);
        menuKeyBlocksOtherMods = b.comment("Take the menu key away from anything else bound to it, so holding it as a shortcut prefix does nothing else.",
                        "Switch this off if a minimap or another mod shares the key and you would rather keep that.",
                        "The chords go through vanilla again at that point, so rebind the Simple Schematics menu key under Controls as well")
                .define("menuKeyBlocksOtherMods", false);
        maxSelectionReach = b.comment("How far away, in blocks, you can set a selection corner")
                .defineInRange("maxSelectionReach", 128, 8, 512);
        lastMode = b.comment("The mode you were last in. Set for you, not meant to be edited by hand")
                .define("lastMode", "SCAN");
        dataDirectory = b.comment("Where schematics and saved data live. Leave blank for <game folder>/simpleschematics",
                        "Point this at a synced folder such as OneDrive or Dropbox to share data between computers")
                .define("dataDirectory", "");
        b.pop();

        b.comment("Scan mode").push("scan");
        startCornerColour = b.comment("Colour of the start corner, as RRGGBB")
                .define("startCornerColour", "36D399");
        endCornerColour = b.comment("Colour of the end corner, as RRGGBB")
                .define("endCornerColour", "F87272");
        selectionBoxColour = b.comment("Colour of the bounding box, as RRGGBB")
                .define("selectionBoxColour", "60A5FA");
        selectionFillOpacity = b.comment("How solid the selection shading is, 0 for outline only")
                .defineInRange("selectionFillOpacity", 0.14D, 0.0D, 0.6D);
        swapScanCorners = b.comment("Swap which mouse button sets which corner: left click for the start and right click for the end")
                .define("swapScanCorners", false);
        showTargetBlockOutline = b.comment("Show a white target marker in Scan mode while holding the tool")
                .define("showTargetBlockOutline", true);
        saveEntitiesByDefault = b.comment("Tick the entities box by default on the save dialogue")
                .define("saveEntitiesByDefault", true);
        saveContainerContentsByDefault = b.comment("Tick the container contents box by default on the save dialogue")
                .define("saveContainerContentsByDefault", true);
        b.pop();

        b.comment("Build mode and holograms").push("build");
        renderHolograms = b.comment("Show holograms. Remembered by the menu toggle and the hologram shortcut")
                .define("renderHolograms", true);
        hologramOpacity = b.comment("How solid the hologram looks")
                .defineInRange("hologramOpacity", 0.65D, 0.0D, 1.0D);
        hologramOutline = b.comment("Draw a box around the hologram")
                .define("hologramOutline", false);
        hologramBlockOutline = b.comment("Draw an edge around every block in the hologram, so the grid is readable")
                .define("hologramBlockOutline", true);
        hologramBlockOutlineColour = b.comment("Colour of those block edges, as RRGGBB")
                .define("hologramBlockOutlineColour", "FFFFFF");
        hologramBlockOutlineOpacity = b.comment("How strong the block edges are")
                .defineInRange("hologramBlockOutlineOpacity", 0.20D, 0.0D, 1.0D);
        hologramBlockOutlineDistance = b.comment("Only draw block edges within this many blocks of you, because they are not cheap")
                .defineInRange("hologramBlockOutlineDistance", 4, 4, 64);
        hologramNearFade = b.comment("Fade nearby ghost surfaces so you can see and build through them")
                .define("hologramNearFade", true);
        hologramFadeDistance = b.comment("Distance in blocks at which nearby ghosts reach full configured opacity")
                .defineInRange("hologramFadeDistance", 2.0D, 0.5D, 8.0D);
        hologramBreathe = b.comment("Pulse the hologram opacity slowly, so the ghosts stand out from the blocks around them")
                .define("hologramBreathe", true);
        hologramBreatheDepth = b.comment("How far each breath dips, as a fraction of the hologram opacity. 1 fades all the way out")
                .defineInRange("hologramBreatheDepth", 0.6D, 0.1D, 1.0D);
        hologramBreathePeriod = b.comment("Seconds one breath in and out takes")
                .defineInRange("hologramBreathePeriod", 1.5D, 0.5D, 10.0D);
        highlightMismatches = b.comment("Outline blocks that are placed but are the wrong block")
                .define("highlightMismatches", true);
        highlightExtraBlocks = b.comment("Also outline blocks that are in the way and should not be there")
                .define("highlightExtraBlocks", true);
        hideCorrectBlocks = b.comment("Stop drawing the hologram for blocks you have already placed correctly")
                .define("hideCorrectBlocks", true);
        strictStateMatch = b.comment("Also compare connections, power, waterlogging and other changing properties.",
                        "Block type, facing, axis and basic shape are always checked.",
                        "The expected state follows the placement rotation and mirror")
                .define("strictStateMatch", false);
        mismatchColour = b.comment("Colour for a wrong block, as RRGGBB")
                .define("mismatchColour", "F87272");
        extraBlockColour = b.comment("Colour for a block that should not be there, as RRGGBB")
                .define("extraBlockColour", "FBBF24");
        highlightFillOpacity = b.comment("How solid the highlight shading is, 0 for outline only")
                .defineInRange("highlightFillOpacity", 0.22D, 0.0D, 0.8D);
        maxHighlights = b.comment("Stop collecting highlights past this many, per placement")
                .defineInRange("maxHighlights", 4096, 64, 100000);
        verifyBlocksPerTick = b.comment("How many blocks to compare against the world each tick.",
                        "Lower this if checking a very large build costs you frames")
                .defineInRange("verifyBlocksPerTick", 8000, 500, 200000);
        hologramRenderDistance = b.comment("Stop drawing the hologram past this many blocks")
                .defineInRange("hologramRenderDistance", 192, 32, 512);
        maxPreviewBlocks = b.comment("Skip the live 3D preview in the library for schematics larger than this")
                .defineInRange("maxPreviewBlocks", 120000, 1000, 5000000);
        layerScrollSound = b.comment("Play a hi hat tick when you change layer")
                .define("layerScrollSound", true);
        layerScrollVolume = b.comment("Volume of the layer tick")
                .defineInRange("layerScrollVolume", 0.6D, 0.0D, 1.0D);
        autoSelectLookedAt = b.comment("Selecting whichever placement you are looking at, instead of choosing one from the list")
                .define("autoSelectLookedAt", false);
        snapPlacementToGrid = b.comment("Snap a new placement to the block you are looking at rather than free floating")
                .define("snapPlacementToGrid", true);
        shaderPackCompat = b.comment("How to draw the holograms while a shader pack is running.",
                        "Iris and Oculus replace the level render and ignore this mod's own shader, which leaves the ghosts invisible,",
                        "so AUTO switches to a plain vanilla render type whenever a pack is loaded. The near fade and the distance",
                        "fade are lost on that path; opacity is not. ALWAYS forces it, NEVER keeps this mod's shader whatever is installed")
                .defineEnum("shaderPackCompat", dev.willtda.simpleschematics.render.ShaderPackCompat.Mode.AUTO);
        b.pop();

        b.comment("Print mode").push("print");
        printSource = b.comment("Where Survival printing takes materials from. Linked chests must be accessible and in reach")
                .defineEnum("printSource", PrintSource.BOTH);
        printDelay = b.comment("Ticks between Survival block placements. Twenty ticks is one second")
                .defineInRange("printDelay", 4, 1, 40);
        printSounds = b.comment("Play each block's placement sound while printing")
                .define("printSounds", true);
        printParticles = b.comment("Show a small burst of particles at each printed block")
                .define("printParticles", true);
        printWarnSurvival = b.comment("Ask before Survival printing and remind you to check the server's automation rules")
                .define("printWarnSurvival", true);
        printWarnMissing = b.comment("Ask before starting a print without all the required materials")
                .define("printWarnMissing", true);
        printReplaceBlocks = b.comment("Allow an operator to replace blocks in the way. Other players must clear them by hand")
                .define("printReplaceBlocks", false);
        printEntities = b.comment("Include saved entities when printing in Creative. Survival never prints entities")
                .define("printEntities", true);
        printContents = b.comment("Include saved container contents when printing in Creative. Survival never prints contents")
                .define("printContents", true);
        b.pop();

        b.comment("Resource list").push("resource_list");
        resourceListVisible = b.comment("Show the resource list for schematics being positioned. Existing placements remember their own choice")
                .define("resourceListVisible", false);
        resourceListEnabled = b.comment("Show the resource list overlay when a schematic is targeted")
                .define("resourceListEnabled", true);
        resourceListAnchor = b.comment("Which corner the list sits in")
                .defineEnum("resourceListAnchor", Anchor.BOTTOM_RIGHT);
        resourceListOffsetX = b.comment("Horizontal nudge away from the chosen corner, in pixels")
                .defineInRange("resourceListOffsetX", 4, 0, 400);
        resourceListOffsetY = b.comment("Vertical nudge away from the chosen corner, in pixels")
                .defineInRange("resourceListOffsetY", 4, 0, 400);
        resourceListScale = b.comment("Size of the list relative to the rest of the interface")
                .defineInRange("resourceListScale", 0.5D, 0.4D, 2.0D);
        resourceListMaxWidth = b.comment("The widest the list may get, in pixels before scaling.",
                        "The panel is measured from the names it is drawing and only trims them once it reaches this")
                .defineInRange("resourceListMaxWidth", 240, 120, 500);
        resourceListMaxRows = b.comment("How many item rows to show at once")
                .defineInRange("resourceListMaxRows", 10, 1, 40);
        resourceListBackgroundOpacity = b.comment("How dark the panel behind the list is")
                .defineInRange("resourceListBackgroundOpacity", 0.55D, 0.0D, 1.0D);
        removeCollectedItems = b.comment("Automatically knock items off the list once you are carrying enough")
                .define("removeCollectedItems", true);
        countOpenContainers = b.comment("Count items sitting in a chest while its screen is open")
                .define("countOpenContainers", true);
        countEnderChest = b.comment("Count items in your ender chest the last time you opened it")
                .define("countEnderChest", true);
        showStackBreakdown = b.comment("Show totals as stacks plus a remainder")
                .define("showStackBreakdown", true);
        hideCompletedRows = b.comment("Hide rows once they are fully gathered")
                .define("hideCompletedRows", true);
        countPlacedBlocks = b.comment("Take blocks already standing correctly in the placement off what is still needed.",
                        "Only loaded chunks can be checked, so a distant build reads as untouched until you visit it")
                .define("countPlacedBlocks", true);
        showBuildName = b.comment("Name the build the list is following, above the list itself")
                .define("showBuildName", true);
        b.pop();

        b.comment("Build list", "The panel that says what is still to place for the layer you are looking at.",
                        "Size, width, panel darkness and the stack breakdown are shared with the resource list")
                .push("build_list");
        buildListVisible = b.comment("Show the build list for schematics being positioned. Existing placements remember their own choice")
                .define("buildListVisible", false);
        buildListEnabled = b.comment("Show the build list overlay when it is switched on for a build")
                .define("buildListEnabled", true);
        buildListAnchor = b.comment("Which corner the list sits in. Sharing a corner with the resource list stacks the two")
                .defineEnum("buildListAnchor", Anchor.TOP_RIGHT);
        buildListOffsetX = b.comment("Horizontal nudge away from the chosen corner, in pixels")
                .defineInRange("buildListOffsetX", 4, 0, 400);
        buildListOffsetY = b.comment("Vertical nudge away from the chosen corner, in pixels")
                .defineInRange("buildListOffsetY", 4, 0, 400);
        buildListMaxRows = b.comment("How many item rows to show at once")
                .defineInRange("buildListMaxRows", 10, 1, 40);
        b.pop();
    }

    /** Resolves the configured tool item, falling back to a stick if the id is nonsense. */
    public Item resolveToolItem() {
        ResourceLocation id = ResourceLocation.tryParse(toolItem.get());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return net.minecraft.world.item.Items.STICK;
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    /** Parses an RRGGBB string into 0xRRGGBB, falling back to white. */
    public static int colour(String hex, int fallback) {
        try {
            return Integer.parseInt(hex.replace("#", "").trim(), 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
