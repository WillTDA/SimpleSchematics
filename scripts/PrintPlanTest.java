package dev.willtda.simpleschematics.printing;

import dev.willtda.simpleschematics.gui.PrintConfirmLayout;
import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Checks the print plan, fill boxes, resume journal, chat filter and confirmation layout. */
public final class PrintPlanTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        PrintTestBootstrap.initialise();
        plan();
        boxes();
        journal();
        chat();
        layout();
        System.out.println("Print plan checks passed: " + checks);
    }

    private static void plan() {
        Schematic.Builder builder = new Schematic.Builder(5, 3, 4);
        Random random = new Random(7);
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
        BlockState[] choices = {Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), stairs};
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 5; x++) {
                    builder.set(x, y, z, choices[random.nextInt(choices.length)]);
                }
            }
        }
        Schematic schematic = builder.build();
        BlockPos origin = new BlockPos(-40, 70, 913);
        Placement placement = new Placement("plan.sschem", "Plan", origin);
        for (Mirror mirror : Mirror.values()) {
            for (Rotation rotation : Rotation.values()) {
                placement.setMirror(mirror);
                placement.setRotation(rotation);
                PrintPlan plan = PrintPlan.of(placement, schematic, "test");
                check(plan.size() == schematic.blockCount(), "The plan must list every block and nothing else");
                int previous = -1;
                BlockPos.MutableBlockPos world = new BlockPos.MutableBlockPos();
                for (int i = 0; i < plan.size(); i++) {
                    int index = plan.target(i);
                    check(index > previous, "Blocks must stay in storage order, lowest layer first");
                    previous = index;
                    int x = plan.localX(index);
                    int y = plan.localY(index);
                    int z = plan.localZ(index);
                    check(schematic.index(x, y, z) == index, "Local coordinates must round trip");
                    BlockState local = schematic.getBlockState(x, y, z);
                    check(!local.isAir(), "Air must never be a target");
                    check(plan.wanted(index).equals(local.mirror(mirror).rotate(rotation)),
                            "Wanted states must follow the mirror then the rotation");
                    check(plan.world(index, world).equals(placement.toWorld(schematic, x, y, z)),
                            "Plan positions must agree with the placement for " + mirror + "/" + rotation);
                }
                check(!plan.moved(), "A fresh plan matches its placement");
            }
        }
        PrintPlan plan = PrintPlan.of(placement, schematic, "test");
        placement.move(1, 0, 0);
        check(plan.moved(), "Moving the placement must be noticed");
    }

    private static void boxes() {
        Random random = new Random(11);
        for (int round = 0; round < 300; round++) {
            int width = 1 + random.nextInt(9);
            int height = 1 + random.nextInt(6);
            int length = 1 + random.nextInt(9);
            int volume = width * height * length;
            int kinds = 1 + random.nextInt(3);
            int[] cells = new int[volume];
            for (int i = 0; i < volume; i++) {
                cells[i] = random.nextInt(kinds + 1);
            }
            int limit = round % 5 == 0 ? 1 + random.nextInt(6) : 32768;
            BitSet done = new BitSet(volume);
            int commands = 0;
            for (int start = 0; start < volume; start++) {
                if (cells[start] == 0 || done.get(start)) continue;
                int kind = cells[start];
                int[] box = CuboidPlanner.grow(width, height, length, start,
                        other -> cells[other] == kind && !done.get(other), limit);
                check(CuboidPlanner.volume(box) <= limit, "A box must respect the server's fill limit");
                check(box[0] <= box[3] && box[1] <= box[4] && box[2] <= box[5], "A box must not be inside out");
                check(CuboidPlanner.index(width, length, box[0], box[1], box[2]) == start,
                        "A box must start where it was asked to");
                check(box[3] < width && box[4] < height && box[5] < length, "A box must stay inside the schematic");
                for (int y = box[1]; y <= box[4]; y++) {
                    for (int z = box[2]; z <= box[5]; z++) {
                        for (int x = box[0]; x <= box[3]; x++) {
                            int index = CuboidPlanner.index(width, length, x, y, z);
                            check(cells[index] == kind, "A box may only hold the block it started with");
                            check(!done.get(index), "No block may be placed twice");
                            check(index >= start, "A box never reaches back over a block that has had its turn");
                            done.set(index);
                        }
                    }
                }
                commands++;
            }
            for (int i = 0; i < volume; i++) {
                check((cells[i] != 0) == done.get(i), "Every block, and only blocks, must be covered");
            }
            int blocks = 0;
            for (int cell : cells) if (cell != 0) blocks++;
            check(commands <= blocks, "Merging must never need more commands than blocks");
        }
        int[] wall = CuboidPlanner.grow(40, 20, 3, 0, other -> true, 32768);
        check(CuboidPlanner.volume(wall) == 2400, "A solid block of one material is one command");
        int[] capped = CuboidPlanner.grow(40, 40, 40, 0, other -> true, 32768);
        check(CuboidPlanner.volume(capped) <= 32768 && CuboidPlanner.volume(capped) >= 32000,
                "A huge solid block is split close to the fill limit");
    }

    private static void journal() throws Exception {
        Path folder = Files.createTempDirectory("print-journal-");
        Path file = folder.resolve("nested").resolve("world.txt");
        UUID legacy = UUID.randomUUID();
        UUID chest = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        Files.createDirectories(file.getParent());
        Files.writeString(file, legacy + System.lineSeparator() + "not a uuid" + System.lineSeparator());
        PrintJournal journal = PrintJournal.open(file);
        check(journal.readable(), "An existing journal must be readable");
        check(journal.step(legacy) == PrintJournal.Step.ATTEMPTED, "Bare ids from older journals read as attempted");
        check(journal.record(chest, PrintJournal.Step.PLACED), "Recording a placed container must succeed");
        check(journal.record(entity, PrintJournal.Step.ATTEMPTED), "Recording a summon must succeed");
        check(journal.record(entity, PrintJournal.Step.DONE), "Recording finished data must succeed");
        check(journal.record(entity, PrintJournal.Step.ATTEMPTED), "A late earlier step is still written");
        PrintJournal reread = PrintJournal.open(file);
        check(reread.step(chest) == PrintJournal.Step.PLACED, "A placed container survives a restart unfilled");
        check(reread.step(entity) == PrintJournal.Step.DONE, "A later step outranks an earlier one on reading");
        check(reread.step(UUID.randomUUID()) == null, "Unknown ids have no step");
        check(reread.recordAll(List.of(), PrintJournal.Step.DONE), "Recording nothing succeeds");

        Path directory = folder.resolve("a-directory.txt");
        Files.createDirectories(directory);
        PrintJournal broken = PrintJournal.open(directory);
        check(!broken.readable(), "A journal that cannot be read must say so");
        check(!broken.record(UUID.randomUUID(), PrintJournal.Step.ATTEMPTED),
                "Nothing may be summoned without a journal to remember it");
    }

    private static void chat() throws Exception {
        Method translatable = PrintChat.class.getDeclaredMethod("translatable", Component.class, int.class);
        translatable.setAccessible(true);
        Component success = Component.translatable("commands.fill.success", 12);
        Component failure = Component.empty().append(Component.translatable("commands.fill.toobig", 4096, 9000));
        Component plain = Component.literal("A protection plugin says no");
        check(key(translatable.invoke(null, success, 0)).equals("commands.fill.success"), "Direct replies are recognised");
        check(key(translatable.invoke(null, failure, 0)).equals("commands.fill.toobig"),
                "Failures wrapped in an empty component are recognised");
        check(translatable.invoke(null, plain, 0) == null, "Plain text is never mistaken for a command reply");

        Method learn = PrintChat.class.getDeclaredMethod("learnLimit", Object.class);
        learn.setAccessible(true);
        PrintChat.forgetServer();
        learn.invoke(null, 4096);
        check(PrintChat.fillLimit() == 4096, "A lower fill limit is learnt from the server's reply");
        learn.invoke(null, Component.literal("8192"));
        check(PrintChat.fillLimit() == 4096, "A larger figure never raises the learnt limit");
        PrintChat.forgetServer();
        check(PrintChat.fillLimit() == 0, "Leaving the server forgets its limit");
    }

    private static String key(Object contents) {
        return contents instanceof TranslatableContents translatable ? translatable.getKey() : "";
    }

    private static void layout() {
        List<int[]> sizes = new ArrayList<>();
        for (int width : new int[]{320, 360, 427, 480, 640, 854, 960, 1280}) {
            for (int height : new int[]{240, 270, 300, 360, 480, 540}) {
                sizes.add(new int[]{width, height});
            }
        }
        // The real window sizes at every GUI scale that fits them.
        for (int[] screen : new int[][]{{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}}) {
            for (int scale = 1; scale <= 6; scale++) {
                int width = screen[0] / scale;
                int height = screen[1] / scale;
                if (width >= 320 && height >= 240) sizes.add(new int[]{width, height});
            }
        }
        for (int[] size : sizes) {
            int width = size[0];
            int height = size[1];
            for (int lines = 1; lines <= 40; lines++) {
                for (boolean checkbox : new boolean[]{false, true}) {
                    PrintConfirmLayout layout = PrintConfirmLayout.of(width, height, lines, checkbox);
                    String label = width + "x" + height + " lines " + lines + (checkbox ? " with box" : "");
                    int button = PrintConfirmLayout.BUTTON_HEIGHT;
                    check(layout.textX() >= 0 && layout.textX() + layout.textWidth() <= width, "Text fits across " + label);
                    check(layout.titleY() >= 0, "Title is on screen " + label);
                    check(layout.notesY() >= layout.titleY() + PrintConfirmLayout.LINE, "Notes clear the title " + label);
                    int notesBottom = layout.notesY() + layout.visibleLines() * PrintConfirmLayout.LINE;
                    int nextTop = checkbox ? layout.checkboxY() : layout.buttonsY();
                    check(notesBottom <= nextTop, "Notes clear the controls " + label);
                    if (checkbox) {
                        check(layout.checkboxY() + button <= layout.buttonsY(), "Checkbox clears the buttons " + label);
                    }
                    check(layout.buttonsY() + button <= height, "Buttons stay on screen " + label);
                    check(layout.buttonWidth() > 0 && layout.printX() >= 0, "Print button fits " + label);
                    check(layout.printX() + layout.buttonWidth() < layout.cancelX(), "Buttons do not overlap " + label);
                    check(layout.cancelX() + layout.buttonWidth() <= width, "Cancel button fits " + label);
                    check(layout.visibleLines() >= 1 && layout.visibleLines() <= lines, "Some notes always show " + label);
                    check(layout.truncated() == (layout.visibleLines() < lines), "Cut notes are marked " + label);
                    if (lines <= 12) {
                        check(!layout.truncated(), "A normal set of notes fits without cutting " + label);
                    }
                }
            }
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
