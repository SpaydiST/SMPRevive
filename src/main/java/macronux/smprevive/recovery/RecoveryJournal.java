package macronux.smprevive.recovery;

import macronux.smprevive.DataManager;
import macronux.smprevive.SMPRevive;
import macronux.smprevive.gui.GuiEscrow;
import macronux.smprevive.data.AtomicStateFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;

/**
 * Small main-thread journal for payments that can outlive a running animation.
 * The file is deliberately separate from player data so an unfinished payment
 * can be recovered without rewriting unrelated state.
 */
/** Persists payment snapshots before mutations so interrupted operations can be recovered. */
public final class RecoveryJournal {
    public enum Type { HEART, ALTAR }

    public enum Status { PREPARED, CONSUMED, REFUNDING }

    public static final class Payment {
        private final String source;
        private final ItemStack sourceSnapshot;
        private final ItemStack refundItem;

        public Payment(String source, ItemStack sourceSnapshot, ItemStack refundItem) {
            if (!isValidSource(source)) throw new IllegalArgumentException("unsupported payment source");
            if (refundItem == null || refundItem.getType() == Material.AIR || refundItem.getAmount() <= 0) {
                throw new IllegalArgumentException("refund item is required");
            }
            if (!"GUI".equalsIgnoreCase(source) && sourceSnapshot == null) {
                throw new IllegalArgumentException("source snapshot is required");
            }
            this.source = source.toUpperCase();
            this.sourceSnapshot = sourceSnapshot == null ? null : sourceSnapshot.clone();
            this.refundItem = refundItem.clone();
        }

        public String source() {
            return this.source;
        }

        public ItemStack sourceSnapshot() {
            return this.sourceSnapshot == null ? null : this.sourceSnapshot.clone();
        }

        public ItemStack refundItem() {
            return this.refundItem.clone();
        }
    }

    public static final class Operation {
        private final UUID id;
        private final Type type;
        private final UUID ownerId;
        private final UUID targetId;
        private final int beforeHearts;
        private final boolean targetWasDead;
        private final Location altarLocation;
        private final Location refundLocation;
        private final String altarSerialized;
        private final String refundSerialized;
        private final List<Payment> payments;
        private final boolean receiptRequired;
        private final boolean exactOutcomeRequired;
        private Status status;
        // Only live operations know a debit happened before a failed playerdata save.
        private boolean debitApplied;
        private boolean manualRefund;

        private Operation(
            UUID id,
            Type type,
            UUID ownerId,
            UUID targetId,
            int beforeHearts,
            boolean targetWasDead,
            Location altarLocation,
            Location refundLocation,
            List<Payment> payments,
            Status status,
            String altarSerialized,
            String refundSerialized,
            boolean receiptRequired,
            boolean exactOutcomeRequired
        ) {
            this.id = id;
            this.type = type;
            this.ownerId = ownerId;
            this.targetId = targetId;
            this.beforeHearts = beforeHearts;
            this.targetWasDead = targetWasDead;
            this.altarLocation = altarLocation == null ? null : altarLocation.clone();
            this.refundLocation = refundLocation == null ? null : refundLocation.clone();
            this.altarSerialized = altarSerialized;
            this.refundSerialized = refundSerialized;
            this.payments = List.copyOf(payments);
            this.receiptRequired = receiptRequired;
            this.exactOutcomeRequired = exactOutcomeRequired;
            this.status = status;
        }

        public UUID id() {
            return this.id;
        }

        public Type type() {
            return this.type;
        }

        public UUID ownerId() {
            return this.ownerId;
        }

        public UUID targetId() {
            return this.targetId;
        }

        public int beforeHearts() {
            return this.beforeHearts;
        }

        public boolean targetWasDead() {
            return this.targetWasDead;
        }

        public Location altarLocation() {
            return resolveLocation(this.altarLocation, this.altarSerialized);
        }

        public Location refundLocation() {
            return resolveLocation(this.refundLocation, this.refundSerialized);
        }

