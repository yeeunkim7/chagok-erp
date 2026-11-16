# 면접 노트: 매출채권 배분 구현

코드와 저장소 문서에서 확인되는 사실만 정리했다. 내 경험이나 결정으로 확인되지 않는 내용은 `확인 필요`로 표시한다. 면접 답변 전에는 링크된 메서드와 테스트를 다시 열어 설명한다.

## A. 기술 질문

### A1. 입금·청구 부모 잠금 순서와 데드락

`AllocationService.lockParents`는 관련 입금 UUID를 정렬해 `FOR UPDATE`한 뒤 청구 UUID를 정렬해 잠근다. 구현 목적은 확정과 취소가 동일한 순서로 부모를 잠가 서로 반대인 입력 순서가 교착을 만들 가능성을 낮추는 것이다. 잠금 없이 합계만 검사하면 동시 요청이 같은 잔액을 소비할 수 있다. 잠금 순서는 구현에서 확인되지만 이를 택한 과거 설계 논의는 기록에 없으므로, 역사적인 선택 이유는 확인 필요다. 정렬은 모든 데드락을 제거한다고 보장하지 않는다. [AllocationService.lockParents](../backend-java/src/main/java/com/smallbiz/reconciliation/receivables/allocation/AllocationService.java) · [동시성 계약](RECEIVABLES_ALLOCATION.md)

### A2. `FOR UPDATE`와 `@Version`, DB 제약의 선택

현재 구현은 부모 행 `FOR UPDATE` 잠금 후 활성 배분 합계를 읽어 Java에서 재검증한다. 비관적 잠금은 부모 잔액을 소비하는 트랜잭션을 직렬화하는 방식이다. 대안인 `@Version` 낙관적 잠금은 경쟁 감지를 위해 부모 행의 version을 실제 갱신해야 한다. 자식 배분 행만 삽입하고 부모 version을 갱신하지 않으면 부모 row version 충돌이 발생하지 않는다. CAS도 같은 갱신 조건으로 충돌을 감지할 수 있지만, 충돌 응답·재시도 정책이 필요하다. DB FK/단일 행 `CHECK`는 참조 무결성이나 한 행의 범위는 지킬 수 있지만 여러 배분 자식의 합계를 제한하는 일반적인 cross-row `CHECK`로는 쓸 수 없다. 현재는 복합 FK 등 DB 제약과 부모 잠금 뒤 SQL 합계 및 Java 검사를 함께 쓴다. 이 대안들을 당시 비교했다거나 이 중 하나를 선택한 역사적 이유는 확인되지 않는다. [lockAndValidate / checkAvailable](../backend-java/src/main/java/com/smallbiz/reconciliation/receivables/allocation/AllocationService.java) · [ADR 0002](adr/0002-receivables-decisions.md)

### A3. 잠금 전 잔액 검사에서 생기는 경쟁 조건

잠금 전 잔액 합계를 신뢰하면 두 트랜잭션이 같은 잔액을 각각 가용하다고 판단해 초과 배분할 수 있다. 현재 `lockAndValidate`는 부모 행 잠금을 얻은 뒤 별도 SQL로 청구 취소를 확인하고, 이어 `checkAvailable`에서 활성 배분 합계를 다시 읽는다. 쓰기 트랜잭션은 `READ_COMMITTED`이므로 잠금 대기 중 선행 트랜잭션이 커밋하면 후속 statement가 최신 활성 합계를 읽어 다시 검사한다. 구현 목적은 읽은 잔액과 실제 소비 사이의 경쟁을 차단하는 것이고, 과거 설계 이유에 대한 기록은 없다. [AllocationService](../backend-java/src/main/java/com/smallbiz/reconciliation/receivables/allocation/AllocationService.java) · [AllocationIntegrationTest](../backend-java/src/test/java/com/smallbiz/reconciliation/receivables/allocation/AllocationIntegrationTest.java)

### A4. 멱등키와 같은 키·다른 본문

배분 명령은 `receivable_allocation_command`에 tenant·operation·key로 예약하고 정규화 요청의 SHA-256 및 결과 audit ID를 저장한다. 구현 목적은 응답 유실 뒤 같은 키·본문을 다시 보내도 최초 결과를 재생하고, 실패한 원장 쓰기와 예약이 함께 rollback되게 하는 것이다. 같은 키·다른 본문은 `409 IDEMPOTENCY_CONFLICT`다. 키 없이 재요청 때마다 처리하면 중복 원장이 생길 수 있고, 클라이언트 메모리만 기억하면 앱 재시작 뒤 재생을 보장하지 못한다. 현재 서버 방식의 과거 선택 이유·당시 비교 기록은 확인되지 않는다. [AllocationService.reserve](../backend-java/src/main/java/com/smallbiz/reconciliation/receivables/allocation/AllocationService.java) · [멱등성 계약](RECEIVABLES_ALLOCATION.md)

### A5. 합계·목록의 일관성

