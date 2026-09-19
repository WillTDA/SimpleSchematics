package dev.willtda.simpleschematics.printing;

import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.placement.PlacementManager;
import dev.willtda.simpleschematics.client.InputHandler;
import dev.willtda.simpleschematics.resource.Banks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

import java.util.*;

/** Normal menu clicks only: cached chest counts never create spendable materials. */
final class PrintInventory {
    final ResourceBudget<Item> budget;
    private final Set<BlockPos> visited = new HashSet<>();
    private BlockPos opening;
    private AbstractContainerMenu ownedMenu;
    private int wait;
    private Item transferring;
    private int beforeTransfer;
    private int stateBeforeTransfer;
    private int transferTimeout;
    private int oldSlot = -1;
    private int swappedFrom = -1;
    private ItemStack creativeOriginal;

    PrintInventory() { this(new ResourceBudget<>()); }

    PrintInventory(ResourceBudget<Item> budget) { this.budget = budget; }

    static int count(Minecraft mc, Item item) {
        int total = 0;
        for (ItemStack stack : mc.player.getInventory().items) {
            if (stack.is(item) && usable(stack, mc.player.isCreative())) total += stack.getCount();
        }
        if (mc.player.getOffhandItem().is(item) && usable(mc.player.getOffhandItem(), mc.player.isCreative())) {
            total += mc.player.getOffhandItem().getCount();
        }
        return total;
    }

    private static boolean usable(ItemStack stack, boolean creative) {
        return !stack.isEmpty() && stack.getTagElement(BlockItem.BLOCK_STATE_TAG) == null
                && (creative || stack.getTagElement(BlockItem.BLOCK_ENTITY_TAG) == null);
    }