        private static Location resolveLocation(Location current, String serialized) {
            if (current != null) return current.clone();
            if (serialized == null) return null;
            String[] parts = serialized.split(",", -1);
            if (parts.length != 4) return null;
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) return null;
            try {
                return new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        public List<Payment> payments() {
            return this.payments;
        }

        public Status status() {
            return this.status;
        }

        public boolean receiptRequired() {
            return this.receiptRequired;
        }
    }

    private final SMPRevive plugin;
    private final File journalFile;
    private final YamlConfiguration yaml;
    private AtomicStateFile stateFile;
    private final NamespacedKey refundMarkerKey;
    private final Map<UUID, Operation> operations = new LinkedHashMap<>();
    private boolean loadFailed;

    public RecoveryJournal(SMPRevive plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        this.journalFile = new File(plugin.getDataFolder(), "operations.yml");
        this.yaml = new YamlConfiguration();
        if (this.journalFile.exists()) {
            try {
                this.yaml.load(this.journalFile);
            } catch (IOException | InvalidConfigurationException ex) {
                this.loadFailed = true;
                this.plugin.getLogger().warning(
                    "Failed to load operations.yml; refusing to overwrite it: " + ex.getMessage()
                );
            }
        }
        if (!this.loadFailed) {
            try { this.stateFile = new AtomicStateFile(this.journalFile.toPath(), this.yaml.saveToString()); }
            catch (IOException error) { this.loadFailed = true; plugin.getLogger().severe("Cannot initialize recovery journal: " + error.getMessage()); }
        }
        this.refundMarkerKey = new NamespacedKey(plugin, "recovery_operation");
        loadOperations();
    }

    public Operation begin(
        Type type,
        UUID ownerId,
        UUID targetId,
        int beforeHearts,
        boolean targetWasDead,
        Location altarLocation,
        Location refundLocation,
        List<Payment> payments
    ) {
        if (this.loadFailed || type == null || ownerId == null || payments == null || payments.isEmpty()) return null;
        Operation operation = new Operation(
            UUID.randomUUID(),
            type,
            ownerId,
            targetId,
            beforeHearts,
            targetWasDead,
            altarLocation,
            refundLocation,
            payments,
            Status.PREPARED,
            serializeLocation(altarLocation), serializeLocation(refundLocation), true, true
        );
        this.operations.put(operation.id(), operation);
        if (!saveOperations()) {
            this.operations.remove(operation.id());
            return null;
        }
        return operation;
    }

    public boolean markConsumed(Operation operation) {
        return markConsumed(operation, null);
    }

    /** Persist the debit receipt in playerdata before treating the payment as consumed. */
    public boolean markConsumed(Operation operation, Player player) {
        if (!isKnown(operation)) return false;
        if (player == null) player = Bukkit.getPlayer(operation.ownerId());
        if (player == null || !player.isOnline()) return false;
        operation.debitApplied = true;
        NamespacedKey operationReceiptKey = receiptKey(operation);
        Byte previousReceipt = player.getPersistentDataContainer().get(operationReceiptKey, PersistentDataType.BYTE);
        try {
            player.getPersistentDataContainer().set(operationReceiptKey, PersistentDataType.BYTE, (byte) 1);
            GuiEscrow.savePlayerData(player);
        } catch (RuntimeException ex) {
            if (previousReceipt == null) player.getPersistentDataContainer().remove(operationReceiptKey);
            else player.getPersistentDataContainer().set(operationReceiptKey, PersistentDataType.BYTE, previousReceipt);
            return false;
        }
        Status previous = operation.status;
        operation.status = Status.CONSUMED;
        if (saveOperations()) return true;
        operation.status = previous;
        // Keep the durable debit receipt. A failed journal write must recover
        // as a debit, never silently turn back into an unconsumed payment.
        saveOperations();
        return false;
    }

