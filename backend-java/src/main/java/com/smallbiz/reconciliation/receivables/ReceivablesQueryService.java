package com.smallbiz.reconciliation.receivables;

import com.smallbiz.reconciliation.account.TenantAccess;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Current open-item ledger; asOf only classifies lateness, never reconstructs history. */
@Service
public class ReceivablesQueryService {
    private final JdbcTemplate jdbc;
    private final TenantAccess tenants;
    private final TransactionTemplate read;
    public ReceivablesQueryService(JdbcTemplate jdbc, TenantAccess tenants, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.tenants = tenants;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Ledger ledger(UUID tenant, String asOf, UUID counterparty, String status, String dueFrom,
            String dueTo, String query, int page, int size) {
        return read.execute(tx -> {
            tenants.require(tenant);
            LocalDate cutoff = ReceivablesValidation.date(asOf);
            ReceivablesValidation.page(page, size);
            if (status == null || !Set.of("OPEN", "UNPAID", "PARTIAL", "PAID", "ALL").contains(status))
                throw ReceivablesException.invalid();
            LocalDate from = dueFrom == null ? null : ReceivablesValidation.date(dueFrom);
            LocalDate to = dueTo == null ? null : ReceivablesValidation.date(dueTo);
            if (from != null && to != null && from.isAfter(to)) throw ReceivablesException.invalid();
            String search = query == null ? "" : query.strip();
            if (search.codePointCount(0, search.length()) > 100 || search.codePoints().anyMatch(Character::isISOControl))
                throw ReceivablesException.invalid();
            if (counterparty != null && jdbc.queryForObject(
                    "SELECT count(*) FROM receivable_counterparty WHERE tenant_id=? AND id=?",
                    Integer.class, tenant, counterparty) != 1) throw ReceivablesException.missing();
            var arguments = new ArrayList<Object>();
            arguments.add(tenant); arguments.add(tenant); arguments.add(cutoff);
            StringBuilder scope = new StringBuilder(" WHERE true");
            if (counterparty != null) { scope.append(" AND counterparty_id=?"); arguments.add(counterparty); }
            if (from != null) { scope.append(" AND due_date>=?"); arguments.add(from); }
            if (to != null) { scope.append(" AND due_date<=?"); arguments.add(to); }
            if (!search.isEmpty()) {
                scope.append(" AND (position(lower(?) in lower(invoice_number))>0 OR position(lower(?) in lower(counterparty_name))>0 OR position(lower(?) in lower(counterparty_code))>0)");
                arguments.add(search); arguments.add(search); arguments.add(search);
            }
            String statusClause = status.equals("ALL") ? "" : status.equals("OPEN") ? " WHERE outstanding>0" : " WHERE item_status=?";
            if (!status.equals("ALL") && !status.equals("OPEN")) arguments.add(status);
            String cte = """
                    WITH invalid_cancellations AS (
                        SELECT l.id FROM receivable_allocation_line l
                        JOIN receivable_invoice_cancellation ic ON ic.tenant_id=l.tenant_id AND ic.invoice_id=l.invoice_id
                        LEFT JOIN receivable_allocation_cancellation ac ON ac.tenant_id=l.tenant_id AND ac.batch_id=l.batch_id
                        WHERE l.tenant_id=? AND ac.id IS NULL
                    ), amounts AS (
                        SELECT i.id,i.counterparty_id,cp.code AS counterparty_code,cp.name AS counterparty_name,
                            i.invoice_number,i.amount,i.issue_date,i.due_date,
                            coalesce(sum(l.amount) FILTER (WHERE ac.id IS NULL),0) AS allocated
                        FROM receivable_invoice i
                        JOIN receivable_counterparty cp ON cp.tenant_id=i.tenant_id AND cp.id=i.counterparty_id
                        LEFT JOIN receivable_allocation_line l ON l.tenant_id=i.tenant_id AND l.invoice_id=i.id
                        LEFT JOIN receivable_allocation_cancellation ac ON ac.tenant_id=l.tenant_id AND ac.batch_id=l.batch_id
                        WHERE i.tenant_id=? AND NOT EXISTS (
                            SELECT 1 FROM receivable_invoice_cancellation ic WHERE ic.tenant_id=i.tenant_id AND ic.invoice_id=i.id)
                        GROUP BY i.id,cp.id
                    ), dated AS (
                        SELECT *,amount-allocated AS outstanding,?::date AS as_of,
                            CASE WHEN allocated=0 THEN 'UNPAID' WHEN allocated=amount THEN 'PAID' ELSE 'PARTIAL' END AS item_status
                        FROM amounts
                    ), scoped AS (SELECT * FROM dated
                    """ + scope + "), filtered AS (SELECT * FROM scoped" + statusClause + ") ";
            Totals totals = jdbc.queryForObject(cte + """
                    SELECT count(*) AS invoice_count,coalesce(sum(amount),0) AS invoice_amount,
                        coalesce(sum(allocated),0) AS allocated_amount,coalesce(sum(outstanding),0) AS outstanding_amount,
                        coalesce(sum(outstanding) FILTER (WHERE due_date<as_of),0) AS overdue_amount,
                        coalesce(sum(outstanding) FILTER (WHERE due_date>=as_of),0) AS not_due_amount,
                        coalesce(sum(outstanding) FILTER (WHERE as_of-due_date BETWEEN 1 AND 30),0) AS d1_30,
                        coalesce(sum(outstanding) FILTER (WHERE as_of-due_date BETWEEN 31 AND 60),0) AS d31_60,
                        coalesce(sum(outstanding) FILTER (WHERE as_of-due_date BETWEEN 61 AND 90),0) AS d61_90,
                        coalesce(sum(outstanding) FILTER (WHERE as_of-due_date>90),0) AS d91,
                        (SELECT count(*) FROM scoped WHERE allocated<0 OR allocated>amount) + (SELECT count(*) FROM invalid_cancellations) AS invalid
                    FROM filtered
                    """, (rs, n) -> {
                        if (rs.getLong("invalid") != 0) throw new ReceivablesException("RECEIVABLE_STORAGE_UNAVAILABLE",503,"Receivables storage is unavailable.");
                        return new Totals(rs.getLong("invoice_count"), money(rs,"invoice_amount"),money(rs,"allocated_amount"),
                                money(rs,"outstanding_amount"),money(rs,"overdue_amount"),money(rs,"not_due_amount"),
                                money(rs,"d1_30"),money(rs,"d31_60"),money(rs,"d61_90"),money(rs,"d91"));
                    }, arguments.toArray());
            var rowArguments = new ArrayList<>(arguments); rowArguments.add(size + 1); rowArguments.add((long)page*size);
            var rows = jdbc.query(cte + "SELECT * FROM filtered ORDER BY due_date,id LIMIT ? OFFSET ?", (rs,n) -> {
                String remaining = money(rs,"outstanding");
                long days = remaining.equals("0") ? 0 : Math.max(0,
                        java.time.temporal.ChronoUnit.DAYS.between(rs.getObject("due_date",LocalDate.class),cutoff));
                String bucket = remaining.equals("0") ? "SETTLED" : days==0 ? "NOT_DUE" : days<=30 ? "OVERDUE_1_30" : days<=60 ? "OVERDUE_31_60" : days<=90 ? "OVERDUE_61_90" : "OVERDUE_OVER_90";
                return new Item(rs.getObject("id",UUID.class),rs.getObject("counterparty_id",UUID.class),
                        rs.getString("counterparty_code"),rs.getString("counterparty_name"),rs.getString("invoice_number"),
                        money(rs,"amount"),money(rs,"allocated"),remaining,rs.getObject("issue_date",LocalDate.class),
                        rs.getObject("due_date",LocalDate.class),rs.getString("item_status"),days,bucket);
            }, rowArguments.toArray());
            return new Ledger(tenant,cutoff,"CURRENT",new Filter(counterparty,status,from,to,search),
                    List.copyOf(rows.subList(0,Math.min(size,rows.size()))),page,size,rows.size()>size,totals);
        });
    }
    private static String money(ResultSet rs,String field) throws SQLException { return rs.getBigDecimal(field).toBigIntegerExact().toString(); }
    public record Filter(UUID counterpartyId,String status,LocalDate dueFrom,LocalDate dueTo,String query) {}
    public record Item(UUID invoiceId,UUID counterpartyId,String counterpartyCode,String counterpartyName,String invoiceNumber,
            String amount,String allocatedAmount,String outstandingAmount,LocalDate issueDate,LocalDate dueDate,
            String status,long daysOverdue,String agingBucket) {}
    public record Totals(long invoiceCount,String invoiceAmount,String allocatedAmount,String outstandingAmount,String overdueAmount,
            String notDueAmount,String overdue1To30Amount,String overdue31To60Amount,String overdue61To90Amount,String overdueOver90Amount) {}
    public record Ledger(UUID tenantId,LocalDate asOf,String allocationState,Filter filters,List<Item> items,
            int page,int size,boolean hasNext,Totals totals) {}
}
