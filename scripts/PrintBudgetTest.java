import java.util.Map;
import dev.willtda.simpleschematics.printing.ResourceBudget;

/** Regression checks for partial builds and chest-only material provenance. */
public class PrintBudgetTest {
    public static void main(String[] args) {
        ResourceBudget<String> budget = new ResourceBudget<>();
        require(budget.available("stone", 64, true) == 0, "Chest-only spent existing inventory");
        budget.credit("stone", 12);
        require(budget.available("stone", 76, true) == 12, "Withdrawn stack lost provenance");
        budget.spend("stone", 5);
        require(budget.available("stone", 71, true) == 7, "Confirmed placement was not debited");
        require(budget.available("stone", 2, true) == 2, "Missing live items remained spendable");
        require(budget.available("stone", 71, false) == 71, "Both sources ignored inventory");
        budget.spend("stone", 100);
        budget.credit("stone", -20);
        require(budget.available("stone", 71, true) == 0, "Invalid transfers created credit");
        require(ResourceBudget.missing(Map.of("stone", 10, "torch", 8), Map.of("stone", 40, "torch", 3)) == 5,
                "Surplus of one material concealed another shortage");
        require(ResourceBudget.missing(Map.of("stone", Integer.MAX_VALUE, "torch", Integer.MAX_VALUE), Map.of())
                == Integer.MAX_VALUE, "A large schematic overflowed the missing-material warning");
        require(ResourceBudget.missing(Map.of(), Map.of("stone", 12)) == 0, "Completed build still had a shortage");
        ResourceBudget<String> resumed = new ResourceBudget<>();
        resumed.reserveExisting(Map.of("stone", 64));
        resumed.credit("stone", 12);
        require(resumed.available("stone", 76, true) == 12, "Resume lost withdrawn materials");
        require(resumed.available("stone", 68, true) == 4, "Discarded withdrawals exposed original supplies");
        require(resumed.available("stone", 64, true) == 0, "Resume spent original inventory after withdrawals disappeared");
        System.out.println("PASS: Print material budgets, shortages and chest-only provenance");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