    public boolean commit(Operation operation) {
        if (!isKnown(operation)) return false;
        return forgetOperation(operation);
    }

    public void abort(Operation operation, boolean paymentStillPresent, Player player, Location dropLocation) {
        if (!isKnown(operation)) return;
        if (isSuccessful(operation, this.plugin.getDataManager())) { forgetOperation(operation); return; }
        boolean successRecorded = false;
        boolean refundMarkerFound = player != null && hasAllRefundMarkers(operation, player, dropLocation);
        if (RecoveryRules.shouldClearWithoutRefund(
            operation.status().name(),
            successRecorded,
            paymentStillPresent,
            refundMarkerFound,
            false
        )) {
            forgetOperation(operation);
            return;
        }
        refundAndForget(operation, player, dropLocation);
    }

    public void recover(Player player, DataManager data) {
        if (player == null || data == null) return;
        UUID playerId = player.getUniqueId();
        for (Operation operation : new ArrayList<>(this.operations.values())) {
            if (!playerId.equals(operation.ownerId())) continue;
            recoverOperation(operation, player, data);
        }
    }

    /** Recover online owners and consumed operations whose owner is offline. */
    public void recoverAll(DataManager data) {
        if (data == null) return;
        for (Operation operation : new ArrayList<>(this.operations.values())) {
            Player owner = Bukkit.getPlayer(operation.ownerId());
            if (owner != null && owner.isOnline()) {
                recoverOperation(operation, owner, data);
                continue;
            }
            if (isSuccessful(operation, data)) {
                forgetOperation(operation);
                continue;
            }
            // Offline playerdata cannot prove whether a debit receipt exists;
            // wait for join rather than risking a duplicate refund.
        }
    }

    public List<String> describePending() {
        return operations.values().stream().limit(20).map(op -> op.id() + " | " + op.type() + " | " + op.status()
            + (op.exactOutcomeRequired ? "" : " | LEGACY: admin decision required")).toList();
    }

    /** Only ambiguous legacy operations may be manually resolved; current transactions own their outcome. */
    public boolean resolveLegacy(UUID id, boolean refund) {
        Operation operation = operations.get(id);
        if (loadFailed || operation == null || operation.exactOutcomeRequired) return false;
        if (!refund) return forgetOperation(operation);
        Status before = operation.status;
        boolean beforeManual = operation.manualRefund;
        operation.status = Status.REFUNDING; operation.manualRefund = true;
        if (!saveOperations()) { operation.status = before; operation.manualRefund = beforeManual; return false; }
        Player owner = Bukkit.getPlayer(operation.ownerId());
        if (owner != null && owner.isOnline()) refundAndForget(operation, owner, owner.getLocation());
        return true;
    }

    public int size() {
        return this.operations.size();
    }

    private boolean isSuccessful(Operation operation, DataManager data) {
        return data.hasCompletedOperation(operation.id());
    }

    private boolean hasDurableReceipt(Operation operation, Player player) {
        if (player == null) return false;
        return player.getPersistentDataContainer().has(receiptKey(operation), PersistentDataType.BYTE);
    }

    private NamespacedKey receiptKey(Operation operation) {
        return new NamespacedKey(this.plugin, "recovery_receipt_" + operation.id().toString().replace('-', '_'));
    }

    private NamespacedKey deliveredKey(Operation operation) {
        return new NamespacedKey(this.plugin, "recovery_refund_" + operation.id().toString().replace('-', '_'));
    }

    private int[] deliveredCounts(Operation operation, Player player) {
        int[] counts = player.getPersistentDataContainer().get(deliveredKey(operation), PersistentDataType.INTEGER_ARRAY);
        return counts == null || counts.length != operation.payments().size()
            ? new int[operation.payments().size()] : counts.clone();
    }

