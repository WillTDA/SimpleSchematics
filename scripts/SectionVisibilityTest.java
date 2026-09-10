import dev.willtda.simpleschematics.render.SectionVisibility;
import java.util.BitSet;

/** Regression checks for exposed faces at layer and section boundaries. */
public final class SectionVisibilityTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // An asymmetrical volume catches swapped axes and palette index order.
        int w = 19, h = 35, l = 23;
        BitSet hidden = new BitSet();
        hidden.set((16 * l + 7) * w + 15);
        check(!SectionVisibility.visible(15, 16, 7, w, h, l, 0, h - 1, hidden), "Placed block must be hidden");
        check(SectionVisibility.visible(16, 16, 7, w, h, l, 0, h - 1, hidden), "Neighbour must remain visible");
        check(SectionVisibility.visible(15, 16, 7, w, h, l, 0, h - 1, null), "Clearing mask must restore block");
        check(SectionVisibility.visible(3, 16, 7, w, h, l, 16, 16, null), "Selected layer must be visible");
        check(!SectionVisibility.visible(3, 15, 7, w, h, l, 16, 16, null), "Bottom cut face needs an air neighbour");
        check(!SectionVisibility.visible(3, 17, 7, w, h, l, 16, 16, null), "Top cut face needs an air neighbour");
        check(!SectionVisibility.visible(-1, 0, 0, w, h, l, 0, h - 1, null), "Outside volume is air");
        check(!SectionVisibility.visible(w, 0, 0, w, h, l, 0, h - 1, null), "Positive boundary is air");

        // Validate topology independently with coordinate Manhattan distance.
        int sw = 3, sh = 4, sl = 2, total = sw * sh * sl;
        for (int i = 0; i < total; i++) {
            BitSet changed = new BitSet();
            changed.set(i);
            BitSet result = SectionVisibility.withNeighbours(changed, sw, sh, sl);
            for (int j = 0; j < total; j++) {
                int distance = Math.abs(i % sw - j % sw)
                        + Math.abs(i / sw % sl - j / sw % sl)
                        + Math.abs(i / (sw * sl) - j / (sw * sl));
                check(result.get(j) == (distance <= 1), "Incorrect section neighbour: " + i + " to " + j);
            }
            check(changed.cardinality() == 1, "Input change mask must not be mutated");
            check(result.length() <= total, "Section indices must stay within the volume");
        }
        BitSet changed = new BitSet();
        changed.set(0);
        changed.set(total - 1);
        check(SectionVisibility.withNeighbours(changed, sw, sh, sl).cardinality() == 8,
                "Opposite corner changes must retain both sets of neighbours");
        System.out.println("PASS: hidden blocks, layer cuts, volume bounds and all 24 section neighbourhoods");
    }
}
