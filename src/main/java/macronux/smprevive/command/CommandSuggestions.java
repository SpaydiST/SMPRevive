package macronux.smprevive.command;

import java.util.List;
import java.util.Locale;

/** Pure argument routing, including Paper's empty argument array. */
public final class CommandSuggestions {
    private CommandSuggestions() {}

    public static List<String> complete(String commandName, String[] args, List<String> players) {
        String command = commandName.toLowerCase(Locale.ROOT);
        int count = Math.max(1, args.length);
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> choices = List.of();
        if (command.equals("event")) {
            if (count == 1) choices = List.of("start", "preview", "status", "stop", "newbie");
            else if (count == 2 && first.equals("newbie")) choices = List.of("start", "particles");
            else if (count >= 3 && count <= 5 && first.equals("newbie")) {
                boolean particles = args[1].equalsIgnoreCase("particles");
                choices = particles && count == 3 ? List.of("stop", "0") : List.of("0");
            }
        } else if (command.equals("reviveitem")) {
            if (count == 1) choices = players;
        } else if (count == 1) {
            choices = List.of("status", "menu", "history", "diagnostics", "difficulty", "pending", "recoverhead", "resolvepayment", "giveblock", "giveunbreak",
                "giveheart", "tokens", "sethearts", "revive", "reload", "allergy", "testsound");
        } else if (count == 2) {
            choices = switch (first) {
                case "allergy" -> List.of("give", "remove", "list");
                case "difficulty" -> List.of("easy", "normal", "hard");
                case "status", "menu", "history", "giveblock", "giveunbreak", "giveheart", "tokens", "sethearts", "revive", "recoverhead" -> players;
                default -> List.of();
            };
        } else if (count == 3) {
            if (first.equals("allergy") && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("remove"))) choices = players;
            else if (first.equals("giveheart") || first.equals("sethearts")) choices = List.of("1", "5", "10");
            else if (first.equals("recoverhead")) choices = players;
            else if (first.equals("resolvepayment")) choices = List.of("refund", "complete");
            else if (first.equals("history")) choices = List.of("1", "2", "3");
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
