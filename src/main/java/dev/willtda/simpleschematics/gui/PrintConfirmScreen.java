package dev.willtda.simpleschematics.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The one question Print asks before it starts, and only when there is
 * something worth asking.
 *
 * <p>It used to ask up to seven times in a row: whether to continue, the
 * server rules, missing materials, replacing blocks, chest estimates, saved
 * data. Now everything that applies is one list on one screen, and it only
 * appears at all when something needs a decision. Don't Ask Again switches the
 * warnings that can be switched off, the same settings the Print section
 * holds.</p>
 */
public final class PrintConfirmScreen extends Screen {

    public enum Choice { PRINT, PRINT_AND_SILENCE, CANCEL }

    private final List<Component> notes;
    private final boolean offerSilence;
    private final Consumer<Choice> onChoice;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private PrintConfirmLayout layout;
    private Checkbox silence;
    private boolean answered;

    public PrintConfirmScreen(Component title, List<Component> notes, boolean offerSilence, Consumer<Choice> onChoice) {
        super(title);
        this.notes = List.copyOf(notes);
        this.offerSilence = offerSilence;
        this.onChoice = onChoice;
    }

    @Override
    protected void init() {
        int textWidth = PrintConfirmLayout.of(this.width, this.height, 1, offerSilence).textWidth();
        lines.clear();
        for (int i = 0; i < notes.size(); i++) {
            if (i > 0) {
                lines.add(FormattedCharSequence.EMPTY);
            }
            lines.addAll(this.font.split(FormattedText.composite(Component.literal("• "), notes.get(i)), textWidth));
        }
        layout = PrintConfirmLayout.of(this.width, this.height, lines.size(), offerSilence);

        if (offerSilence) {
            Component label = Component.translatable("simpleschematics.print.dont_ask");
            int boxWidth = Math.min(layout.textWidth(), 24 + this.font.width(label));
            boolean was = silence != null && silence.selected();
            silence = Screens.checkbox(this.font, (this.width - boxWidth) / 2, layout.checkboxY(), boxWidth,
                    PrintConfirmLayout.BUTTON_HEIGHT, label, was);
            silence.setTooltip(Tooltip.create(Component.translatable("simpleschematics.tip.print.dont_ask")));
            addRenderableWidget(silence);
        }
        Button print = addRenderableWidget(Button.builder(Component.translatable("simpleschematics.gui.print"),
                        b -> answer(silence != null && silence.selected() ? Choice.PRINT_AND_SILENCE : Choice.PRINT))
                .bounds(layout.printX(), layout.buttonsY(), layout.buttonWidth(), PrintConfirmLayout.BUTTON_HEIGHT)
                .build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> answer(Choice.CANCEL))
                .bounds(layout.cancelX(), layout.buttonsY(), layout.buttonWidth(), PrintConfirmLayout.BUTTON_HEIGHT)
                .build());
        setInitialFocus(print);
    }

    private void answer(Choice choice) {
        if (answered) {
            return;
        }
        answered = true;
        onChoice.accept(choice);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Screens.background(this, graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, layout.titleY(), 0xFFFFFFFF);
        int y = layout.notesY();
        for (int i = 0; i < layout.visibleLines(); i++) {
            boolean last = i == layout.visibleLines() - 1;
            if (last && layout.truncated()) {
                graphics.drawString(this.font, "…", layout.textX(), y, 0xFFD1D5DB, false);
            } else {
                graphics.drawString(this.font, lines.get(i), layout.textX(), y, 0xFFD1D5DB, false);
            }
            y += PrintConfirmLayout.LINE;
        }
        Screens.widgets(this, graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        answer(Choice.CANCEL);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
