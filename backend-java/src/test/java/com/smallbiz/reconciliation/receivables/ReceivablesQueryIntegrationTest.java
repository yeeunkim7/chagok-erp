package com.smallbiz.reconciliation.receivables;

import com.smallbiz.reconciliation.receivables.allocation.AllocationService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.smallbiz.reconciliation.account.AccountException;
import com.smallbiz.reconciliation.account.AuthService;
import com.smallbiz.reconciliation.aireview.ai.AiCompletionClient;
import com.smallbiz.reconciliation.aireview.query.ReadOnlyReviewQueries;
import com.smallbiz.reconciliation.vendor.RejectDevelopmentDatabaseInitializer;
import java.util.ArrayList;
import java.util.HashMap;
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
class ReceivablesQueryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired ReceivablesQueryService service;
    @MockitoBean AiCompletionClient ai;
    @MockitoBean ReadOnlyReviewQueries queries;
    private final List<Owner> owners = new ArrayList<>();

    @BeforeEach void prepare() { SecurityContextHolder.clearContext(); reset(ai, queries); }
    @AfterEach void cleanupOwnRows() {
        SecurityContextHolder.clearContext();
        try { verifyNoInteractions(ai, queries); }
        finally {
            for (Owner owner : owners) {
                // No production cascade and no other test's rows are deleted.
                for (String table : List.of("receivable_allocation_command", "receivable_allocation_audit", "receivable_allocation_cancellation",
                        "receivable_allocation_line", "receivable_allocation_batch", "receivable_invoice_replacement", "receivable_invoice_cancellation_command", "receivable_invoice_cancellation", "receivable_registration_request", "receivable_invoice",
                        "receivable_payment", "receivable_counterparty"))
                    jdbc.update("DELETE FROM " + table + " WHERE tenant_id=?", owner.tenant());
                jdbc.update("DELETE FROM app_session WHERE account_id=?", owner.account());
                jdbc.update("DELETE FROM app_membership WHERE tenant_id=?", owner.tenant());
                jdbc.update("DELETE FROM app_tenant WHERE id=?", owner.tenant());
                jdbc.update("DELETE FROM app_account WHERE id=?", owner.account());
            }
        }
    }

    @Test void agingBoundariesCurrentSummaryAndCancellationAreConsistentAcrossPages() throws Exception {
        Owner owner=owner(); var cp=customer(owner,"AGING","A 거래처");
        for(int days:new int[]{0,1,30,31,60,61,90,91})
            document(owner,cp,"D"+days,"100",java.time.LocalDate.parse("2030-04-01").minusDays(days).toString());
        var paid=document(owner,cp,"PAID","100","2030-03-01");
        var partial=document(owner,cp,"PARTIAL","100","2030-03-31");
        var payment=payment(owner,"140");
        confirm(owner,"apply",List.of(line(payment,paid,"100"),line(payment,partial,"40")),null);
        var voided=document(owner,cp,"VOID","1000","2030-01-01");
        postJson(owner,"invoices/"+id(voided)+"/cancel","void",Map.of("reason","오입력"),200);
        var result=ledger(owner,"status=ALL&size=1");
        assertThat(result.path("allocationState").asText()).isEqualTo("CURRENT");
        assertThat(result.path("items").size()).isEqualTo(1);
        assertThat(result.path("hasNext").asBoolean()).isTrue();
        assertThat(result.path("totals").path("invoiceCount").asInt()).isEqualTo(10);
        assertMoney(result.path("totals"),"invoiceAmount","1000","allocatedAmount","140","outstandingAmount","860",
                "notDueAmount","100","overdueAmount","760","overdue1To30Amount","260",
                "overdue31To60Amount","200","overdue61To90Amount","200","overdueOver90Amount","100");
        var all=ledger(owner,"status=ALL&size=100");
        assertThat(all.path("totals")).isEqualTo(result.path("totals"));
        assertThat(all.path("items").size()).isEqualTo(10);
        for(var item:all.path("items")) assertThat(item.path("invoiceId").asText()).isNotEqualTo(id(voided));
        var second=ledger(owner,"status=ALL&size=1&page=1");
        assertThat(second.path("totals")).isEqualTo(result.path("totals"));
        assertThat(second.path("items").get(0).path("invoiceId")).isNotEqualTo(result.path("items").get(0).path("invoiceId"));
        var current=summary(owner,"2030-04-01");
        for(var field:List.of("invoiceAmount","allocatedAmount","outstandingAmount","overdueAmount"))
            assertThat(all.path("totals").path(field)).isEqualTo(current.path(field));
        var emptyPage=ledger(owner,"status=ALL&page=100&size=1");
        assertThat(emptyPage.path("items").size()).isZero();
        assertThat(emptyPage.path("totals")).isEqualTo(result.path("totals"));
        var byBucket=new java.util.HashMap<String,String>();
        for(var item:all.path("items")) {
            byBucket.put(item.path("invoiceNumber").asText(),item.path("agingBucket").asText());
            if(item.path("status").asText().equals("PAID")) assertThat(item.path("daysOverdue").asLong()).isZero();
        }
        assertThat(byBucket).containsEntry("D0","NOT_DUE").containsEntry("D1","OVERDUE_1_30")
                .containsEntry("D30","OVERDUE_1_30").containsEntry("D31","OVERDUE_31_60")
                .containsEntry("D60","OVERDUE_31_60").containsEntry("D61","OVERDUE_61_90")
                .containsEntry("D90","OVERDUE_61_90").containsEntry("D91","OVERDUE_OVER_90").containsEntry("PAID","SETTLED");
    }
    @Test void customerStatusDueAndLiteralSearchFiltersApplyBeforeGlobalTotalsAndEmptyIsNotPaid() throws Exception {
        Owner owner=owner();var a=customer(owner,"A","A 거래처");var b=customer(owner,"B","B 거래처");
        var first=document(owner,a,"INV_%_ONE","100","2030-03-01");
        var second=document(owner,a,"INV-TWO","200","2030-04-01");
        document(owner,b,"INV-THREE","300","2030-05-01");
        var payment=payment(owner,"40");confirm(owner,"part",List.of(line(payment,first,"40")),null);
        var filtered=ledger(owner,"counterpartyId="+id(a)+"&size=1");
        assertThat(filtered.path("totals").path("invoiceCount").asInt()).isEqualTo(2);
        assertMoney(filtered.path("totals"),"outstandingAmount","260","overdueAmount","60");
        assertMoney(ledger(owner,"status=PARTIAL").path("totals"),"outstandingAmount","60");
        assertMoney(ledger(owner,"status=UNPAID&dueFrom=2030-04-01&dueTo=2030-04-01").path("totals"),"outstandingAmount","200");
        var literal=mapper.readTree(mvc.perform(get(path(owner,"receivables/ledger"))
                .header("Authorization",owner.bearer()).param("asOf","2030-04-01").param("query","%_"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(literal.path("items").size()).isEqualTo(1);
        assertThat(literal.path("items").get(0).path("invoiceId").asText()).isEqualTo(id(first));
        assertThat(ledger(owner,"status=PAID").path("totals").path("invoiceCount").asInt()).isZero();
        var noData=ledger(owner,"query=no-such-invoice");
        assertThat(noData.path("totals").path("invoiceCount").asInt()).isZero();
        assertMoney(noData.path("totals"),"outstandingAmount","0");
        assertThat(noData.path("items").size()).isZero();
        assertThat(getJson(owner,"invoices/"+id(second))).isEqualTo(second);
    }
    @Test void everyReadChecksTenantAndSessionAndRejectsInvalidFiltersWithoutExposingForeignRecords() throws Exception {
        Owner a=owner(), b=owner();var cp=customer(b,"PRIVATE","다른 거래처");
        mvc.perform(get(path(a,"receivables/ledger")).param("asOf","2030-04-01")).andExpect(status().isUnauthorized());
        mvc.perform(get(path(b,"receivables/ledger")).header("Authorization",a.bearer()).param("asOf","2030-04-01")).andExpect(status().isForbidden());
        mvc.perform(get(path(a,"receivables/ledger")).header("Authorization",a.bearer()).param("asOf","2030-04-01").param("counterpartyId",id(cp)))
                .andExpect(status().isNotFound());
        for(var pair:List.of(List.of("status","CANCELLED"),List.of("page","-1"),List.of("size","101"),
                List.of("asOf","2030-02-30"),List.of("query","x".repeat(101)),List.of("query","\u0001"))) {
            var request = get(path(a,"receivables/ledger")).header("Authorization",a.bearer());
            if (!pair.get(0).equals("asOf")) request.param("asOf","2030-04-01");
            mvc.perform(request.param(pair.get(0),pair.get(1))).andExpect(status().isBadRequest());
        }
        mvc.perform(get(path(a,"receivables/ledger")).header("Authorization",a.bearer()).param("asOf","2030-04-01")
                .param("dueFrom","2030-04-02").param("dueTo","2030-04-01")).andExpect(status().isBadRequest());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(auth.authenticate(a.token()),null,List.of()));
        jdbc.update("DELETE FROM app_membership WHERE tenant_id=?",a.tenant());
        assertThatThrownBy(()->service.ledger(a.tenant(),"2030-04-01",null,"OPEN",null,null,null,0,20)).isInstanceOf(AccountException.class);
    }
    @Test void totalsAndPageKeepOneSnapshotWhenAConfirmationCommitsBetweenTheirQueries() throws Exception {
        Owner owner=owner();var cp=customer(owner,"SNAPSHOT","A 거래처");
        var invoice=document(owner,cp,"CONCURRENT","100","2030-03-01");var payment=payment(owner,"100");
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        try(var worker=Executors.newSingleThreadExecutor()) {
            doAnswer(call -> {
                String sql=call.getArgument(0,String.class);
                if(sql.contains("SELECT * FROM filtered ORDER BY due_date,id") && once.compareAndSet(false,true)) {
                    worker.submit(() -> confirm(owner,"between-queries",List.of(line(payment,invoice,"100")),null))
                            .get(15,TimeUnit.SECONDS);
                }
                return call.callRealMethod();
            }).when(jdbc).query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),any(Object[].class));
            var before=ledger(owner,"");
            assertThat(once.get()).isTrue();
            assertThat(before.path("totals").path("invoiceCount").asInt()).isEqualTo(1);
            assertThat(before.path("items").size()).isEqualTo(1);
            assertMoney(before.path("totals"),"outstandingAmount","100");
            assertMoney(before.path("items").get(0),"outstandingAmount","100");
            var after=ledger(owner,"");
            assertThat(after.path("items").size()).isZero();
            assertThat(after.path("totals").path("invoiceCount").asInt()).isZero();
        }
    }

    @Test void invalidUnderlyingBalanceNeverDisappearsSilentlyBehindOpenStatusFilter() throws Exception {
        Owner owner=owner();var cp=customer(owner,"BROKEN","A 거래처");
        var invoice=document(owner,cp,"CHECK","100","2030-03-01");var payment=payment(owner,"200");
        var batch=confirm(owner,"valid",List.of(line(payment,invoice,"50")),null);
        // Isolated test fixture simulates corruption by an out-of-band database writer.
        jdbc.update("UPDATE receivable_allocation_line SET amount=200 WHERE tenant_id=?",owner.tenant());
        mvc.perform(get(path(owner,"receivables/ledger")).header("Authorization",owner.bearer()).param("asOf","2030-04-01"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RECEIVABLE_STORAGE_UNAVAILABLE"));
        jdbc.update("UPDATE receivable_allocation_line SET amount=50 WHERE tenant_id=?",owner.tenant());
        var voided=document(owner,cp,"VOIDED","100","2030-03-01");
        postJson(owner,"invoices/"+id(voided)+"/cancel","void",Map.of("reason","오입력"),200);
        jdbc.update("INSERT INTO receivable_allocation_line (id,tenant_id,batch_id,line_number,payment_id,invoice_id,amount) VALUES (?,?,?,2,?,?,10)",
                UUID.randomUUID(),owner.tenant(),UUID.fromString(id(batch)),UUID.fromString(id(payment)),UUID.fromString(id(voided)));
        mvc.perform(get(path(owner,"receivables/ledger")).header("Authorization",owner.bearer()).param("asOf","2030-04-01"))
                .andExpect(status().isServiceUnavailable());
    }

    private JsonNode document(Owner owner, JsonNode cp, String number, String amount, String due) throws Exception {
        return postJson(owner,"invoices",UUID.randomUUID().toString(),Map.of("counterpartyId",id(cp),"invoiceNumber",number,
                "amount",amount,"issueDate","2020-01-01","dueDate",due),201);
    }
    private JsonNode customer(Owner owner,String code,String name) throws Exception {
        return postJson(owner,"counterparties",UUID.randomUUID().toString(),Map.of("code",code,"name",name),201);
    }
    private JsonNode ledger(Owner owner,String parameters) throws Exception {
        return getJson(owner,"receivables/ledger?asOf=2030-04-01&"+parameters);
    }
    private Owner owner() throws Exception {
        var signup = mapper.readTree(mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("login", "allocation-" + UUID.randomUUID(), "password", "password-123456", "tenantName", "Synthetic shop"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String token = signup.path("accessToken").asText();
        var me = mapper.readTree(mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var owner = new Owner(UUID.fromString(me.path("accountId").asText()), UUID.fromString(me.path("tenants").get(0).path("id").asText()), token);
        owners.add(owner);
        return owner;
    }
    private JsonNode invoice(Owner owner, String amount, String due) throws Exception {
        String key = UUID.randomUUID().toString();
        var cp = postJson(owner, "counterparties", key, Map.of("code", "C" + key.replace("-", "").substring(0, 20), "name", "Synthetic customer"), 201);
        return postJson(owner, "invoices", key, Map.of("counterpartyId", id(cp), "invoiceNumber", key, "amount", amount, "issueDate", "2025-01-01", "dueDate", due), 201);
    }
    private JsonNode payment(Owner owner, String amount) throws Exception {
        return postJson(owner, "payments", UUID.randomUUID().toString(), Map.of("amount", amount, "bookedDate", "2026-11-01", "payerName", "Different payer"), 201);
    }
    private JsonNode confirm(Owner owner, String key, List<Map<String, Object>> lines, String reason) throws Exception {
        return postJson(owner, "allocations", key, request(lines, reason), 201);
    }
    private JsonNode cancel(Owner owner, JsonNode batch, String key, String reason) throws Exception {
        return postJson(owner, "allocations/" + id(batch) + "/cancel", key, Map.of("reason", reason), 200);
    }
    private JsonNode invoiceBalance(Owner owner, JsonNode invoice, String date) throws Exception { return getJson(owner, "invoices/" + id(invoice) + "/balance?asOf=" + date); }
    private JsonNode paymentBalance(Owner owner, JsonNode payment) throws Exception { return getJson(owner, "payments/" + id(payment) + "/balance"); }
    private JsonNode summary(Owner owner, String date) throws Exception { return getJson(owner, "receivables/summary?asOf=" + date); }
    private JsonNode postJson(Owner owner, String suffix, String key, Map<String, Object> body, int status) throws Exception {
        return mapper.readTree(send(owner, suffix, key, body, status).andReturn().getResponse().getContentAsString());
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
    private JsonNode getJson(Owner owner, String suffix) throws Exception {
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
    private int count(Owner owner, String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=?", Integer.class, owner.tenant()); }
    private static String id(JsonNode row) { return row.path("id").asText(); }
    private static String path(Owner owner, String suffix) { return "/api/v1/tenants/" + owner.tenant() + "/" + suffix; }
    private static Map<String, Object> line(JsonNode pay, JsonNode invoice, String amount) { return Map.of("paymentId", id(pay), "invoiceId", id(invoice), "amount", amount); }
    private static Map<String, Object> request(List<Map<String, Object>> lines, String reason) {
        var request = new HashMap<String, Object>(); request.put("lines", lines); if (reason != null) request.put("reason", reason); return request;
    }
    private static void assertMoney(JsonNode value, String... fieldsAndValues) {
        for (int i = 0; i < fieldsAndValues.length; i += 2) {
            assertThat(value.path(fieldsAndValues[i]).isString()).isTrue();
            assertThat(value.path(fieldsAndValues[i]).asText()).isEqualTo(fieldsAndValues[i + 1]);
        }
    }
    private record Owner(UUID account, UUID tenant, String token) { String bearer() { return "Bearer " + token; } }
    private record Result(int status, JsonNode json) {}
}