    static ItemStack eligibleStack(Minecraft mc, Item item, boolean creative) {
        for (ItemStack stack : mc.player.getInventory().items) {
            if (stack.is(item) && usable(stack, creative)) return stack;
        }
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.is(item) && usable(offhand, creative)) return offhand;
        return creative ? new ItemStack(item) : ItemStack.EMPTY;
    }

    static Map<Item, Integer> counts(Minecraft mc) {
        Map<Item, Integer> result = new HashMap<>();
        for (ItemStack stack : mc.player.getInventory().items) {
            if (usable(stack, mc.player.isCreative())) result.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        ItemStack offhand = mc.player.getOffhandItem();
        if (usable(offhand, mc.player.isCreative())) result.merge(offhand.getItem(), offhand.getCount(), Integer::sum);
        return result;
    }

    static boolean reachable(Minecraft mc, BlockPos pos) {
        double range = mc.player.getBlockReach();
        return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= range * range;
    }

    boolean ownsScreen(Minecraft mc) {
        return opening != null && mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu
                && mc.screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu() == mc.player.containerMenu
                && (ownedMenu == null || ownedMenu == screen.getMenu())
                && sameBank(mc);
    }

    private boolean sameBank(Minecraft mc) {
        BlockPos last = InputHandler.lastUsedBlock();
        return opening != null && mc.level != null && last != null
                && Banks.canonical(mc.level, opening).equals(Banks.canonical(mc.level, last));
    }

    boolean busy() { return opening != null; }

    void retryBanks() { visited.clear(); }

    /** Returns true while a reachable linked storage menu is being fetched. */
    boolean restock(Minecraft mc, Placement placement, Map<Item, Integer> required, boolean chestsOnly) {
        if (opening == null) {
            boolean needed = required.entrySet().stream().anyMatch(e ->
                    budget.available(e.getKey(), count(mc, e.getKey()), chestsOnly) < e.getValue());
            if (!needed || mc.screen != null || mc.player.containerMenu != mc.player.inventoryMenu) return false;
            for (BlockPos pos : placement.bankPositions()) {
                if (visited.contains(pos) || !mc.level.hasChunkAt(pos) || !reachable(mc, pos)
                        || !Banks.isContainer(mc.level, pos)) continue;
                BlockHitResult hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), Vec3.atCenterOf(pos),
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) continue;
                visited.add(pos);
                opening = pos;
                wait = responseTicks(mc);
                InputHandler.recordUsedBlock(pos);
                // Use the real block and face, so locks and server protections still apply.
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                return true;
            }
            return false;
        }
        if (!sameBank(mc)) {
            opening = null;
            ownedMenu = null;
            transferring = null;
            return false;
        }
        if (mc.screen == null) InputHandler.recordUsedBlock(opening);
        if (--wait > 0) return true;
        if (mc.player.containerMenu == mc.player.inventoryMenu || !ownsScreen(mc)) {
            opening = null;
            ownedMenu = null;
            return true;
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        // Crafting and machine output slots are not general purpose material banks.
        if (!(menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu
                || menu instanceof HopperMenu || menu instanceof DispenserMenu)) {
            close(mc);
            return true;
        }
        if (ownedMenu != null && ownedMenu != menu) {
            opening = null;
            ownedMenu = null;
            return true;
        }
        ownedMenu = menu;
        if (!menu.getCarried().isEmpty()) {
            close(mc);
            return true;
        }
        if (transferring != null) {
            if (menu.getStateId() == stateBeforeTransfer) {
                if (--transferTimeout > 0) { wait = 1; return true; }
                close(mc);
                return true;
            }
            int received = Math.max(0, count(mc, transferring) - beforeTransfer);
            budget.credit(transferring, received);
            transferring = null;
            if (received == 0) {
                snapshot(mc, placement, menu);
                close(mc);
                return true;
            }
        }
        snapshot(mc, placement, menu);
        for (Slot slot : menu.slots) {
            if (slot.container == mc.player.getInventory() || !slot.hasItem() || !slot.mayPickup(mc.player)
                    || !usable(slot.getItem(), false)) continue;
            Item item = slot.getItem().getItem();
            if (budget.available(item, count(mc, item), chestsOnly) >= required.getOrDefault(item, 0)) continue;
            transferring = item;
            beforeTransfer = count(mc, item);
            stateBeforeTransfer = menu.getStateId();
            transferTimeout = responseTicks(mc) * 4;
            mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, mc.player);
            wait = responseTicks(mc);
            return true;
        }
        close(mc);
        return true;
    }

    private void snapshot(Minecraft mc, Placement placement, AbstractContainerMenu menu) {
        Map<String, Integer> contents = new LinkedHashMap<>();
        for (Slot slot : menu.slots) {
            if (slot.container == mc.player.getInventory() || !slot.hasItem()) continue;
            ItemStack stack = slot.getItem();
            contents.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        placement.setBankContents(opening, contents);
        PlacementManager.INSTANCE.markDirty();
    }

    void close(Minecraft mc) {
        if (mc.player != null && ownsScreen(mc)) mc.player.closeContainer();
        opening = null;
        ownedMenu = null;
        transferring = null;
    }

    /** The caller restores the slot in a finally block after the use packet. */
    InteractionHand equip(Minecraft mc, Item item, boolean creative) {
        oldSlot = mc.player.getInventory().selected;
        if (mc.player.getOffhandItem().is(item) && usable(mc.player.getOffhandItem(), creative)) return InteractionHand.OFF_HAND;
        for (int i = 0; i < 36; i++) {
            if (!mc.player.getInventory().getItem(i).is(item) || !usable(mc.player.getInventory().getItem(i), creative)) continue;
            if (i < 9) {
                mc.player.getInventory().selected = i;
            } else {
                swappedFrom = i;
                mc.gameMode.handleInventoryMouseClick(0, i, oldSlot, ClickType.SWAP, mc.player);
            }
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().selected));
            return InteractionHand.MAIN_HAND;
        }
        if (!creative) return null;
        creativeOriginal = mc.player.getInventory().getSelected().copy();
        ItemStack stack = new ItemStack(item);
        mc.player.getInventory().setItem(oldSlot, stack);
        mc.gameMode.handleCreativeModeItemAdd(stack, 36 + oldSlot);
        return InteractionHand.MAIN_HAND;
    }

    void restore(Minecraft mc) {
        if (oldSlot < 0) return;
        if (creativeOriginal != null) {
            mc.player.getInventory().setItem(oldSlot, creativeOriginal);
            mc.gameMode.handleCreativeModeItemAdd(creativeOriginal, 36 + oldSlot);
            creativeOriginal = null;
        }
        if (swappedFrom >= 0) {
            mc.gameMode.handleInventoryMouseClick(0, swappedFrom, oldSlot, ClickType.SWAP, mc.player);
            swappedFrom = -1;
        }
        mc.player.getInventory().selected = oldSlot;
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(oldSlot));
        oldSlot = -1;
    }

    static int responseTicks(Minecraft mc) {
        var info = mc.player.connection.getPlayerInfo(mc.player.getUUID());
        return Math.max(12, Math.min(200, 12 + (info == null ? 0 : info.getLatency() / 25)));
    }
}
