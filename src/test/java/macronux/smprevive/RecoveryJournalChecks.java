package macronux.smprevive;

import macronux.smprevive.recovery.RecoveryJournal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Paper API checks for payment serialization and immutable payment snapshots. */
public final class RecoveryJournalChecks {
    private RecoveryJournalChecks() {
    }

    public static void main(String[] args) {
        ItemStack original = new ItemStack(Material.DIAMOND, 3);
        Map<String, Object> serialized = new LinkedHashMap<>(original.serialize());
        ItemStack restored = ItemStack.deserialize(serialized);
        assert restored.isSimilar(original) : restored;
        assert restored.getAmount() == 3 : restored.getAmount();

        RecoveryJournal.Payment payment = new RecoveryJournal.Payment("GUI", null, original);
        original.setAmount(1);
        assert payment.refundItem().getAmount() == 3 : payment.refundItem().getAmount();
        assert payment.sourceSnapshot() == null;
        System.out.println("RecoveryJournalChecks: PASS");
    }
}
