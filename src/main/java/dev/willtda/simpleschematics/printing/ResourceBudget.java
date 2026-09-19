package dev.willtda.simpleschematics.printing;

import java.util.HashMap;
import java.util.Map;

/** Tracks materials actually withdrawn when the selected source excludes existing inventory. */
public final class ResourceBudget<T> {
    private final Map<T, Integer> withdrawn = new HashMap<>();
    private final Map<T, Integer> reserved = new HashMap<>();

    public void reserveExisting(Map<T, Integer> inventory) {
        if (reserved.isEmpty()) inventory.forEach((item, count) -> reserved.put(item, Math.max(0, count)));
    }

    public void credit(T item, int amount) {
        if (amount > 0) withdrawn.merge(item, amount, Integer::sum);
    }

    public int available(T item, int inInventory, boolean chestsOnly) {
        int spendable = Math.max(0, inInventory - reserved.getOrDefault(item, 0));
        return Math.max(0, chestsOnly ? Math.min(spendable, withdrawn.getOrDefault(item, 0)) : inInventory);
    }

    public void spend(T item, int amount) {
        if (amount > 0) withdrawn.computeIfPresent(item, (key, count) -> Math.max(0, count - amount));
    }

    public static <T> int missing(Map<T, Integer> required, Map<T, Integer> available) {
        long total = 0;
        for (var entry : required.entrySet()) {
            total += Math.max(0L, (long) entry.getValue() - available.getOrDefault(entry.getKey(), 0));
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }
}
