package dev.willtda.simpleschematics.printing;

import dev.willtda.simpleschematics.client.ClientState;
import dev.willtda.simpleschematics.client.EditMode;
import dev.willtda.simpleschematics.client.Feedback;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.gui.PrintConfirmScreen;
import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.placement.PlacementManager;
import dev.willtda.simpleschematics.resource.MaterialResolver;
import dev.willtda.simpleschematics.schematic.Schematic;
import dev.willtda.simpleschematics.schematic.SchematicLibrary;
import dev.willtda.simpleschematics.util.DataPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Starts, holds and finishes printing. The server remains the authority for every change.
 *
 * <p>A print checks the world once, asks at most one question, and then runs
 * until the build is done or the player stops it. Opening chat, the inventory
 * or the pause menu only holds it; it carries on when the screen closes.
 * Leaving Print mode, moving the placement, dying or leaving the world stops
 * it, and Enter starts it again from wherever the world has got to, without
 * asking anything already answered this session.</p>
 */
public final class PrintManager {

    public static final PrintManager INSTANCE = new PrintManager();

    private enum Phase { IDLE, SURVEYING, CONFIRMING, RUNNING }

    private static final int STATUS_TICKS = 40;

    private Phase phase = Phase.IDLE;
    private PrintPlan plan;
    private PrintSurvey survey;
    private ClientLevel level;
    private boolean creative;
    private boolean operator;
    private boolean chestsOnly;
    private boolean useBanks;
    private boolean resumed;
    /** The server rules warning has been answered this session. */
    private boolean rulesAcknowledged;
    private long generation;
    private int statusTicks;
    private HandPrinter hand;
    private CreativePrinter paste;
    private PrintInventory inventory = new PrintInventory();
    /** Prints already agreed to this session, which are not asked about again. */
    private final Set<String> confirmed = new HashSet<>();
    private final Map<String, ResourceBudget<Item>> withdrawalBudgets = new LinkedHashMap<>();

    private PrintManager() {
    }

    public boolean isRunning() {
        return phase != Phase.IDLE;
    }

    /** Enter, or the use key with the tool out: start a print, or stop the one that is running. */
    public void toggle() {
        if (isRunning()) {
            pause();
        } else {
            start();
        }
    }

