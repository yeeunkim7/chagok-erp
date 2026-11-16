-- Input corrections preserve invoice originals and append current-ledger facts.
CREATE TABLE receivable_invoice_cancellation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES app_tenant(id),
    invoice_id UUID NOT NULL,
    reason VARCHAR(1000) NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 1000),
    created_by UUID NOT NULL REFERENCES app_account(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, invoice_id),
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES receivable_invoice(tenant_id, id)
);
CREATE TABLE receivable_invoice_cancellation_command (
    tenant_id UUID NOT NULL REFERENCES app_tenant(id),
    request_key VARCHAR(100) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    cancellation_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, request_key),
    FOREIGN KEY (tenant_id, cancellation_id) REFERENCES receivable_invoice_cancellation(tenant_id, id)
        DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE receivable_invoice_replacement (
    tenant_id UUID NOT NULL REFERENCES app_tenant(id),
    cancelled_invoice_id UUID NOT NULL,
    replacement_invoice_id UUID NOT NULL,
    created_by UUID NOT NULL REFERENCES app_account(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, cancelled_invoice_id),
    UNIQUE (tenant_id, replacement_invoice_id),
    FOREIGN KEY (tenant_id, cancelled_invoice_id)
        REFERENCES receivable_invoice_cancellation(tenant_id, invoice_id),
    FOREIGN KEY (tenant_id, replacement_invoice_id) REFERENCES receivable_invoice(tenant_id, id),
    CHECK (cancelled_invoice_id <> replacement_invoice_id)
);
