# 결정 출처 기록

면접 설명을 위해 저장소 기록으로 확인 가능한 결정을 요약한다. 출처 분류는 `사용자 지시` / `AI 제안·사용자 채택` / `AI 제안·기존 구현` / `확인 필요` 중 하나다.

| 결정내용 | 결정출처 | 근거경로 |
|---|---|---|
| 기존 전역 대사 도메인을 청구·입금 AR로 재해석하지 않고 별도 tenant 소유 데이터로 확장 | 사용자 지시 | [AGENTS.md](../../AGENTS.md), [ADR 0002](../adr/0002-receivables-decisions.md) |
| 후보와 사람의 확정을 분리하고 자동 확정을 제외 | 사용자 지시 | [ADR 0002](../adr/0002-receivables-decisions.md) |
| 배분 부모 행을 정렬 잠금하고 잠금 뒤 잔액을 재검사 | AI 제안·기존 구현 | [AllocationService](../../backend-java/src/main/java/com/smallbiz/reconciliation/receivables/allocation/AllocationService.java), [ADR 0002](../adr/0002-receivables-decisions.md) |
| 멱등 명령 결과와 원장·감사를 단일 트랜잭션에서 저장 | AI 제안·기존 구현 | [AllocationService](../../backend-java/src/main/java/com/smallbiz/reconciliation/receivables/allocation/AllocationService.java), [RECEIVABLES_ALLOCATION.md](../RECEIVABLES_ALLOCATION.md) |
| 신규 AR 서비스에서 `JdbcTemplate`을 사용한 이유와 legacy JPA 병존 근거 | 확인 필요 | [ReceivablesService](../../backend-java/src/main/java/com/smallbiz/reconciliation/receivables/ReceivablesService.java), [AllocationService](../../backend-java/src/main/java/com/smallbiz/reconciliation/receivables/allocation/AllocationService.java) |
| 배분 테이블·키 등 구체 DB 선택에 대한 과거 사용자 개별 승인 | 확인 필요 | [ADR 0002](../adr/0002-receivables-decisions.md) |
| 주요 업무 규칙·DB 관계·아키텍처 변경 전 사용자 승인 요구 | 사용자 지시 | [AGENTS.md](../../AGENTS.md), [ADR 0003](../adr/0003-ar-erp-workflow.md) |
| `ß`/`ſ` 정규화 결함 발견은 구현자 자체 검토의 재확인이지 독립 검토 발견이 아님 | AI 제안·기존 구현 | [등록 검토](../receivables-registration-review.md), [등록 통합 테스트](../../backend-java/src/test/java/com/smallbiz/reconciliation/receivables/ReceivablesRegistrationIntegrationTest.java) |
| 특정 AI 제안의 거절·수정 사례와 사용자 직접 확인 완료 여부 | 확인 필요 | [AI 작업 기록](AI_REVIEW_WORK.md) |
| 입금 등록의 응답 유실 복구 대안 A 구현을 사용자가 채택 | AI 제안·사용자 채택 | [ADR 0002](../adr/0002-receivables-decisions.md) |
