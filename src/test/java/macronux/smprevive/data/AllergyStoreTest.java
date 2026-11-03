package macronux.smprevive.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import junit.framework.TestCase;

public final class AllergyStoreTest extends TestCase {
    public void testMalformedFileIsNeverOverwritten() throws Exception {
        Path directory = Files.createTempDirectory("smprevive-allergy-test");
        Path file = directory.resolve("allergies.yml");
        String broken = "heart-allergic: [unclosed";
        try {
            Files.writeString(file, broken);
            AllergyStore store = new AllergyStore(file.toFile(), Logger.getAnonymousLogger());
            assertFalse(store.setAllergic(UUID.randomUUID(), true));
            assertFalse(store.save());
            assertEquals(broken, Files.readString(file));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    public void testAllergySurvivesRestartAndRemoval() throws Exception {
        Path directory = Files.createTempDirectory("smprevive-allergy-test");
        Path file = directory.resolve("allergies.yml");
        UUID player = UUID.randomUUID();
        try {
            AllergyStore store = new AllergyStore(file.toFile(), Logger.getAnonymousLogger());
            assertTrue(store.setAllergic(player, true));
            store = new AllergyStore(file.toFile(), Logger.getAnonymousLogger());
            assertTrue(store.isAllergic(player));
            assertTrue(store.setAllergic(player, false));
            assertFalse(new AllergyStore(file.toFile(), Logger.getAnonymousLogger()).isAllergic(player));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }
}
