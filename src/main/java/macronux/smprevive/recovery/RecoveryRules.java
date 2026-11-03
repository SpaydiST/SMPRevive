package macronux.smprevive.recovery;

/** Pure decision rules used by the crash-recovery journal. */
public final class RecoveryRules {
    private RecoveryRules() {
    }

    public static boolean shouldClearWithoutRefund(
        String status,
        boolean successRecorded,
        boolean paymentStillPresent,
        boolean refundMarkerFound
    ) {
        return shouldClearWithoutRefund(status, successRecorded, paymentStillPresent, refundMarkerFound, false);
    }

    public static boolean shouldClearWithoutRefund(
        String status,
        boolean successRecorded,
        boolean paymentStillPresent,
        boolean refundMarkerFound,
        boolean durableReceipt,
        boolean receiptRequired
    ) {
        // Playerdata stores the debit and receipt together, even if journal status advanced.
        if (receiptRequired && !durableReceipt) return true;
        if ("REFUNDING".equalsIgnoreCase(status)) return refundMarkerFound;
        return successRecorded || refundMarkerFound
            || ("PREPARED".equalsIgnoreCase(status) && paymentStillPresent && !durableReceipt);
    }

    /**
     * A durable receipt means the payment debit was persisted with player data.
     * In that case a PREPARED journal entry must not be cleared merely because
     * a stale in-memory inventory still contains the payment.
     */
    public static boolean shouldClearWithoutRefund(
        String status,
        boolean successRecorded,
        boolean paymentStillPresent,
        boolean refundMarkerFound,
        boolean durableReceipt
    ) {
        return shouldClearWithoutRefund(status, successRecorded, paymentStillPresent,
            refundMarkerFound, durableReceipt, false);
    }

    public static boolean shouldRefund(
        String status,
        boolean successRecorded,
        boolean paymentStillPresent,
        boolean refundMarkerFound
    ) {
        return !shouldClearWithoutRefund(status, successRecorded, paymentStillPresent, refundMarkerFound);
    }

    public static boolean shouldRefund(
        String status,
        boolean successRecorded,
        boolean paymentStillPresent,
        boolean refundMarkerFound,
        boolean durableReceipt
    ) {
        return !shouldClearWithoutRefund(
            status, successRecorded, paymentStillPresent, refundMarkerFound, durableReceipt
        );
    }
}
