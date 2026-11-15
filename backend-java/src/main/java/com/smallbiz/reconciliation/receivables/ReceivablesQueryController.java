package com.smallbiz.reconciliation.receivables;

import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/receivables")
public class ReceivablesQueryController {
    private final ReceivablesQueryService service;
    public ReceivablesQueryController(ReceivablesQueryService service) { this.service=service; }
    @GetMapping("/ledger")
    public ReceivablesQueryService.Ledger ledger(@PathVariable UUID tenantId,@RequestParam String asOf,
            @RequestParam(required=false) UUID counterpartyId,@RequestParam(defaultValue="OPEN") String status,
            @RequestParam(required=false) String dueFrom,@RequestParam(required=false) String dueTo,
            @RequestParam(required=false) String query,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.ledger(tenantId,asOf,counterpartyId,status,dueFrom,dueTo,query,page,size);
    }
    @ExceptionHandler(ReceivablesException.class)
    public ResponseEntity<Map<String,String>> invalid(ReceivablesException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code",e.code(),"message",e.getMessage()));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String,String>> unavailable() {
        return ResponseEntity.status(503).body(Map.of("code","RECEIVABLE_STORAGE_UNAVAILABLE","message","Receivables storage is unavailable."));
    }
}
