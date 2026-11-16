package com.smallbiz.reconciliation.receivables;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.smallbiz.reconciliation.account.AccountException;
import com.smallbiz.reconciliation.account.AuthService;
import com.smallbiz.reconciliation.aireview.ai.AiCompletionClient;
import com.smallbiz.reconciliation.aireview.query.ReadOnlyReviewQueries;
import com.smallbiz.reconciliation.vendor.RejectDevelopmentDatabaseInitializer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"AI_API_KEY=synthetic-key-present", "app.ai.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = RejectDevelopmentDatabaseInitializer.class)
class InvoiceCancellationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired InvoiceCancellationService service;
    @MockitoBean AiCompletionClient ai;
    @MockitoBean ReadOnlyReviewQueries queries;
    private final List<Owner> owners = new ArrayList<>();

    @BeforeEach void prepare() { SecurityContextHolder.clearContext(); reset(ai, queries); }
    @AfterEach void cleanupOwnRows() {
        SecurityContextHolder.clearContext();
        try { verifyNoInteractions(ai, queries); }
        finally {
            for (Owner owner : owners) {
                for (String table : List.of("receivable_matching_usage_event", "receivable_monthly_usage", "receivable_matching_run",
                        "receivable_invoice_replacement", "receivable_invoice_cancellation_command", "receivable_invoice_cancellation",
                        "receivable_allocation_command", "receivable_allocation_audit", "receivable_allocation_cancellation",
                        "receivable_allocation_line", "receivable_allocation_batch", "receivable_registration_request",
                        "receivable_invoice", "receivable_payment", "receivable_counterparty"))
                    jdbc.update("DELETE FROM " + table + " WHERE tenant_id=?", owner.tenant());
                jdbc.update("DELETE FROM app_session WHERE account_id=?", owner.account());
                jdbc.update("DELETE FROM app_membership WHERE tenant_id=?", owner.tenant());
                jdbc.update("DELETE FROM app_tenant WHERE id=?", owner.tenant());
                jdbc.update("DELETE FROM app_account WHERE id=?", owner.account());
            }
        }
    }

    @Test void cancellationPreservesOriginalAndRecordsOneReasonActorTimeWithNormalizedReplay() throws Exception {
        Owner owner = owner();
        var invoice = invoice(owner, "100000");
        Instant before = Instant.now().minusSeconds(1);
        var cancelled = cancel(owner, invoice, "fix", "  duplicate input  ");
        assertThat(cancelled.path("invoiceId").asText()).isEqualTo(id(invoice));
        assertThat(cancelled.path("tenantId").asText()).isEqualTo(owner.tenant().toString());
        assertThat(cancelled.path("reason").asText()).isEqualTo("duplicate input");
        assertThat(cancelled.path("createdBy").asText()).isEqualTo(owner.account().toString());
        assertThat(Instant.parse(cancelled.path("createdAt").asText())).isBetween(before, Instant.now().plusSeconds(1));
        assertThat(cancel(owner, invoice, "fix", "duplicate input")).isEqualTo(cancelled);
        assertThat(read(owner, "invoices/" + id(invoice) + "/cancellation")).isEqualTo(cancelled);
        assertThat(read(owner, "invoices/" + id(invoice))).isEqualTo(invoice);
        assertThat(read(owner, "invoices").path("items").get(0)).isEqualTo(invoice);
        assertThat(count(owner, "receivable_invoice_cancellation")).isEqualTo(1);
        assertThat(count(owner, "receivable_invoice_cancellation_command")).isEqualTo(1);
        // asOf still classifies overdue amounts in the CURRENT ledger; it never undoes a cancellation.
        for (String asOf : List.of("2020-01-01", "2026-11-02", "9999-12-31")) {
            var balance = read(owner, "invoices/" + id(invoice) + "/balance?asOf=" + asOf);
            assertThat(balance.path("status").asText()).isEqualTo("CANCELLED");
            money(balance, "amount", "100000", "allocatedAmount", "0", "outstandingAmount", "0", "overdueAmount", "0");
            var summary = read(owner, "receivables/summary?asOf=" + asOf);
            assertThat(summary.path("invoiceCount").asLong()).isZero();
            money(summary, "invoiceAmount", "0", "outstandingAmount", "0", "overdueAmount", "0");
        }
        send(owner, cancelPath(invoice), "other-key", Map.of("reason", "again"), 409)
                .andExpect(jsonPath("$.code").value("INVOICE_ALREADY_CANCELLED"));
        assertThat(count(owner, "receivable_invoice_cancellation_command")).isEqualTo(1);
    }

    @Test void activeAllocationsMustBeCancelledFirstAndOldConfirmationReplayRemainsHistorical() throws Exception {
        Owner owner = owner();
        var invoice = invoice(owner, "100000");
        var pay = payment(owner, "40000");
        var confirmation = allocation(invoice, pay, "40000");
        var batch = postJson(owner, "allocations", "confirm", confirmation, 201);
        send(owner, cancelPath(invoice), "cancel-invoice", Map.of("reason", "wrong amount"), 409)
                .andExpect(jsonPath("$.code").value("INVOICE_HAS_ACTIVE_ALLOCATIONS"));
        money(read(owner, "invoices/" + id(invoice) + "/balance?asOf=2026-11-02"), "outstandingAmount", "60000");
        assertThat(count(owner, "receivable_invoice_cancellation_command")).isZero();
        var reversal = postJson(owner, "allocations/" + id(batch) + "/cancel", "reverse", Map.of("reason", "undo allocation first"), 200);
        cancel(owner, invoice, "cancel-invoice", "wrong amount");
        assertThat(postJson(owner, "allocations", "confirm", confirmation, 201)).isEqualTo(batch);
        assertThat(postJson(owner, "allocations/" + id(batch) + "/cancel", "reverse", Map.of("reason", "undo allocation first"), 200)).isEqualTo(reversal);
        send(owner, "allocations", "new-confirm", confirmation, 409).andExpect(jsonPath("$.code").value("INVOICE_CANCELLED"));
        money(read(owner, "payments/" + id(pay) + "/balance"), "allocatedAmount", "0", "unallocatedAmount", "40000");
        var summary = read(owner, "receivables/summary?asOf=2026-11-02");
        money(summary, "invoiceAmount", "0", "allocatedAmount", "0", "outstandingAmount", "0", "paymentAmount", "40000", "unallocatedAmount", "40000");
        assertThat(summary.path("activeAllocationLineCount").asLong()).isZero();
        assertThat(count(owner, "receivable_allocation_audit")).isEqualTo(2);
        assertThat(count(owner, "receivable_allocation_command")).isEqualTo(2);
    }

    @Test void invalidCancellationInputsNeverReserveKeysAndNoBackdatedEffectiveDateIsAccepted() throws Exception {
        Owner owner = owner();
        var invoice = invoice(owner, "100");
        List<Map<String, Object>> bad = List.of(Map.of(), Map.of("reason", " "), Map.of("reason", 1),
                Map.of("reason", "x".repeat(1001)), Map.of("reason", "fix", "effectiveAt", "2020-01-01"),
                Map.of("reason", "fix", "cancelledDate", "2020-01-01"), Map.of("reason", "fix", "amount", "1"));
        int index = 0;
        for (var body : bad) send(owner, cancelPath(invoice), "invalid-" + index++, body, 400);
        mvc.perform(post(path(owner, cancelPath(invoice))).header("Authorization", owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"fix\"}"))
                .andExpect(status().isBadRequest());
        send(owner, cancelPath(invoice), "bad key", Map.of("reason", "fix"), 400);
        mvc.perform(get(path(owner, "invoices/" + id(invoice) + "/cancellation")).header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound());
        assertThat(count(owner, "receivable_invoice_cancellation")).isZero();
        assertThat(count(owner, "receivable_invoice_cancellation_command")).isZero();
    }

    @Test void newRoutesRequireAuthenticationMembershipAndConcealForeignAndUnknownInvoiceIds() throws Exception {
        Owner a = owner(), b = owner();
        var invoice = invoice(a, "100");
        cancel(a, invoice, "cancel", "fix");
        for (String suffix : List.of("invoices/" + id(invoice) + "/cancellation", "invoices/" + id(invoice) + "/replacement")) {
            mvc.perform(get(path(a, suffix))).andExpect(status().isUnauthorized());
            mvc.perform(get(path(a, suffix)).header("Authorization", b.bearer())).andExpect(status().isForbidden());
            var foreign = mvc.perform(get(path(b, suffix)).header("Authorization", b.bearer()))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            var unknown = mvc.perform(get(path(b, suffix.replace(id(invoice), UUID.randomUUID().toString()))).header("Authorization", b.bearer()))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertThat(foreign).isEqualTo(unknown);
        }
        mvc.perform(post(path(a, cancelPath(invoice))).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path(a, cancelPath(invoice))).header("Authorization", b.bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        var foreign = response(b, cancelPath(invoice), "foreign", Map.of("reason", "fix"));
        var unknown = response(b, "invoices/" + UUID.randomUUID() + "/cancel", "unknown", Map.of("reason", "fix"));
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(unknown).isEqualTo(foreign);
        assertThat(count(b, "receivable_invoice_cancellation_command")).isZero();
    }

    @Test void cancellationKeysAreBoundToInvoiceAndNormalizedReasonAndConcurrentReplaysCreateOneFact() throws Exception {
        Owner owner = owner();
        var first = invoice(owner, "100");
        var second = invoice(owner, "200");
        var results = parallel(
                () -> response(owner, cancelPath(first), "same", Map.of("reason", " fix ")),
                () -> response(owner, cancelPath(first), "same", Map.of("reason", "fix")));
        assertThat(results.getFirst().status()).isEqualTo(200);
        assertThat(results.get(1)).isEqualTo(results.getFirst());
        send(owner, cancelPath(first), "same", Map.of("reason", "different"), 409).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        send(owner, cancelPath(second), "same", Map.of("reason", "fix"), 409).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(count(owner, "receivable_invoice_cancellation")).isEqualTo(1);
        assertThat(count(owner, "receivable_invoice_cancellation_command")).isEqualTo(1);
        assertThat(read(owner, "invoices/" + id(second) + "/balance?asOf=2026-11-02").path("status").asText()).isEqualTo("UNPAID");
    }

    @Test void replacementUsesNewIdentityAndNumberWithOneImmutableLinkAndStableReplays() throws Exception {
        Owner owner = owner();
        var original = invoice(owner, "100000");
        var cancelled = cancel(owner, original, "cancel-original", "incorrect amount");
        var body = replacement(original, "CORRECTED", "120000");
        var child = postJson(owner, "invoices", "replacement", body, 201);
        assertThat(id(child)).isNotEqualTo(id(original));
        assertThat(child.path("invoiceNumber").asText()).isEqualTo("CORRECTED");
        assertThat(child.path("amount").asText()).isEqualTo("120000");
        assertThat(read(owner, "invoices/" + id(original))).isEqualTo(original);
        var link = read(owner, "invoices/" + id(original) + "/replacement");
        assertThat(link.path("cancelledInvoiceId").asText()).isEqualTo(id(original));
        assertThat(link.path("replacementInvoiceId").asText()).isEqualTo(id(child));
        assertThat(link.path("createdBy").asText()).isEqualTo(owner.account().toString());
        var normalized = new HashMap<>(body);
        normalized.put("invoiceNumber", " CORRECTED ");
        normalized.put("replacesInvoiceId", id(original).toUpperCase());
        assertThat(postJson(owner, "invoices", "replacement", normalized, 201)).isEqualTo(child);
        send(owner, "invoices", "another-replacement", replacement(original, "ANOTHER", "120000"), 409)
                .andExpect(jsonPath("$.code").value("INVOICE_ALREADY_REPLACED"));
        send(owner, "invoices", "replacement", replacement(original, "CHANGED", "120000"), 409)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(count(owner, "receivable_invoice")).isEqualTo(2);
        assertThat(count(owner, "receivable_invoice_replacement")).isEqualTo(1);
        var summary = read(owner, "receivables/summary?asOf=2026-11-02");
        assertThat(summary.path("invoiceCount").asLong()).isEqualTo(1);
        money(summary, "invoiceAmount", "120000", "outstandingAmount", "120000");
        // A second correction targets the cancelled child, never adds a second child to the original.
        cancel(owner, child, "cancel-child", "correct again");
        var next = postJson(owner, "invoices", "next", replacement(child, "CORRECTED-2", "110000"), 201);
        assertThat(id(next)).isNotEqualTo(id(child));
        assertThat(cancel(owner, original, "cancel-original", "incorrect amount")).isEqualTo(cancelled);
        assertThat(postJson(owner, "invoices", "replacement", body, 201)).isEqualTo(child);
        assertThat(read(owner, "invoices/" + id(original) + "/replacement")).isEqualTo(link);
    }

    @Test void replacementRequiresCancelledSameTenantOriginalAndRejectsOriginalNumberWithoutLeavingRows() throws Exception {
        Owner a = owner(), b = owner();
        var original = invoice(a, "100");
        send(a, "invoices", "not-cancelled", replacement(original, "NEW", "200"), 409)
                .andExpect(jsonPath("$.code").value("INVOICE_NOT_CANCELLED"));
        cancel(a, original, "cancel", "fix");
        send(a, "invoices", "reuse-number", replacement(original, original.path("invoiceNumber").asText(), "200"), 409)
                .andExpect(jsonPath("$.code").value("RECEIVABLE_DUPLICATE"));
        var bInvoice = invoice(b, "100");
        var foreign = replacement(bInvoice, "NEW", "200");
        foreign.put("replacesInvoiceId", id(original));
        send(b, "invoices", "foreign", foreign, 404);
        foreign.put("replacesInvoiceId", UUID.randomUUID().toString());
        send(b, "invoices", "unknown", foreign, 404);
        foreign.put("replacesInvoiceId", "invalid");
        send(b, "invoices", "invalid", foreign, 400);
        assertThat(count(a, "receivable_invoice")).isEqualTo(1);
        assertThat(count(b, "receivable_invoice")).isEqualTo(1);
        assertThat(count(a, "receivable_invoice_replacement")).isZero();
        assertThat(count(b, "receivable_invoice_replacement")).isZero();
    }

    @Test void missingAndNullReplacementKeepTheExactPreV14RegistrationHashAndResponse() throws Exception {
        Owner owner = owner();
        var cp = counterparty(owner);
        var body = invoiceBody(cp.path("id").asText(), "LEGACY", "100");
        var invoice = postJson(owner, "invoices", "legacy-key", body, 201);
        String oldJson = "{\"counterpartyId\":\"" + id(cp) + "\",\"invoiceNumber\":\"LEGACY\",\"amount\":100,\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-11-01\"}";
        String oldHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(oldJson.getBytes(StandardCharsets.UTF_8)));
        assertThat(jdbc.queryForObject("""
                SELECT request_hash FROM receivable_registration_request WHERE tenant_id=? AND operation='INVOICE' AND request_key='legacy-key'
                """, String.class, owner.tenant())).isEqualTo(oldHash);
        // Simulate a stored pre-V14 key and test both absent/null optional input forms.
        jdbc.update("UPDATE receivable_registration_request SET request_hash=? WHERE tenant_id=? AND operation='INVOICE' AND request_key='legacy-key'", oldHash, owner.tenant());
        assertThat(postJson(owner, "invoices", "legacy-key", body, 201)).isEqualTo(invoice);
        body.put("replacesInvoiceId", null);
        assertThat(postJson(owner, "invoices", "legacy-key", body, 201)).isEqualTo(invoice);
        cancel(owner, invoice, "cancel", "fix");
        assertThat(postJson(owner, "invoices", "legacy-key", body, 201)).isEqualTo(invoice);
        assertThat(read(owner, "invoices/" + id(invoice))).isEqualTo(invoice);
        assertThat(invoice.propertyNames()).containsExactlyInAnyOrder("id", "tenantId", "counterpartyId", "invoiceNumber", "amount", "currency",
                "issueDate", "dueDate", "sourceKind", "createdBy", "createdAt");
    }

    @Test void newMatchingRunsExcludeCancelledInvoicesButPreviouslySavedRunAndReplayRemainUnchanged() throws Exception {
        Owner owner = owner();
        var cancelledInvoice = invoice(owner, "100");
        invoice(owner, "200");
        payment(owner, "100");
        var range = Map.<String, Object>of("from", "2026-11-01", "to", "2026-11-30");
        var oldRun = postJson(owner, "matching-runs", "before", range, 201);
        assertThat(oldRun.path("sourceCounts").path("invoiceCount").asInt()).isEqualTo(2);
        cancel(owner, cancelledInvoice, "cancel", "fix");
        var newRun = postJson(owner, "matching-runs", "after", range, 201);
        assertThat(newRun.path("sourceCounts").path("invoiceCount").asInt()).isEqualTo(1);
        assertThat(newRun.path("invoices").toString()).doesNotContain(id(cancelledInvoice));
        assertThat(read(owner, "matching-runs/" + id(oldRun))).isEqualTo(oldRun);
        assertThat(postJson(owner, "matching-runs", "before", range, 201)).isEqualTo(oldRun);
    }

    @Test void competingCancellationAndConfirmationShareParentLockAndCannotLeaveAnActiveCancelledInvoice() throws Exception {
        Owner owner = owner();
        var invoice = invoice(owner, "100");
        var pay = payment(owner, "100");
        var results = parallel(
                () -> response(owner, cancelPath(invoice), "cancel", Map.of("reason", "fix")),
                () -> response(owner, "allocations", "confirm", allocation(invoice, pay, "100")));
        assertThat(results.stream().map(Result::status)).contains(409);
        var balance = read(owner, "invoices/" + id(invoice) + "/balance?asOf=2026-11-02");
        if (results.getFirst().status() == 200) {
            assertThat(results.get(1).status()).isEqualTo(409);
            assertThat(results.get(1).json().path("code").asText()).isEqualTo("INVOICE_CANCELLED");
            assertThat(balance.path("status").asText()).isEqualTo("CANCELLED");
            money(balance, "allocatedAmount", "0", "outstandingAmount", "0");
            assertThat(count(owner, "receivable_allocation_batch")).isZero();
        } else {
            assertThat(results.getFirst().json().path("code").asText()).isEqualTo("INVOICE_HAS_ACTIVE_ALLOCATIONS");
            assertThat(results.get(1).status()).isEqualTo(201);
            assertThat(balance.path("status").asText()).isEqualTo("PAID");
            assertThat(count(owner, "receivable_invoice_cancellation")).isZero();
            assertThat(count(owner, "receivable_invoice_cancellation_command")).isZero();
        }
    }

    @Test void competingReplacementKeysCreateOnlyOneChildAndNoOrphanRegistration() throws Exception {
        Owner owner = owner();
        var original = invoice(owner, "100");
        cancel(owner, original, "cancel", "fix");
        var results = parallel(
                () -> response(owner, "invoices", "one", replacement(original, "ONE", "110")),
                () -> response(owner, "invoices", "two", replacement(original, "TWO", "120")));
        assertThat(results.stream().map(Result::status)).containsExactlyInAnyOrder(201, 409);
        assertThat(results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().json().path("code").asText()).isEqualTo("INVOICE_ALREADY_REPLACED");
        assertThat(count(owner, "receivable_invoice")).isEqualTo(2);
        assertThat(count(owner, "receivable_invoice_replacement")).isEqualTo(1);
        assertThat(count(owner, "receivable_registration_request")).isEqualTo(3); // counterparty, original, replacement
    }

    @Test void competingCancellationKeysCreateOneFactAndConcurrentReplacementReplayReturnsOneChild() throws Exception {
        Owner owner = owner();
        var original = invoice(owner, "100");
        var cancellations = parallel(
                () -> response(owner, cancelPath(original), "one", Map.of("reason", "fix")),
                () -> response(owner, cancelPath(original), "two", Map.of("reason", "fix")));
        assertThat(cancellations.stream().map(Result::status)).containsExactlyInAnyOrder(200, 409);
        assertThat(count(owner, "receivable_invoice_cancellation")).isEqualTo(1);
        assertThat(count(owner, "receivable_invoice_cancellation_command")).isEqualTo(1);
        var body = replacement(original, "CHILD", "200");
        var replacements = parallel(
                () -> response(owner, "invoices", "same", body),
                () -> response(owner, "invoices", "same", body));
        assertThat(replacements.getFirst().status()).isEqualTo(201);
        assertThat(replacements.get(1)).isEqualTo(replacements.getFirst());
        assertThat(count(owner, "receivable_invoice")).isEqualTo(2);
        assertThat(count(owner, "receivable_invoice_replacement")).isEqualTo(1);
    }

    @Test void cancelledInvoiceInMultiLineConfirmationRollsBackAllLinesAndCorruptActiveFactsFailClosed() throws Exception {
        Owner owner = owner();
        var cancelled = invoice(owner, "100");
        var active = invoice(owner, "100");
        var pay = payment(owner, "200");
        cancel(owner, cancelled, "cancel", "fix");
        var body = Map.<String, Object>of("lines", List.of(
                Map.of("invoiceId", id(active), "paymentId", id(pay), "amount", "100"),
                Map.of("invoiceId", id(cancelled), "paymentId", id(pay), "amount", "100")));
        send(owner, "allocations", "confirm", body, 409).andExpect(jsonPath("$.code").value("INVOICE_CANCELLED"));
        assertThat(count(owner, "receivable_allocation_command")).isZero();
        assertThat(count(owner, "receivable_allocation_line")).isZero();
        postJson(owner, "allocations", "confirm", allocation(active, pay, "100"), 201);
        // Direct SQL simulates corruption by a writer outside the approved service path.
        jdbc.update("""
                INSERT INTO receivable_invoice_cancellation (id,tenant_id,invoice_id,reason,created_by,created_at)
                VALUES (?,?,?,'synthetic invariant violation',?,CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), owner.tenant(), UUID.fromString(id(active)), owner.account());
        for (String suffix : List.of("invoices/" + id(active) + "/balance?asOf=2026-11-02", "receivables/summary?asOf=2026-11-02"))
            mvc.perform(get(path(owner, suffix)).header("Authorization", owner.bearer()))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ALLOCATION_STORAGE_UNAVAILABLE"));
    }

    @Test void failedFactAndLinkWritesRollBackReservedKeysAndNewInvoicesSoSameKeysCanRetry() throws Exception {
        Owner owner = owner();
        var original = invoice(owner, "100");
        jdbc.execute("ALTER TABLE receivable_invoice_cancellation ADD CONSTRAINT ck_test_invoice_cancel_failure CHECK (false) NOT VALID");
        try {
            send(owner, cancelPath(original), "cancel", Map.of("reason", "fix"), 503);
            assertThat(count(owner, "receivable_invoice_cancellation")).isZero();
            assertThat(count(owner, "receivable_invoice_cancellation_command")).isZero();
        } finally { jdbc.execute("ALTER TABLE receivable_invoice_cancellation DROP CONSTRAINT ck_test_invoice_cancel_failure"); }
        cancel(owner, original, "cancel", "fix");
        var body = replacement(original, "REPLACED", "200");
        jdbc.execute("ALTER TABLE receivable_invoice_replacement ADD CONSTRAINT ck_test_invoice_link_failure CHECK (false) NOT VALID");
        try {
            send(owner, "invoices", "replacement", body, 503);
            assertThat(count(owner, "receivable_invoice")).isEqualTo(1);
            assertThat(count(owner, "receivable_invoice_replacement")).isZero();
            assertThat(count(owner, "receivable_registration_request")).isEqualTo(2);
        } finally { jdbc.execute("ALTER TABLE receivable_invoice_replacement DROP CONSTRAINT ck_test_invoice_link_failure"); }
        var child = postJson(owner, "invoices", "replacement", body, 201);
        assertThat(read(owner, "invoices/" + id(original) + "/replacement").path("replacementInvoiceId").asText()).isEqualTo(id(child));
    }

    @Test void compositeForeignKeysRejectCrossTenantFactsAndReplacementLinksAndUncancelledTargets() throws Exception {
        Owner a = owner(), b = owner();
        var original = invoice(a, "100");
        var child = invoice(b, "200");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO receivable_invoice_cancellation (id,tenant_id,invoice_id,reason,created_by,created_at)
                VALUES (?,?,?,'cross tenant',?,CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), b.tenant(), UUID.fromString(id(original)), b.account()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO receivable_invoice_replacement (tenant_id,cancelled_invoice_id,replacement_invoice_id,created_by,created_at)
                VALUES (?,?,?,?,CURRENT_TIMESTAMP)
                """, a.tenant(), UUID.fromString(id(original)), UUID.fromString(id(child)), a.account()))
                .isInstanceOf(DataIntegrityViolationException.class);
        cancel(a, original, "cancel", "fix");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO receivable_invoice_replacement (tenant_id,cancelled_invoice_id,replacement_invoice_id,created_by,created_at)
                VALUES (?,?,?,?,CURRENT_TIMESTAMP)
                """, a.tenant(), UUID.fromString(id(original)), UUID.fromString(id(child)), a.account()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count(a, "receivable_invoice_replacement")).isZero();
    }

    @Test void allCancellationServiceEntriesRecheckMembershipAndRevokedSession() throws Exception {
        Owner a = owner(), b = owner();
        assertThatThrownBy(() -> service.get(a.tenant(), UUID.randomUUID())).isInstanceOf(AccountException.class);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(auth.authenticate(a.token()), null, List.of()));
        SecurityContextHolder.setContext(context);
        assertThatThrownBy(() -> service.cancel(b.tenant(), UUID.randomUUID(), "key", Map.of()))
                .isInstanceOfSatisfying(AccountException.class, ex -> assertThat(ex.status()).isEqualTo(403));
        jdbc.update("UPDATE app_session SET revoked_at_epoch=1 WHERE account_id=?", a.account());
        for (Runnable action : List.<Runnable>of(
                () -> service.cancel(a.tenant(), UUID.randomUUID(), "key", Map.of()),
                () -> service.get(a.tenant(), UUID.randomUUID()),
                () -> service.replacement(a.tenant(), UUID.randomUUID())))
            assertThatThrownBy(action::run).isInstanceOfSatisfying(AccountException.class, ex -> assertThat(ex.status()).isEqualTo(401));
        assertThat(count(a, "receivable_invoice_cancellation_command")).isZero();
    }

    private Owner owner() throws Exception {
        var signup = mapper.readTree(mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("login", "invoice-cancel-" + UUID.randomUUID(), "password", "password-123456", "tenantName", "Synthetic shop"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String token = signup.path("accessToken").asText();
        var me = mapper.readTree(mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var owner = new Owner(UUID.fromString(me.path("accountId").asText()), UUID.fromString(me.path("tenants").get(0).path("id").asText()), token);
        owners.add(owner);
        return owner;
    }
    private JsonNode counterparty(Owner owner) throws Exception {
        String key = UUID.randomUUID().toString();
        return postJson(owner, "counterparties", key, Map.of("code", "C" + key.replace("-", "").substring(0, 20), "name", "Synthetic customer"), 201);
    }
    private JsonNode invoice(Owner owner, String amount) throws Exception {
        return postJson(owner, "invoices", UUID.randomUUID().toString(), invoiceBody(id(counterparty(owner)), UUID.randomUUID().toString(), amount), 201);
    }
    private static HashMap<String, Object> invoiceBody(String counterparty, String number, String amount) {
        return new HashMap<>(Map.of("counterpartyId", counterparty, "invoiceNumber", number, "amount", amount, "issueDate", "2026-01-01", "dueDate", "2026-11-01"));
    }
    private static HashMap<String, Object> replacement(JsonNode original, String number, String amount) {
        var body = invoiceBody(original.path("counterpartyId").asText(), number, amount);
        body.put("replacesInvoiceId", id(original));
        return body;
    }
    private JsonNode payment(Owner owner, String amount) throws Exception {
        return postJson(owner, "payments", UUID.randomUUID().toString(), Map.of("amount", amount, "bookedDate", "2026-11-01", "payerName", "Synthetic payer"), 201);
    }
    private JsonNode cancel(Owner owner, JsonNode invoice, String key, String reason) throws Exception {
        return postJson(owner, cancelPath(invoice), key, Map.of("reason", reason), 200);
    }
    private static String cancelPath(JsonNode invoice) { return "invoices/" + id(invoice) + "/cancel"; }
    private static Map<String, Object> allocation(JsonNode invoice, JsonNode pay, String amount) {
        return Map.of("lines", List.of(Map.of("invoiceId", id(invoice), "paymentId", id(pay), "amount", amount)));
    }
    private JsonNode postJson(Owner owner, String suffix, String key, Map<String, Object> body, int expected) throws Exception {
        return mapper.readTree(send(owner, suffix, key, body, expected).andReturn().getResponse().getContentAsString());
    }
    private org.springframework.test.web.servlet.ResultActions send(Owner owner, String suffix, String key, Map<String, Object> body, int expected) throws Exception {
        return mvc.perform(post(path(owner, suffix)).header("Authorization", owner.bearer()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andExpect(header().string("Cache-Control", "no-store"));
    }
    private Result response(Owner owner, String suffix, String key, Map<String, Object> body) throws Exception {
        var result = mvc.perform(post(path(owner, suffix)).header("Authorization", owner.bearer()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body))).andReturn().getResponse();
        return new Result(result.getStatus(), mapper.readTree(result.getContentAsString()));
    }
    private JsonNode read(Owner owner, String suffix) throws Exception {
        return mapper.readTree(mvc.perform(get(path(owner, suffix)).header("Authorization", owner.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private List<Result> parallel(Callable<Result> first, Callable<Result> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return first.call(); });
            var b = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return second.call(); });
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }
    private int count(Owner owner, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=?", Integer.class, owner.tenant());
    }
    private static void money(JsonNode row, String... fields) {
        for (int index = 0; index < fields.length; index += 2) {
            assertThat(row.path(fields[index]).isString()).isTrue();
            assertThat(row.path(fields[index]).asText()).isEqualTo(fields[index + 1]);
        }
    }
    private static String id(JsonNode row) { return row.path("id").asText(); }
    private static String path(Owner owner, String suffix) { return "/api/v1/tenants/" + owner.tenant() + "/" + suffix; }
    private record Owner(UUID account, UUID tenant, String token) { String bearer() { return "Bearer " + token; } }
    private record Result(int status, JsonNode json) {}
}