배분 상세·목록은 다중 쿼리 사이 일관성을 위해 read-only `REPEATABLE_READ`로 실행한다. 별도로 거래처 원장 `ReceivablesQueryService.ledger`도 totals와 페이지 항목을 각각 조회하므로 같은 격리수준을 사용한다. `totalsAndPageKeepOneSnapshotWhenAConfirmationCommitsBetweenTheirQueries`는 totals 조회 뒤 페이지 조회 전에 별도 worker thread가 배분 확정을 commit하도록 구성한다. 같은 ledger 응답의 totals와 item은 이전 snapshot의 잔액 100을 함께 반환하고, 다음 요청은 확정된 결과를 반영해 항목이 0건임을 확인한다. 대안인 기본 `READ_COMMITTED`에서는 statement별 snapshot이 달라 응답 내부 totals/page가 어긋날 수 있다. 왜 당시 `REPEATABLE_READ`를 결정했는지는 확인되지 않는다. [ReceivablesQueryService.ledger](../backend-java/src/main/java/com/smallbiz/reconciliation/receivables/ReceivablesQueryService.java) · [snapshot 통합 테스트](../backend-java/src/test/java/com/smallbiz/reconciliation/receivables/ReceivablesQueryIntegrationTest.java) · [AllocationService](../backend-java/src/main/java/com/smallbiz/reconciliation/receivables/allocation/AllocationService.java)

### A6. Legacy JPA와 새 `JdbcTemplate`을 함께 쓰는 이유

새 tenant AR 서비스에서 `JdbcTemplate`이 사용되는 사실은 확인된다. 기존 대사 기능 보존은 AGENTS.md의 사용자 지시다. 다만 새 모듈에 JdbcTemplate을 선택한 비교 과정과 당시 선정 이유는 확인되지 않는다. SQL·행 잠금 흐름을 직접 표현할 수 있다는 현재 구조의 장점과, 당시 선택 이유는 구분한다. 추측하지 말고 결정 기록 또는 구현 담당자 확인이 필요하다. [ReceivablesService](../backend-java/src/main/java/com/smallbiz/reconciliation/receivables/ReceivablesService.java) · [AllocationService](../backend-java/src/main/java/com/smallbiz/reconciliation/receivables/allocation/AllocationService.java)

### A7. 동시성 테스트가 검증하는 것

동시성 통합 테스트의 `parallel` helper는 고정 스레드 풀 두 개의 `Callable`을 제출하고 `CyclicBarrier(2)`에서 출발을 동기화한 뒤 최대 15초 동안 결과를 받는다. 경쟁 배분 테스트는 한 입금 또는 청구의 합계 상한, 반대 부모 순서 테스트는 두 요청이 같은 정렬 잠금 규칙으로 완료되는지 확인한다. 이 방식은 경쟁을 겹치게 하지만 DB 내부의 특정 잠금 대기 순서를 강제로 지정하지 않는다. 실제 운영 부하·데드락률 측정과도 구분한다. [AllocationIntegrationTest.parallel 및 동시성 사례](../backend-java/src/test/java/com/smallbiz/reconciliation/receivables/allocation/AllocationIntegrationTest.java) · [배분 검토 기록](receivables-allocation-review.md)

## B. 협업·판단 질문

### B8. 구현과 독립 검토의 분리

별도 검토자는 같은 구현자의 경로·가정에 고정되지 않은 관점으로 업무 불변식과 실패 경로를 확인해 누락·잘못된 가정을 찾는 절차다. 배분 기록은 검토자가 테스트 코드·구현과 총괄 보관 테스트 XML을 읽었다고 하며, 독립 검토자가 테스트를 중복 실행하지 않았다고 명시한다. 본인이 어느 역할을 맡았는지는 확인 필요다. [협업 절차](../AGENTS.md) · [배분 검토 기록](receivables-allocation-review.md)

### B9. 업무 DB·아키텍처 변경 승인

핵심 업무 규칙·주요 DB 관계·중요 아키텍처 변경은 사용자 승인을 받는다. 업무 데이터를 되돌리기 어려운 방식으로 바꾸기 전에는 영향받는 원본·이력·테넌트 경계, 재실행/rollback 가능성, 기존 API·기능 계약을 검토하고, 대안·트레이드오프·검증 계획을 제시해 결정권자인 사용자가 승인하도록 한다. 이는 현재 협업 규칙을 적용한 설명이다. 과거의 구체적인 배분 테이블·키 선택에 대한 개별 승인은 확인되지 않았다고 ADR이 기록한다. [협업 규칙](../AGENTS.md) · [ADR 0002](adr/0002-receivables-decisions.md)

### B10. AI/검토에서 실제 발견된 결함

등록 코드의 거래처 코드 정규화에서 Unicode `ß`/`ſ`가 대문자 변환 후 ASCII 코드로 바뀌어 통과할 수 있는 문제를 구현 담당 자체 검토에서 발견했고, 원문 ASCII 검사 및 400 회귀 사례로 수정·재확인했다. 이를 독립 검토자 발견이라고 설명하면 안 된다. [등록 검토 기록](receivables-registration-review.md) · [등록 통합 테스트](../backend-java/src/test/java/com/smallbiz/reconciliation/receivables/ReceivablesRegistrationIntegrationTest.java)

### B11. AI 제안 거절 또는 수정

근거가 있는 AI 제안 채택 사례는 ADR 0002의 입금 등록 재시작 복구 대안 A다. ADR은 2026-11-12 사용자가 대안 A 구현을 승인했다고 기록한다. 사용자 채택 사례로 말할 수 있으나, 이를 거절 사례나 대안 수정 사례라고 바꾸어 말할 수는 없다. [ADR 0002의 입금 복구 결정](adr/0002-receivables-decisions.md)

### B12. AI 테스트와 사용자 직접 확인 구분

자동·에이전트 테스트 통과는 사용자 직접 확인 완료를 뜻하지 않는다. 배분 검토 기록은 통합 테스트 결과를 읽어 확인한 범위와 독립 검토자의 실행 여부를 구분한다. 사용자가 실제로 직접 확인했는지는 별도 근거가 없는 한 `확인 필요`다. [협업 절차](../AGENTS.md) · [배분 검토 기록](receivables-allocation-review.md)
