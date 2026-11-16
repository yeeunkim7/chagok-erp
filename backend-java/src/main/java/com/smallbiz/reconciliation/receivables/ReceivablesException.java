package com.smallbiz.reconciliation.receivables;

public class ReceivablesException extends RuntimeException {
    private final String code;
    private final int status;
    ReceivablesException(String code, int status, String message) {
        super(message); this.code = code; this.status = status;
    }
    public String code() { return code; }
    public int status() { return status; }
    static ReceivablesException invalid() {
        return new ReceivablesException("INVALID_INPUT", 400, "Invalid receivables input.");
    }
    static ReceivablesException missing() {
        return new ReceivablesException("RECEIVABLE_NOT_FOUND", 404, "The requested record was not found.");
    }
    static ReceivablesException duplicate() {
        return new ReceivablesException("RECEIVABLE_DUPLICATE", 409, "The code or invoice number is already in use.");
    }
    static ReceivablesException keyConflict() {
        return new ReceivablesException("IDEMPOTENCY_CONFLICT", 409, "The idempotency key was used for another request.");
    }
    static ReceivablesException alreadyCancelled() {
        return new ReceivablesException("INVOICE_ALREADY_CANCELLED", 409, "The invoice has already been cancelled.");
    }
    static ReceivablesException activeAllocations() {
        return new ReceivablesException("INVOICE_HAS_ACTIVE_ALLOCATIONS", 409, "Cancel active allocations before cancelling the invoice.");
    }
    static ReceivablesException notCancelled() {
        return new ReceivablesException("INVOICE_NOT_CANCELLED", 409, "A replacement requires a cancelled original invoice.");
    }
    static ReceivablesException alreadyReplaced() {
        return new ReceivablesException("INVOICE_ALREADY_REPLACED", 409, "The cancelled invoice already has a replacement.");
    }
}
