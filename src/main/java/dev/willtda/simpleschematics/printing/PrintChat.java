package dev.willtda.simpleschematics.printing;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.Set;

/**
 * Keeps the paste's own command replies out of chat.
 *
 * <p>Every setblock, fill, data and summon a Creative paste sends is answered
 * with a line in chat, so a large build flooded it with thousands of "Changed
 * the block" messages. While a paste is sending, and for a short while after
 * so late replies are caught, those replies are dropped. Only the replies the
 * paste's own commands produce are recognised; anything else, a protection
 * plugin explaining itself say, still reaches chat.</p>
 *
 * <p>Other operators may still see the server's admin echo of each command.
 * That is sent to them, not to this client, so it cannot be filtered here;
 * fewer commands, through larger fills, is what keeps it down.</p>
 */
public final class PrintChat {

    private static final long QUIET_MILLIS = 10_000L;

    /** Replies that mean a command did what it was asked, or found it already done. */
    private static final Set<String> ROUTINE = Set.of(
            "commands.setblock.success", "commands.setblock.failed",
            "commands.fill.success", "commands.fill.failed",
            "commands.data.block.modified", "commands.data.entity.modified", "commands.data.merge.failed",
            "commands.summon.success", "commands.summon.failed.uuid",
            "commands.execute.conditional.fail");

    /** Replies that mean the server would not do it. Counted, and still kept out of chat. */
    private static final Set<String> REFUSED = Set.of(
            "commands.fill.toobig", "commands.summon.failed", "commands.summon.invalidPosition",
            "commands.data.block.invalid", "commands.data.entity.invalid", "arguments.nbtpath.nothing_found",
            "argument.pos.unloaded", "argument.pos.outofworld", "argument.entity.notfound.entity",
            "command.unknown.command", "command.unknown.argument", "command.context.here",
            "chat.disabled.options");

    private static long quietUntil;
    private static int refused;
    private static int fillLimit;

    private PrintChat() {
    }

    /** Called before each command the paste sends. */
    static void expect() {
        quietUntil = System.currentTimeMillis() + QUIET_MILLIS;
    }

    /** Refusals since the last reset, for the summary at the end of a paste. */
    static int refused() {
        return refused;
    }

    /** The server's fill limit, once it has told us, or zero. */
    static int fillLimit() {
        return fillLimit;
    }

    static void reset() {
        refused = 0;
    }

    /** Another server may allow larger fills, so what this one said is forgotten on leaving. */
    static void forgetServer() {
        refused = 0;
        fillLimit = 0;
        quietUntil = 0;
    }

    /**
     * Asked by the loader for every system message before it reaches chat.
     *
     * @return true to keep it out of chat
     */
    public static boolean shouldHide(Component message, boolean overlay) {
        if (overlay || System.currentTimeMillis() > quietUntil) {
            return false;
        }
        TranslatableContents reply = translatable(message, 0);
        if (reply == null) {
            return false;
        }
        String key = reply.getKey();
        if (ROUTINE.contains(key)) {
            return true;
        }
        if (REFUSED.contains(key)) {
            // A fill that was too large is retried smaller, and the context line
            // repeats the error before it, so neither counts as a refusal.
            if (!key.equals("command.context.here") && !key.equals("commands.fill.toobig")) {
                refused++;
            }
            if (key.equals("commands.fill.toobig") && reply.getArgs().length > 0) {
                learnLimit(reply.getArgs()[0]);
            }
            return true;
        }
        return false;
    }

    /** Failures arrive wrapped in an empty red component, so the key can sit one level down. */
    private static TranslatableContents translatable(Component component, int depth) {
        if (component.getContents() instanceof TranslatableContents contents) {
            return contents;
        }
        if (depth >= 2) {
            return null;
        }
        for (Component sibling : component.getSiblings()) {
            TranslatableContents found = translatable(sibling, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void learnLimit(Object value) {
        try {
            int limit = Integer.parseInt(value instanceof Component text ? text.getString() : String.valueOf(value));
            if (limit > 0 && (fillLimit == 0 || limit < fillLimit)) {
                fillLimit = limit;
            }
        } catch (NumberFormatException ignored) {
            // Not a number we can use; the next wave tries again at the same size.
        }
    }
}
