package com.smallbiz.reconciliation.receivables;

import static com.smallbiz.reconciliation.receivables.ReceivablesValidation.*;
import com.smallbiz.reconciliation.account.AuthService;
import com.smallbiz.reconciliation.account.TenantAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Append-only correction facts; invoice registration originals remain untouched. */
@Service
public class InvoiceCancellationService {
    private final JdbcTemplate jdbc;
    private final TenantAccess tenants;
    private final AuthService auth;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final TransactionTemplate write;

    public InvoiceCancellationService(JdbcTemplate jdbc, TenantAccess tenants, AuthService auth, Clock clock,
            ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.tenants = tenants; this.auth = auth; this.clock = clock; this.mapper = mapper;
        write = new TransactionTemplate(transactionManager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public CancellationResponse cancel(UUID tenant, UUID invoice, String requestKey, Map<String, Object> body) {
        return write.execute(status -> {
            tenants.require(tenant);
            key(requestKey);
            fields(body, Set.of("reason"));
            var input = new Input(invoice, text(body, "reason", 1000, true));
            requireInvoice(tenant, invoice, false);
            UUID cancellationId = UUID.randomUUID();
            String hash = hash(input);
            int inserted = jdbc.update("""
                    INSERT INTO receivable_invoice_cancellation_command (tenant_id,request_key,request_hash,cancellation_id)
                    VALUES (?,?,?,?) ON CONFLICT (tenant_id,request_key) DO NOTHING
                    """, tenant, requestKey, hash, cancellationId);
            if (inserted == 0) {
                var previous = jdbc.queryForObject("""
                        SELECT request_hash,cancellation_id FROM receivable_invoice_cancellation_command
                        WHERE tenant_id=? AND request_key=?
                        """, (rs, row) -> new Stored(rs.getString("request_hash"), rs.getObject("cancellation_id", UUID.class)), tenant, requestKey);
                if (previous == null || !hash.equals(previous.hash())) throw ReceivablesException.keyConflict();
                return cancellationById(tenant, previous.id());
            }
            // Invoice-only locking never reverses allocation's payment-then-invoice order.
            // Confirmations recheck cancellation after acquiring this same parent lock.
            requireInvoice(tenant, invoice, true);
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT FROM receivable_invoice_cancellation WHERE tenant_id=? AND invoice_id=?)
                    """, Boolean.class, tenant, invoice))) throw ReceivablesException.alreadyCancelled();
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT FROM receivable_allocation_line l
                        LEFT JOIN receivable_allocation_cancellation c ON c.tenant_id=l.tenant_id AND c.batch_id=l.batch_id
                        WHERE l.tenant_id=? AND l.invoice_id=? AND c.id IS NULL
                    )
                    """, Boolean.class, tenant, invoice))) throw ReceivablesException.activeAllocations();
            UUID actor = auth.requireActiveSession().accountId();
            OffsetDateTime time = OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
            jdbc.update("""
                    INSERT INTO receivable_invoice_cancellation (id,tenant_id,invoice_id,reason,created_by,created_at)
                    VALUES (?,?,?,?,?,?)
                    """, cancellationId, tenant, invoice, input.reason(), actor, time);
            return cancellationById(tenant, cancellationId);
        });
    }

    public CancellationResponse get(UUID tenant, UUID invoice) {
        tenants.require(tenant);
        return one(jdbc.query("""
                SELECT id,tenant_id,invoice_id,reason,created_by,created_at FROM receivable_invoice_cancellation
                WHERE tenant_id=? AND invoice_id=?
                """, (rs, row) -> new CancellationResponse(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("invoice_id", UUID.class), rs.getString("reason"), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()), tenant, invoice));
    }

    public ReplacementResponse replacement(UUID tenant, UUID invoice) {
        tenants.require(tenant);
        return one(jdbc.query("""
                SELECT tenant_id,cancelled_invoice_id,replacement_invoice_id,created_by,created_at
                FROM receivable_invoice_replacement WHERE tenant_id=? AND cancelled_invoice_id=?
                """, (rs, row) -> new ReplacementResponse(rs.getObject("tenant_id", UUID.class),
                rs.getObject("cancelled_invoice_id", UUID.class), rs.getObject("replacement_invoice_id", UUID.class),
                rs.getObject("created_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class).toInstant()), tenant, invoice));
    }

    private void requireInvoice(UUID tenant, UUID invoice, boolean lock) {
        one(jdbc.query("SELECT id FROM receivable_invoice WHERE tenant_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> rs.getObject("id", UUID.class), tenant, invoice));
    }
    private CancellationResponse cancellationById(UUID tenant, UUID cancellation) {
        return one(jdbc.query("""
                SELECT id,tenant_id,invoice_id,reason,created_by,created_at FROM receivable_invoice_cancellation
                WHERE tenant_id=? AND id=?
                """, (rs, row) -> new CancellationResponse(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("invoice_id", UUID.class), rs.getString("reason"), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()), tenant, cancellation));
    }
    private String hash(Input input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(input).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static <T> T one(List<T> rows) {
        if (rows.isEmpty()) throw ReceivablesException.missing();
        return rows.getFirst();
    }
    private record Input(UUID invoiceId, String reason) {}
    private record Stored(String hash, UUID id) {}
    public record CancellationResponse(UUID id, UUID tenantId, UUID invoiceId, String reason, UUID createdBy, Instant createdAt) {}
    public record ReplacementResponse(UUID tenantId, UUID cancelledInvoiceId, UUID replacementInvoiceId, UUID createdBy, Instant createdAt) {}
}
