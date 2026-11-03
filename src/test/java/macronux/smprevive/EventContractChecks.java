package macronux.smprevive;

import macronux.smprevive.command.Commands;
import macronux.smprevive.event.EventManager;
import macronux.smprevive.event.EventSpawnLocator;

import java.nio.file.Files;
import java.nio.file.Path;

public final class EventContractChecks {
    private EventContractChecks() {
    }

    public static void main(String[] args) throws Exception {
        String plugin = read("src/main/resources/plugin.yml");
        String config = read("src/main/resources/config.yml");
        String sounds = read("src/main/resources/sounds.yml");
        String commands = read("src/main/java/macronux/smprevive/command/Commands.java");
        String altar = read("src/main/java/macronux/smprevive/AltarGui.java");
        require(plugin.contains("event:"), "event command missing");
        require(plugin.contains("/event start"), "event usage missing");
        require(config.contains("event:") && config.contains("spawn-radius"), "event fallback config missing");
        require(config.contains("tokens-required: 160"), "casino token price must be multiplied by twenty");
        for (String key : new String[] {"start", "heart-rise", "heart-center", "player-rise", "border-slow", "border-fast", "burst", "land"}) {
            require(sounds.contains("event:\n") && sounds.contains("  " + key + ":"), "event sound missing: " + key);
        }
        require(commands.contains("commandName.equalsIgnoreCase(\"event\")"), "event command branch missing");
        require(commands.contains("equalsIgnoreCase(\"start\")"), "event start action missing");
        String manager = read("src/main/java/macronux/smprevive/event/EventManager.java");
        String locator = read("src/main/java/macronux/smprevive/event/EventSpawnLocator.java");
        String smp = read("src/main/java/macronux/smprevive/SMPRevive.java");
        require(manager.contains("enum StartResult"), "event result enum missing");
        require(manager.contains("start(Player initiator)"), "event start method missing");
        require(manager.contains("stop(boolean restoreBorder)"), "event stop method missing");
        require(manager.contains("WorldBorder"), "border snapshot missing");
        require(manager.contains("setPersistent(false)"), "display persistence guard missing");
        require(manager.contains("createHeart()"), "event heart item missing");
        require(smp.contains("eventManager.stop(true)"), "event stop lifecycle wiring missing");
        require(manager.contains("HEART_RISE_TICKS"), "heart rise timing missing");
        require(manager.contains("HEART_TRAVEL_TICKS"), "heart travel timing missing");
        require(manager.contains("Particle.ENCHANT"), "enchant trail missing");
        require(manager.contains("Particle.CLOUD"), "cloud particle missing");
        require(manager.contains("heartDelay"), "heart delay field missing");
        require(manager.contains("Quaternionf"), "heart rotation missing");
        require(manager.contains("Transformation"), "display transformation missing");
        require(manager.contains("participant.heart.remove()"), "display cleanup missing");
        require(manager.contains("PLAYER_RISE_TICKS"), "player rise timing missing");
        require(manager.contains("BORDER_SLOW_TICKS"), "slow border timing missing");
        require(manager.contains("BORDER_FAST_TICKS"), "fast border timing missing");
        require(manager.contains("BURST_TICKS"), "burst timing missing");
        require(manager.contains("PLAYER_LAND_TICKS"), "landing timing missing");
        require(manager.contains("Particle.TOTEM_OF_UNDYING"), "totem burst particles missing");
        require(manager.contains("Particle.EXPLOSION"), "explosion particles missing");
        require(manager.contains("setSize("), "border expansion missing");
        require(manager.contains("easeOutCubic"), "landing easing missing");
        require(manager.contains("spawnLocator.find"), "landing spawn lookup missing");
        require(manager.contains("setFallDistance"), "landing fall reset missing");
        require(manager.contains("SETUP_FAILED"), "setup failure result missing");
        require(manager.contains("phase execution failed"), "phase abort logging missing");
        int participantInsert = manager.indexOf("participants.put(uuid, participant)");
        int playerMutation = manager.indexOf("player.setGravity(false)", manager.indexOf("private void addParticipant"));
        require(participantInsert >= 0 && playerMutation >= 0 && participantInsert < playerMutation,
            "participant must be registered before player mutation");
        require(locator.contains("findSafeAt(World world, int x, int z)"), "safe center lookup missing");
        require(locator.contains("border margin"), "radius border margin guard missing");
        require(manager.contains("participant.heart = world.spawn(displayLocation, ItemDisplay.class)"),
            "display reference must be captured immediately");
        require(locator.contains("isWithinUsableBorder"), "local border margin helper missing");
        require(locator.contains("findAsync(World world"), "async landing lookup missing");
        require(locator.contains("getChunkAtAsync"), "async chunk loading missing");
        require(locator.contains("int checks = 24"), "landing checks must be bounded per tick");
        require(locator.contains("BURST_PREPARE_TIMEOUT_TICKS"), "landing lookup timeout missing");
        require(locator.contains("addPluginChunkTicket"), "landing chunk tickets missing");
        require(locator.contains("removePluginChunkTicket"), "landing chunk ticket cleanup missing");
        require(manager.contains("spawnLocator.holdLocation"), "origin chunk ticket missing");
        require(manager.contains("spawnLocator.releaseTickets"), "event chunk ticket cleanup missing");
        require(!manager.contains("spawnLocator.find(world)"), "landing must not scan synchronously during burst");
        require(!manager.contains("participant.landingTarget = participant.lastManagedLocation"),
            "landing must not use an airborne fallback");
        require(manager.contains("Location fallback = participant.startLocation"),
            "landing must use the saved ground-level fallback");
        require(manager.contains("restorePlayer(participant, restoreBorder)"),
            "abort must restore the saved location when still in the same world");
        require(manager.contains("spawnBurstCloudPulse"), "burst cloud pulse missing");
        require(manager.contains("player.setAllowFlight(true)"), "temporary flight permission missing");
        require(manager.contains("player.setFlying(true)"), "temporary flying state missing");
        require(manager.contains("playSoundToPlayer"), "event sounds must reach participants");
        require(manager.contains("CLOUD_HEIGHT_OFFSET"), "cloud height offset missing");
        require(manager.contains("centralHeartStart"), "central heart launch start missing");
        require(manager.contains("centralHeartEnd"), "central heart launch target missing");
        require(manager.contains("BURST_HEART_HOLD_TICKS"), "heart burst hold timing missing");
        require(manager.contains("BURST_HEART_FLIGHT_TICKS"), "heart burst flight timing missing");
        require(manager.contains("35.0, 55.0"), "player burst height missing");
        require(locator.contains("findRandomSpawnPlus"), "RandomSpawnPlus bridge missing");
        require(!locator.contains("setAccessible"), "RandomSpawnPlus private reflection must not be used");
        require(altar.contains("return calculateLocalSpawnLocation(altarLocation);"),
            "normal altar must retain local altar-radius spawn");
        require(altar.contains("plugin.getEventSpawnLocator().findRandomSpawnPlus(targetWorld)"),
            "Limbo must use RandomSpawnPlus");
        require(altar.contains("getEventSpawnLocator"), "altar spawn provider wiring missing");
        require(!altar.contains("getStoredOrDroppedHeadLocation"), "revival must not use dropped death head location");
        require(altar.contains("startLimboArrivalAnimation"), "Limbo arrival animation missing");
        require(altar.contains("limboArrivals"), "Limbo animation cleanup state missing");
        require(altar.contains("ItemDisplay"), "Limbo arrival must use ItemDisplay");
        require(manager.contains("player.setWalkSpeed(0.0f)") && manager.contains("player.setFlySpeed(0.0f)"),
            "event movement input must be disabled");
        require(!manager.contains("event.setTo("), "event must not correct movement packets or camera");
        require(manager.contains("onToggleFlight"), "event flight toggle guard missing");
        require(manager.contains("current.getYaw()"), "player yaw must be preserved");
        require(manager.contains("current.getPitch()"), "player pitch must be preserved");
        require(altar.contains("new Quaternionf()"), "Limbo head must not rotate itself");
        System.out.println("EventContractChecks: PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path)).replace("\r\n", "\n");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
