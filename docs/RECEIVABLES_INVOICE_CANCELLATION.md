# 청구 오입력 취소와 대체 등록

청구 금액·거래처·날짜 등을 잘못 입력했을 때 원문을 보존하고 취소 사실을 추가한다. 사용자 승인 범위인 ERP 업무 흐름 보완의 두 번째 단계다. API와 DB 검증 결과는 `docs/ai/AI_REVIEW_WORK.md`의 실제 실행 기록을 따른다.

## 처리 순서

1. 청구 원문과 연결된 배분을 확인한다.
2. 활성 배분이 있으면 기존 배분 취소 API로 해당 배분 묶음을 먼저 취소한다. 묶음에 여러 줄이 있으면 기존 계약대로 모든 줄이 함께 취소된다.
3. 청구 취소 API에 사유와 새 `Idempotency-Key`를 전달한다.
4. 올바른 청구를 새 번호로 등록한다. `replacesInvoiceId`를 지정하면 취소한 원문과 새 청구를 연결한다.

취소해도 원문의 UUID·번호·금액·날짜·거래처·등록자·등록 시각과 기존 배분 이력은 남는다. 기존 청구 원문 조회와 등록 응답의 필드도 유지한다. 취소 사실과 대체 관계는 각각 별도 API로 조회한다.

## API

모든 경로 앞에는 `/api/v1/tenants/{tenantId}`가 붙는다. 기존 bearer 인증과 tenant 소속 검사를 적용한다. 금액은 기존 계약대로 KRW 정수 문자열이다.

| 메서드·경로 | 요청 | 결과 |
| --- | --- | --- |
| `POST /invoices/{id}/cancel` | `Idempotency-Key`, JSON `reason` | 200, 취소 사실 |
| `GET /invoices/{id}/cancellation` | 없음 | 200, 취소 사실. 취소되지 않았거나 해당 tenant에 없으면 404 |
| `POST /invoices` | 기존 등록 필드 + 선택 `replacesInvoiceId` | 201, 기존 형식의 새 청구 원문 |
| `GET /invoices/{id}/replacement` | 취소 원문의 ID | 200, 대체 관계. 연결이 없거나 해당 tenant에 없으면 404 |

청구 취소 본문:

```json
{"reason":"청구 금액 오입력"}
```

`reason`은 앞뒤 공백을 제거한 뒤 1~1000자다. 취소 API는 별도 효력일이나 금액 입력을 받지 않는다. 취소 사실 응답 필드는 `id`, `tenantId`, `invoiceId`, `reason`, `createdBy`, `createdAt`이다. `createdAt`은 서버 시계의 현재 UTC 시각이다.

대체 등록 본문 예:

```json
{
  "counterpartyId":"11111111-1111-4111-8111-111111111111",
  "invoiceNumber":"INV-2026-002",
  "amount":"120000",
  "issueDate":"2026-11-01",
  "dueDate":"2026-11-30",
  "replacesInvoiceId":"22222222-2222-4222-8222-222222222222"
}
```

서버는 새 UUID를 만든다. 취소한 청구의 번호도 계속 보존되므로 새 청구 번호를 사용해야 한다. 대체 관계 응답 필드는 `tenantId`, `cancelledInvoiceId`, `replacementInvoiceId`, `createdBy`, `createdAt`이다.

한 취소 청구에는 대체 청구 하나만 연결할 수 있다. 대체 청구를 다시 정정하려면 그 청구를 취소하고, 그 ID를 다음 등록의 `replacesInvoiceId`로 지정한다. 이때 이전 원문과 대체 관계도 보존된다.

## 재시도와 오류

청구 취소는 같은 tenant·키·청구 ID·정규화한 사유에 대해 최초 취소 사실을 반환한다. 이후 대체 등록이 일어나도 취소 재시도의 응답은 동일하다. 새 키로 이미 취소한 청구를 취소하면 409다.

대체 등록도 기존 청구 등록의 멱등 처리를 재사용한다. 같은 키의 재시도에서는 새 청구를 만들지 않는다. `replacesInvoiceId`의 UUID 표기는 정규화하며 미지정과 `null`은 동일하다. 이 필드가 없는 요청의 해시는 V14 이전의 다섯 필드 형식을 그대로 사용하므로 기존 등록 키를 재시도할 수 있다.

