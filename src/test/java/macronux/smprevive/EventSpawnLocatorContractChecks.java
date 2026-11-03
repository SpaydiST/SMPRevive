package macronux.smprevive;

import macronux.smprevive.event.EventSpawnLocator;

import java.nio.file.Files;
import java.nio.file.Path;

public final class EventSpawnLocatorContractChecks {
    private EventSpawnLocatorContractChecks() {
    }

    public static void main(String[] args) throws Exception {
        Path source = Path.of("src/main/java/macronux/smprevive/event/EventSpawnLocator.java");
        require(Files.isRegularFile(source), "EventSpawnLocator.java is missing");
        String text = Files.readString(source);
        require(text.contains("public EventSpawnLocator(SMPRevive plugin)"), "constructor missing");
        require(text.contains("find(World world)"), "find method missing");
        require(text.contains("RandomSpawnPlus"), "RandomSpawnPlus lookup missing");
        require(text.contains("spawnzones.yml"), "spawnzones file missing");
        require(text.contains("50"), "border margin missing");
        require(text.contains("10"), "attempt count missing");
        require(text.contains("20"), "safe search radius missing");
        require(text.contains("worlds."), "world radius lookup missing");
        System.out.println("EventSpawnLocatorContractChecks: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
