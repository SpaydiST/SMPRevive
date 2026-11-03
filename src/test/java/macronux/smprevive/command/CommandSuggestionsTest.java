package macronux.smprevive.command;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.junit.Test;
import static org.junit.Assert.*;

public class CommandSuggestionsTest {
    @Test public void emptyPaperArgumentsOfferFirstLevelSuggestions() {
        assertTrue(CommandSuggestions.complete("revive", new String[0], List.of()).contains("menu"));
        assertEquals(List.of("start", "preview", "status", "stop", "newbie"), CommandSuggestions.complete("event", new String[0], List.of()));
        assertEquals(List.of("Alice"), CommandSuggestions.complete("reviveitem", new String[0], List.of("Alice")));
    }

    @Test public void newcomerCommandSuggestions() {
        assertEquals(List.of("start", "particles"), CommandSuggestions.complete("event", new String[]{"newbie", ""}, List.of()));
        assertEquals(List.of("0"), CommandSuggestions.complete("event", new String[]{"newbie", "start", ""}, List.of()));
        assertEquals(List.of("stop", "0"), CommandSuggestions.complete("event", new String[]{"newbie", "particles", ""}, List.of()));
        assertEquals(List.of("stop"), CommandSuggestions.complete("event", new String[]{"newbie", "particles", "s"}, List.of()));
        assertEquals(List.of("0"), CommandSuggestions.complete("event", new String[]{"newbie", "particles", "0", ""}, List.of()));
    }

    @Test public void routesOnlyValidArgumentPositions() {
        assertEquals(List.of("Alice"), CommandSuggestions.complete("revive", new String[]{"status", "Al"}, List.of("Alice", "Bob")));
        assertEquals(List.of("give", "remove", "list"), CommandSuggestions.complete("revive", new String[]{"allergy", ""}, List.of()));
        assertEquals(List.of(), CommandSuggestions.complete("event", new String[]{"start", ""}, List.of("Alice")));
        assertEquals(List.of(), CommandSuggestions.complete("reviveitem", new String[]{"Alice", ""}, List.of("Alice")));
        assertEquals(List.of(), CommandSuggestions.complete("revive", new String[]{"bogus", ""}, List.of("Alice")));
        assertEquals(List.of("hard"), CommandSuggestions.complete("revive", new String[]{"difficulty", "h"}, List.of()));
    }

    @Test public void matchingIsIndependentOfServerLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(CommandSuggestions.complete("REVIVE", new String[]{"DI"}, List.of()).contains("difficulty"));
        } finally { Locale.setDefault(previous); }
    }

    @Test public void deniedSenderCannotSeeSuggestionsOrUseBasicCommand() throws Exception {
        CommandSender denied = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class},
            (proxy, method, args) -> {
                if (method.getName().equals("hasPermission")) return false;
                throw new AssertionError("Denied suggestions must not access other server data: " + method);
            });
        Commands commands = new Commands(null);
        var method = Commands.class.getDeclaredMethod("complete", CommandSender.class, String.class, String[].class);
        method.setAccessible(true);
        assertEquals(List.of(), method.invoke(commands, denied, "revive", new String[0]));
        assertFalse(commands.asBasicCommand("revive").canUse(denied));
        assertEquals("smprevive.admin", commands.asBasicCommand("revive").permission());
    }
    @Test public void adminRecoveryRoutesHaveCorrectPositions() {
        assertTrue(CommandSuggestions.complete("revive", new String[]{"pen"}, List.of()).contains("pending"));
        assertEquals(List.of("Alice"), CommandSuggestions.complete("revive", new String[]{"recoverhead", "Al"}, List.of("Alice")));
        assertEquals(List.of("Bob"), CommandSuggestions.complete("revive", new String[]{"recoverhead", "Alice", "B"}, List.of("Bob")));
        assertEquals(List.of("refund", "complete"), CommandSuggestions.complete("revive", new String[]{"resolvepayment", "id", ""}, List.of()));
        assertEquals(List.of(), CommandSuggestions.complete("revive", new String[]{"pending", ""}, List.of("Bob")));
    }

}
