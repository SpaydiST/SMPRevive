package macronux.smprevive;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-light source contract checks for the optional SpaydisCore API. */
public final class IntegrationContractChecks {
    private IntegrationContractChecks() {
    }

    public static void main(String[] args) throws Exception {
        String smp = Files.readString(Path.of(
                "src/main/java/macronux/smprevive/SMPRevive.java"));
        String data = Files.readString(Path.of(
                "src/main/java/macronux/smprevive/DataManager.java"));
        require(smp.contains("getIntegrationApiVersion()")
                        && smp.contains("hasPlayerData(UUID uuid)")
                        && smp.contains("isPlayerDead(UUID uuid)")
                        && smp.contains("isFirstDeathPending(Player player)")
                        && smp.contains("reviveFromLimbo(Player target, Location spawnLocation)"));
        require(data.contains("public void save()")
                        && data.contains("public boolean saveSafely()")
                        && data.contains("public void addAltar(Location loc)")
                        && data.contains("public boolean addAltarSafely(Location loc)")
                        && data.contains("public void removeAltar(Location loc)")
                        && data.contains("public boolean removeAltarSafely(Location loc)"));
        System.out.println("IntegrationContractChecks: PASS");
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("integration contract is missing");
    }
}