    private void recoverOperation(Operation operation, Player player, DataManager data) {
        if (isSuccessful(operation, data)) { forgetOperation(operation); return; }
        if (operation.manualRefund) { refundAndForget(operation, player, player.getLocation()); return; }
        // Old journals have no exact outcome proof. HP/dead state may have changed since the operation.
        // Preserve ambiguous entries for an explicit administrator decision instead of guessing a refund.
        if (!operation.exactOutcomeRequired && operation.status() != Status.REFUNDING
            && !data.hasCompletedOperation(operation.id())) return;
        boolean successRecorded = operation.status() != Status.REFUNDING && isSuccessful(operation, data);
        boolean paymentStillPresent = isPaymentStillPresent(operation, player);
        boolean refundMarkerFound = !operation.receiptRequired()
            && hasAllRefundMarkers(operation, player, player.getLocation());
        boolean durableReceipt = hasDurableReceipt(operation, player) || operation.debitApplied;
        if (RecoveryRules.shouldClearWithoutRefund(
            operation.status().name(),
            successRecorded,
            paymentStillPresent,
            refundMarkerFound,
            durableReceipt,
            operation.receiptRequired()
        )) {
            forgetOperation(operation);
            return;
        }
        refundAndForget(operation, player, player.getLocation());
    }