    /** Starts printing the selected placement, restarting any print already running. */
    public void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (phase != Phase.IDLE) reset();
        if (mc.gameMode.getPlayerMode() != GameType.CREATIVE && mc.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            error("simpleschematics.print.mode");
            return;
        }
        Placement selected = PlacementManager.INSTANCE.selected();
        SchematicLibrary.Entry entry = selected == null ? null : SchematicLibrary.INSTANCE.byKey(selected.schematicKey());
        if (entry == null || entry.get() == null) {
            error("simpleschematics.print.select");
            return;
        }
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) {
            error("simpleschematics.print.inventory_busy");
            return;
        }
        if (mc.player.isCreative() && mc.player.hasPermissions(2)
                && mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) {
            // The server refuses every command from a player who has hidden chat.
            error("simpleschematics.print.chat_hidden");
            return;
        }
        level = mc.level;
        String signature = signature(entry, selected);
        plan = PrintPlan.of(selected, entry.get(), signature);
        survey = new PrintSurvey(plan);
        creative = mc.player.isCreative();
        operator = mc.player.hasPermissions(2);
        chestsOnly = SSConfig.INSTANCE.printSource.get() == SSConfig.PrintSource.LINKED_CHESTS;
        useBanks = SSConfig.INSTANCE.printSource.get() != SSConfig.PrintSource.INVENTORY;
        resumed = selected.hasPrintProgress(signature);
        String budgetKey = DataPaths.currentWorldKey() + ":" + mc.player.getUUID() + ":" + selected.id()
                + ":" + signature + ":" + chestsOnly;
        if (withdrawalBudgets.size() > 128) withdrawalBudgets.clear();
        inventory = new PrintInventory(withdrawalBudgets.computeIfAbsent(budgetKey, key -> {
            ResourceBudget<Item> budget = new ResourceBudget<>();
            budget.reserveExisting(PrintInventory.counts(mc));
            return budget;
        }));
        statusTicks = 0;
        phase = Phase.SURVEYING;
        info(tr("simpleschematics.print.checking"));
    }

    private String signature(SchematicLibrary.Entry entry, Placement placement) {
        return entry.stamp() + ":" + placement.origin().asLong() + ":" + placement.rotation()
                + ":" + placement.mirror() + ":" + level.dimension().location();
    }

    private boolean valid(Minecraft mc) {
        SchematicLibrary.Entry entry = plan == null ? null : SchematicLibrary.INSTANCE.byKey(plan.placement.schematicKey());
        return mc.player != null && mc.player.isAlive() && mc.level == level && mc.gameMode != null
                && mc.player.isCreative() == creative && (creative || mc.gameMode.getPlayerMode() == GameType.SURVIVAL)
                && ClientState.INSTANCE.isEnabled() && ClientState.INSTANCE.mode() == EditMode.PRINT
                && PlacementManager.INSTANCE.selected() == plan.placement && entry != null
                && !plan.moved() && signature(entry, plan.placement).equals(plan.signature);
    }

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        PrintInventory.tickOrphan(mc);
        if (phase == Phase.IDLE) return;
        if (!valid(mc)) {
            if (plan != null && plan.moved() && mc.player != null) {
                reset();
                error("simpleschematics.print.moved");
            } else {
                pause();
            }
            return;
        }
        if (phase == Phase.CONFIRMING) return;
        // Chat, the inventory or the pause menu hold printing rather than stop it.
        if (mc.screen != null && !inventory.ownsScreen(mc)) {
            if (inventory.busy()) inventory.abandon();
            return;
        }
        if (mc.isPaused()) return;
        if (phase == Phase.SURVEYING) {
            if (survey.advance(mc.level)) surveyed(mc);
            return;
        }
        run(mc);
    }

    // ---- before starting --------------------------------------------------

    private void surveyed(Minecraft mc) {
        boolean replace = creative && operator && SSConfig.INSTANCE.printReplaceBlocks.get();
        boolean entityWork = creative && operator && SSConfig.INSTANCE.printEntities.get()
                && !plan.schematic.entities().isEmpty();
        int left = survey.work(replace);
        // A paste may still owe saved data to blocks it placed before it was interrupted.
        boolean pasteWork = creative && operator && (entityWork || !plan.schematic.blockEntities().isEmpty());
        if (left == 0 && !pasteWork) {
            int attention = (replace ? 0 : survey.obstructed) + survey.outside + survey.banks;
            if (attention == 0) {
                finish(true, tr("simpleschematics.print.already_complete"));
            } else {
                finish(false, tr("simpleschematics.print.finished_attention", 0, attention));
            }
            return;
        }

        // The rules warning is about the server, so it is asked once a session. The rest are
        // about this print, and continuing a print already agreed to asks nothing, unless it
        // now wants to replace blocks.
        String agreement = plan.signature + (replace && survey.obstructed > 0 ? ":replace" : "");
        boolean agreed = confirmed.contains(agreement);
        List<Component> decisions = new ArrayList<>();
        List<Component> remarks = new ArrayList<>();
        boolean silenceable = false;
        if (!creative && SSConfig.INSTANCE.printWarnSurvival.get() && !rulesAcknowledged) {
            decisions.add(tr("simpleschematics.print.note.rules"));
            silenceable = true;
        }
        if (!agreed && !creative && SSConfig.INSTANCE.printWarnMissing.get()) {
            int missing = ResourceBudget.missing(survey.required(), available(mc));
            if (missing > 0) {
                decisions.add(tr("simpleschematics.print.note.missing", missing));
                silenceable = true;
            }
        }
        if (!agreed && replace && survey.obstructed > 0) {
            decisions.add(tr("simpleschematics.print.note.replace", survey.obstructed));
        }
        if (!replace && survey.obstructed > 0) {
            remarks.add(tr("simpleschematics.print.note.obstructions", survey.obstructed));
        }
        if (survey.outside > 0) {
            remarks.add(tr("simpleschematics.print.note.outside", survey.outside));
        }
        if (creative && !operator) {
            remarks.add(tr("simpleschematics.print.note.by_hand"));
        } else if (!creative && (!plan.schematic.entities().isEmpty() || !plan.schematic.blockEntities().isEmpty())) {
            remarks.add(tr("simpleschematics.print.note.survival_data"));
        }

        if (decisions.isEmpty()) {
            begin(mc, remarks.isEmpty() ? null : remarks.get(0), agreement);
            return;
        }
        decisions.addAll(remarks);
        phase = Phase.CONFIRMING;
        long asked = ++generation;
        boolean offerSilence = silenceable;
        mc.setScreen(new PrintConfirmScreen(tr("simpleschematics.print.title"), decisions, offerSilence, choice -> {
            if (phase != Phase.CONFIRMING || generation != asked) return;
            mc.setScreen(null);
            if (choice == PrintConfirmScreen.Choice.CANCEL || !valid(mc)) {
                reset();
                info(tr("simpleschematics.print.cancelled"));
                return;
            }
            if (choice == PrintConfirmScreen.Choice.PRINT_AND_SILENCE) {
                SSConfig.INSTANCE.printWarnSurvival.set(false);
                SSConfig.INSTANCE.printWarnMissing.set(false);
                SSConfig.SPEC.save();
            }
            begin(mc, null, agreement);
        }));
    }

    private Map<Item, Integer> available(Minecraft mc) {
        Map<Item, Integer> available = PrintInventory.counts(mc);
        if (chestsOnly) available.replaceAll((item, count) -> inventory.budget.available(item, count, true));
        if (useBanks) {
            // Cached totals are only estimates, but they say whether a warning is worth showing.
            for (var bank : plan.placement.banks().entrySet()) {
                if (!mc.level.hasChunkAt(bank.getKey())) continue;
                for (var item : bank.getValue().entrySet()) {
                    ResourceLocation id = ResourceLocation.tryParse(item.getKey());
                    if (id != null) available.merge(BuiltInRegistries.ITEM.get(id), item.getValue(), Integer::sum);
                }
            }
        }
        return available;
    }

    private void begin(Minecraft mc, Component remark, String agreement) {
        phase = Phase.RUNNING;
        confirmed.add(agreement);
        if (!creative) rulesAcknowledged = true;
        plan.placement.markPrintStarted(plan.signature);
        PlacementManager.INSTANCE.markDirty();
        PlacementManager.INSTANCE.saveIfDirty();
        boolean replace = SSConfig.INSTANCE.printReplaceBlocks.get();
        int left = survey.work(creative && operator && replace);
        if (creative && operator) {
            PrintChat.reset();
            paste = new CreativePrinter(plan, left, replace, SSConfig.INSTANCE.printEntities.get(),
                    SSConfig.INSTANCE.printContents.get(), SSConfig.INSTANCE.printInstant.get());
        } else {
            hand = new HandPrinter(plan, creative, chestsOnly, useBanks, inventory, survey);
        }
        statusTicks = 0;
        if (remark != null) {
            info(remark);
        } else {
            info(tr(resumed ? "simpleschematics.print.resumed" : "simpleschematics.print.started", left));
        }
    }

    // ---- running ----------------------------------------------------------

    private void run(Minecraft mc) {
        if (mc.player.hasPermissions(2) != operator) {
            pause();
            return;
        }
        if (paste != null) {
            paste.tick();
            switch (paste.status()) {
                case COMPLETE -> finishPaste();
                case NO_PERMISSION -> {
                    String detail = paste.detail();
                    reset();
                    error(detail);
                }
                case CANCELLED -> pause();
                default -> status(paste.statusLine());
            }
            return;
        }
        hand.tick(mc);
        if (hand.failure() != null) {
            String failure = hand.failure();
            reset();
            error(failure);
        } else if (hand.complete()) {
            summarise(hand.placed(), hand.attention(), 0);
        } else {
            status(hand.status());
        }
    }

    private void finishPaste() {
        summarise(paste.placed(), paste.attention(), paste.omitted());
    }

    private void summarise(int placed, int attention, int omitted) {
        if (attention > 0) {
            finish(false, tr("simpleschematics.print.finished_attention", placed, attention));
        } else if (omitted > 0) {
            finish(false, tr("simpleschematics.print.finished_omitted", placed, omitted));
        } else {
            finish(true, tr("simpleschematics.print.complete", placed));
        }
    }

    /** One line above the hotbar every couple of seconds, never chat. */
    private void status(Component line) {
        if (++statusTicks < STATUS_TICKS) return;
        statusTicks = 0;
        info(line);
    }

    // ---- stopping ---------------------------------------------------------

    private void finish(boolean success, Component message) {
        reset();
        if (success) Feedback.success(message);
        else info(message);
    }

    public void pause() {
        if (!isRunning()) return;
        reset();
        info(tr("simpleschematics.print.paused"));
    }

    public void reset() {
        Minecraft mc = Minecraft.getInstance();
        generation++;
        if (hand != null && mc.player != null && mc.level == level) hand.writeOff(mc);
        inventory.close(mc);
        if (paste != null) paste.cancel();
        if (phase == Phase.CONFIRMING && mc.screen instanceof PrintConfirmScreen) mc.setScreen(null);
        paste = null;
        hand = null;
        survey = null;
        plan = null;
        phase = Phase.IDLE;
    }

    /** Leaving the world: nothing already agreed carries over to the next one. */
    public void leaveWorld() {
        reset();
        confirmed.clear();
        rulesAcknowledged = false;
        PrintChat.forgetServer();
    }

    // ---- shared helpers ---------------------------------------------------

    public static boolean suppressPlacementSound() {
        return HandPrinter.applying() && !SSConfig.INSTANCE.printSounds.get();
    }

    static MaterialResolver.Cost remainingCost(BlockState desired, BlockState actual) {
        Map<Item, Integer> cost = new LinkedHashMap<>();
        MaterialResolver.costOf(desired).addTo(cost);
        for (var entry : MaterialResolver.standing(desired, actual).entries()) {
            cost.computeIfPresent(entry.item(), (item, count) -> Math.max(0, count - entry.amount()));
        }
        return new MaterialResolver.Cost(cost.entrySet().stream().filter(e -> e.getValue() > 0)
                .map(e -> new MaterialResolver.Cost.Entry(e.getKey(), e.getValue())).toList());
    }

    static boolean isObstruction(BlockState wanted, BlockState actual) {
        return !actual.canBeReplaced() && !PrintPlacement.matches(wanted, actual) && !PrintPlacement.isPartial(wanted, actual);
    }

    static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    private static void info(Component message) {
        Feedback.value(tr("simpleschematics.print.title"), message);
    }

    private static void error(String key) {
        Feedback.error(tr(key));
    }
}