| 코드 | 상태 | 처리 |
| --- | --- | --- |
| `INVOICE_HAS_ACTIVE_ALLOCATIONS` | 409 | 활성 배분을 먼저 취소한 뒤 청구 취소 재시도 |
| `INVOICE_ALREADY_CANCELLED` | 409 | 기존 취소 사실 조회 |
| `INVOICE_NOT_CANCELLED` | 409 | 대체 대상으로 지정한 원문의 취소 여부 확인 |
| `INVOICE_ALREADY_REPLACED` | 409 | 기존 대체 관계 조회 |
| `INVOICE_CANCELLED` | 409 | 배분 확정 대상에서 취소 청구 제외 |
| `IDEMPOTENCY_CONFLICT` | 409 | 같은 키의 다른 요청인지 확인 |
| `RECEIVABLE_DUPLICATE` | 409 | 기존 청구 번호와 겹치지 않는 새 번호 사용 |
| `INVALID_INPUT` | 400 | 필수 사유·키·등록 필드와 허용 필드 확인 |
| `RECEIVABLE_NOT_FOUND` | 404 | 해당 tenant의 원문·취소 사실·대체 관계 확인 |

익명 요청은 401, 다른 tenant의 경로 접근은 403이다. 자신의 tenant 경로에 다른 tenant의 원문 ID를 넣으면 알 수 없는 ID와 같은 404를 반환한다. 저장소 오류는 SQL이나 입력값을 노출하지 않는 기존 503 응답을 사용한다. 실패한 요청의 키 예약·취소 사실·새 청구·대체 연결은 트랜잭션과 함께 롤백된다.

## 잔액·집계·후보 생성

취소 청구의 개별 잔액은 원래 `amount`를 보존하고 `status=CANCELLED`, `outstandingAmount=0`, `overdueAmount=0`을 반환한다. 활성 배분이 없어야 취소할 수 있으므로 정상 상태의 `allocatedAmount`도 0이다. 취소 청구와 활성 배분이 동시에 존재하는 비정합은 잔액·전체 집계에서 503으로 거절한다.

전체 집계의 청구 수·청구 금액·미수·연체에는 활성 청구만 포함한다. 입금 원문과 미배분 금액 계산은 기존대로 유지한다. 새 매칭 실행은 취소 청구를 후보 대상에서 제외한다. 이미 저장한 매칭 결과와 같은 키의 재시도 결과는 보존되므로 과거 결과에 취소 청구가 보일 수 있다. 배분 확정에서는 현재 취소 상태를 다시 확인해 거절한다.

취소 효력은 현재 장부에 적용한다. 기존 `asOf`는 현재 잔액의 연체 여부를 판단하는 기준일이다. 취소보다 과거의 `asOf`를 조회해도 취소 청구의 미수가 복원되지 않는다. 과거 장부 재현이나 소급 취소 날짜는 이 API에 포함하지 않는다.

## 저장과 동시성

V14는 원문 변경 없이 다음 테이블을 추가한다.

| 테이블 | 보존 내용·제약 |
| --- | --- |
| `receivable_invoice_cancellation` | 원문별 취소 사실 1개, 필수 사유·실행자·시각, tenant 복합 FK |
| `receivable_invoice_cancellation_command` | tenant별 요청 키·정규화 해시·취소 결과. 결과 FK는 같은 트랜잭션에서 완성 |
| `receivable_invoice_replacement` | 취소 원문별 대체 청구 1개, 대체 청구의 중복 연결 금지, 취소 사실·새 원문에 tenant 복합 FK |

취소는 원문 `FOR UPDATE` 잠금 후 활성 배분을 검사한다. 배분 확정은 기존 payment→invoice 잠금 순서를 유지하고, 모든 부모 잠금을 얻은 다음 별도 SQL 문장에서 취소 사실을 검사한다. 잠금 대기 중 다른 트랜잭션이 취소를 커밋해도 확인할 수 있다. 취소와 확정 중 먼저 완료한 쪽에 따라 다른 쪽이 409가 되며 활성 배분과 취소 사실이 함께 확정되지 않는다.

대체 등록은 취소 원문을 잠그고 취소·기존 연결 여부를 확인한 뒤 새 원문·등록 키·대체 관계를 함께 저장한다. 별도 취소 서비스와 기존 등록·배분·매칭 서비스 변경을 `InvoiceCancellationIntegrationTest`에서 검증한다. 실제 DB 적용과 테스트 실행은 테스트 전용 DB 분리 가드를 유지한 환경에서 수행한다.