    private boolean isPaymentStillPresent(Operation operation, Player player) {
        for (Payment payment : operation.payments()) {
            if ("GUI".equalsIgnoreCase(payment.source())) return false;
            ItemStack expected = payment.sourceSnapshot();
            if (expected == null) return false;
            ItemStack actual = switch (payment.source().toUpperCase()) {
                case "MAIN_HAND" -> player.getInventory().getItemInMainHand();
                case "OFF_HAND" -> player.getInventory().getItemInOffHand();
                default -> null;
            };
            if (actual == null || !actual.isSimilar(expected) || actual.getAmount() < expected.getAmount()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAllRefundMarkers(Operation operation, Player player, Location extraLocation) {
        for (int index = 0; index < operation.payments().size(); index++) {
            int expected = operation.payments().get(index).refundItem().getAmount();
            if (countRefundMarker(player, operation, index, extraLocation) < expected) return false;
        }
        return true;
    }

    private int countRefundMarker(Player player, Operation operation, int paymentIndex, Location extraLocation) {
        String marker = marker(operation, paymentIndex);
        int total = countOnlinePlayerMarkers(marker) + countLoadedContainerMarkers(marker);

        Set<UUID> countedDrops = new HashSet<>();
        Location location = operation.refundLocation();
        total += countDroppedMarkers(location, marker, countedDrops);
        if (!sameLocation(location, extraLocation)) {
            total += countDroppedMarkers(extraLocation, marker, countedDrops);
        }
        return total;
    }

    private int countPlayerInventoryMarkers(Player player, String marker) {
        if (player == null) return 0;
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (hasMarker(item, marker)) total += item.getAmount();
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (hasMarker(offHand, marker)) total += offHand.getAmount();
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (hasMarker(armor, marker)) total += armor.getAmount();
        }
        ItemStack cursor = player.getItemOnCursor();
        if (hasMarker(cursor, marker)) total += cursor.getAmount();
        return total;
    }

    private int countOnlinePlayerMarkers(String marker) {
        int total = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            total += countPlayerInventoryMarkers(player, marker);
        }
        return total;
    }

    /**
     * Refunds are rare, so inspect already-loaded containers as well as player
     * inventories. This closes the crash window where a marked refund was
     * moved into a chest before the journal cleanup was persisted, without
     * loading arbitrary chunks during recovery.
     */
    private int countLoadedContainerMarkers(String marker) {
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (!(state instanceof Container container)) continue;
                    try {
                        for (ItemStack item : container.getInventory().getContents()) {
                            if (hasMarker(item, marker)) total += item.getAmount();
                        }
                    } catch (RuntimeException ex) {
                        this.plugin.getLogger().warning(
                            "Could not inspect a loaded container during recovery: " + ex.getMessage()
                        );
                    }
                }
            }
        }
        return total;
    }

    private int countDroppedMarkers(Location location, String marker, Set<UUID> countedDrops) {
        if (location == null || location.getWorld() == null) return 0;
        int total = 0;
        for (Entity entity : location.getWorld().getNearbyEntities(location, 8.0, 8.0, 8.0)) {
            if (!(entity instanceof Item dropped) || !hasMarker(dropped.getItemStack(), marker)) continue;
            if (countedDrops.add(dropped.getUniqueId())) total += dropped.getItemStack().getAmount();
        }
        return total;
    }

    private boolean hasAllDroppedRefundMarkers(Operation operation, Location location) {
        Set<UUID> countedDrops = new HashSet<>();
        for (int index = 0; index < operation.payments().size(); index++) {
            int expected = operation.payments().get(index).refundItem().getAmount();
            if (countDroppedMarkers(location, marker(operation, index), countedDrops) < expected) return false;
        }
        return true;
    }

    private boolean sameLocation(Location first, Location second) {
        if (first == null || second == null || first.getWorld() == null || second.getWorld() == null) return false;
        return first.getWorld().equals(second.getWorld()) && first.distanceSquared(second) < 0.0001;
    }

    private void refundAndForget(Operation operation, Player player, Location dropLocation) {
        if (operation.status() != Status.REFUNDING) {
            Status previous = operation.status;
            operation.status = Status.REFUNDING;
            if (!saveOperations()) {
                operation.status = previous;
                return;
            }
        }
        if (player != null && player.isOnline()) {
            boolean pending = false;
            int[] delivered = deliveredCounts(operation, player);
            ItemStack[] beforeStorage = cloneItems(player.getInventory().getStorageContents());
            ItemStack beforeOffHand = player.getInventory().getItemInOffHand();
            int[] previousDelivered = delivered.clone();
            for (int index = 0; index < operation.payments().size(); index++) {
                Payment payment = operation.payments().get(index);
                int missing = payment.refundItem().getAmount() - delivered[index];
                if (missing <= 0) continue;

                ItemStack refund = payment.refundItem();
                refund.setAmount(missing);
                if (!operation.receiptRequired && !operation.manualRefund) markRefund(refund, marker(operation, index));
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(refund);
                int leftoverAmount = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
                delivered[index] += missing - leftoverAmount;
                pending |= !leftovers.isEmpty();
            }
            player.getPersistentDataContainer().set(
                deliveredKey(operation), PersistentDataType.INTEGER_ARRAY, delivered
            );
            try {
                // Keep the journal until the marked inventory mutation is in
                // the player's durable save. A crash before this point retries
                // from the journal and marker counts make the retry idempotent.
                GuiEscrow.savePlayerData(player);
            } catch (RuntimeException ex) {
                player.getInventory().setStorageContents(beforeStorage);
                player.getInventory().setItemInOffHand(beforeOffHand);
                player.getPersistentDataContainer().set(
                    deliveredKey(operation), PersistentDataType.INTEGER_ARRAY, previousDelivered
                );
                this.plugin.getLogger().warning("Could not persist recovery refund: " + ex.getMessage());
                return;
            }
            if (pending) return;
        } else if (dropLocation != null && dropLocation.getWorld() != null) {
            // A world drop has no playerdata receipt. Keep the operation queued
            // until the owner joins and the inventory mutation can be saved.
            return;
        } else {
            this.plugin.getLogger().warning(
                "Recovery refund has no valid drop location; keeping operation " + operation.id()
            );
            return;
        }
        forgetOperation(operation);
    }

    private ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = items[i] == null ? null : items[i].clone();
        return copy;
    }

    private void markRefund(ItemStack item, String marker) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(this.refundMarkerKey, PersistentDataType.STRING, marker);
        item.setItemMeta(meta);
    }

    public void markRefund(ItemStack item, Operation operation, int paymentIndex) {
        if (item == null || !isKnown(operation)
            || paymentIndex < 0 || paymentIndex >= operation.payments().size()) return;
        markRefund(item, marker(operation, paymentIndex));
    }

    private boolean hasMarker(ItemStack item, String marker) {
        if (item == null || !item.hasItemMeta()) return false;
        String value = item.getItemMeta().getPersistentDataContainer().get(this.refundMarkerKey, PersistentDataType.STRING);
        return marker.equals(value);
    }

    private String marker(Operation operation, int paymentIndex) {
        return operation.id() + ":" + paymentIndex;
    }

    private boolean isKnown(Operation operation) {
        return operation != null && this.operations.get(operation.id()) == operation;
    }

    private boolean forgetOperation(Operation operation) {
        if (!isKnown(operation)) return true;
        Operation removed = this.operations.remove(operation.id());
        if (saveOperations()) {
            Player owner = Bukkit.getPlayer(operation.ownerId());
            if (owner != null) {
                owner.getPersistentDataContainer().remove(receiptKey(operation));
                owner.getPersistentDataContainer().remove(deliveredKey(operation));
            }
            // Cleanup follows journal removal, so stale receipts can only delay cleanup, never mint a refund.
            DataManager data = plugin.getDataManager();
            if (data.hasCompletedOperation(operation.id())) {
                data.setCompletedOperation(operation.id(), false);
                if (!data.saveSafely()) data.setCompletedOperation(operation.id(), true);
            }
            return true;
        }
        this.operations.put(operation.id(), removed);
        saveOperations();
        return false;
    }

    private void loadOperations() {
        ConfigurationSection root = this.yaml.getConfigurationSection("operations");
        if (root == null) {
            if (this.yaml.contains("operations")) { this.loadFailed = true; this.plugin.getLogger().severe("Malformed operations root; writes disabled"); }
            return;
        }
        for (String idText : root.getKeys(false)) {
            String path = "operations." + idText;
            try {
                UUID id = UUID.fromString(idText);
                Type type = Type.valueOf(this.yaml.getString(path + ".type", ""));
                Status status = Status.valueOf(this.yaml.getString(path + ".status", ""));
                boolean receiptRequired = this.yaml.getBoolean(path + ".receipt-required", false);
                boolean exactOutcomeRequired = this.yaml.getBoolean(path + ".outcome-receipt-required", false);
                UUID ownerId = UUID.fromString(this.yaml.getString(path + ".owner"));
                String targetText = this.yaml.getString(path + ".target");
                UUID targetId = targetText == null ? null : UUID.fromString(targetText);
                int beforeHearts = this.yaml.getInt(path + ".before-hearts");
                boolean targetWasDead = this.yaml.getBoolean(path + ".target-was-dead", false);
                String altarSerialized = this.yaml.getString(path + ".altar");
                String refundSerialized = this.yaml.getString(path + ".refund-location");
                Location altarLocation = parseLocation(altarSerialized);
                Location refundLocation = parseLocation(refundSerialized);
                if (!isLocationText(refundSerialized)) throw new IllegalArgumentException("refund location is missing");
                if (type == Type.ALTAR && (targetId == null || !isLocationText(altarSerialized))) {
                    throw new IllegalArgumentException("altar operation coordinates are missing");
                }
                List<Payment> payments = loadPayments(path + ".payments");
                if (payments.isEmpty()) throw new IllegalArgumentException("no payments");
                this.operations.put(id, new Operation(
                    id,
                    type,
                    ownerId,
                    targetId,
                    beforeHearts,
                    targetWasDead,
                    altarLocation,
                    refundLocation,
                    payments,
                    status,
                    altarSerialized,
                    refundSerialized,
                    receiptRequired,
                    exactOutcomeRequired
                ));
                this.operations.get(id).manualRefund = this.yaml.getBoolean(path + ".manual-refund", false);
            } catch (RuntimeException ex) {
                this.loadFailed = true;
                this.plugin.getLogger().severe("Preserving malformed recovery operation " + idText + ": " + ex.getMessage());
            }
        }
    }

    private List<Payment> loadPayments(String path) {
        List<Payment> result = new ArrayList<>();
        List<Map<?, ?>> rawPayments = this.yaml.getMapList(path);
        for (Map<?, ?> raw : rawPayments) {
            Object rawSource = raw.get("source");
            String source = rawSource instanceof String value ? value : null;
            if (!isValidSource(source)) throw new IllegalArgumentException("unsupported payment source");
            ItemStack sourceSnapshot = deserializeItem(raw.get("source-item"));
            ItemStack refundItem = deserializeItem(raw.get("refund-item"));
            if (refundItem == null) throw new IllegalArgumentException("refund item is malformed");
            result.add(new Payment(source, sourceSnapshot, refundItem));
        }
        return result;
    }

    private static boolean isValidSource(String source) {
        if (source == null || source.isBlank()) return false;
        return switch (source.toUpperCase()) {
            case "MAIN_HAND", "OFF_HAND", "GUI" -> true;
            default -> false;
        };
    }

    private ItemStack deserializeItem(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<String, Object> serial = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            serial.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        try {
            return ItemStack.deserialize(serial);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean saveOperations() {
        if (this.loadFailed) {
            this.plugin.getLogger().warning("Skipped operations.yml save because the file could not be loaded safely");
            return false;
        }
        this.yaml.set("operations", null);
        for (Operation operation : this.operations.values()) {
            String path = "operations." + operation.id();
            this.yaml.set(path + ".type", operation.type().name());
            this.yaml.set(path + ".status", operation.status().name());
            this.yaml.set(path + ".receipt-required", operation.receiptRequired());
            this.yaml.set(path + ".outcome-receipt-required", operation.exactOutcomeRequired);
            this.yaml.set(path + ".manual-refund", operation.manualRefund);
            this.yaml.set(path + ".owner", operation.ownerId().toString());
            this.yaml.set(path + ".target", operation.targetId() == null ? null : operation.targetId().toString());
            this.yaml.set(path + ".before-hearts", operation.beforeHearts());
            this.yaml.set(path + ".target-was-dead", operation.targetWasDead());
            this.yaml.set(path + ".altar", operation.altarSerialized);
            this.yaml.set(path + ".refund-location", operation.refundSerialized);
            List<Map<String, Object>> payments = new ArrayList<>();
            for (Payment payment : operation.payments()) {
                Map<String, Object> serialized = new LinkedHashMap<>();
                serialized.put("source", payment.source());
                serialized.put("source-item", serializeItem(payment.sourceSnapshot()));
                serialized.put("refund-item", serializeItem(payment.refundItem()));
                payments.add(serialized);
            }
            this.yaml.set(path + ".payments", payments);
        }

        try {
            this.stateFile.save(this.yaml.saveToString());
            return true;
        } catch (IOException | RuntimeException ex) {
            this.plugin.getLogger().warning("Failed to save operations.yml: " + ex.getMessage());
            return false;
        }
    }

    private Map<String, Object> serializeItem(ItemStack item) {
        return item == null ? null : new LinkedHashMap<>(item.serialize());
    }

    private String serializeLocation(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return location.getWorld().getName() + "," + location.getX() + "," + location.getY() + "," + location.getZ();
    }

    private Location parseLocation(String value) {
        if (value == null || value.isBlank()) return null;
        String[] parts = value.split(",", -1);
        if (parts.length != 4) return null;
        World world = this.plugin.getServer().getWorld(parts[0]);
        if (world == null) return null;
        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
            return new Location(world, x, y, z);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean isLocationText(String value) {
        if (value == null || value.isBlank()) return false;
        String[] parts = value.split(",", -1);
        if (parts.length != 4 || parts[0].isBlank()) return false;
        try {
            return Double.isFinite(Double.parseDouble(parts[1]))
                && Double.isFinite(Double.parseDouble(parts[2]))
                && Double.isFinite(Double.parseDouble(parts[3]));
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
