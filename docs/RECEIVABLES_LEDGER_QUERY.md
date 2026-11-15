# 거래처별 현재 채권 명세 조회

`GET /api/v1/tenants/{tenantId}/receivables/ledger`

인증된 사업장의 현재 청구·활성 입금 배분을 읽는 API다. 회계 총계정원장이나 과거 월말 잔액 조회가 아니다. Java/SQL의 기존 배분 규칙을 재사용하고 읽기 전용 REPEATABLE_READ 트랜잭션에서 합계와 페이지를 같은 snapshot으로 조회한다. 모델 호출·이용량 차감·자동 반제는 없다.

## 입력

| 항목 | 값 |
| --- | --- |
| asOf | 필수 YYYY-MM-DD. 현재 미수금의 연체 분류 기준일 |
| counterpartyId | 선택. 해당 사업장의 거래처 UUID, 없거나 다른 사업장이면404 |
| status | 기본 OPEN. OPEN(미수>0), UNPAID(배분0), PARTIAL, PAID, ALL(취소되지 않은 청구 전체) |
| dueFrom / dueTo | 선택. 지급기한 양끝 포함 필터. 둘 다 있으면 from≤to |
| query | 선택. 문서번호·거래처 코드·이름에 대한 대소문자 무시 부분 문자열 검색. 앞뒤 공백 제거 후100 codepoint, 제어문자 거절 |
| page / size | 기본0/20. page≥0, size1~100 |

query의 `%`, `_`는 wildcard가 아닌 문자 그대로 검색한다. 취소 청구는 ALL에서도 제외하며 원본·취소·대체 관계는 기존 invoice 상세와 cancellation/replacement API로 확인한다. 날짜를 과거로 주어도 지금까지의 청구·배분을 과거 상태로 되돌리지 않는다.

예:

```text
/api/v1/tenants/{tenantId}/receivables/ledger?asOf=2030-04-01&counterpartyId={id}&status=OPEN&page=0&size=20
```

## 응답

- tenantId, asOf, allocationState=CURRENT, 정규화된 filters.
- items: invoiceId, counterpartyId/code/name, invoiceNumber, amount, allocatedAmount, outstandingAmount, issueDate, dueDate, status, daysOverdue, agingBucket.
- page, size, hasNext: 지급기한·UUID 오름차순으로 안정 정렬한 페이지.
- totals: 필터 전체 invoiceCount와 금액 합계. 페이지에 보이는 항목만 더한 값이 아니다.

금액은 원화 정수 문자열이다. totals에는 invoiceAmount·allocatedAmount·outstandingAmount·overdueAmount·notDueAmount와 연체 구간별 금액이 있다. 같은 필터의 page/size를 바꿔도 totals는 같다. 모든 필터를 제외하고 status=ALL로 조회한 금액 합계는 기존 receivables/summary와 일치한다. OPEN 등 상태 필터를 사용한 합계를 전체 장부 합계와 혼동하지 않는다.

조회 구간의 기본값은 다음과 같다. 사용자에게 제시한 권장 조회 분류이며 금액·마감·감액 정책을 변경하는 선택이 아니다.

| agingBucket | 의미 |
| --- | --- |
| SETTLED | 미수금0, 입금 배분 완료 |
| NOT_DUE | 미수금이 있고 기한 당일 또는 아직 기한 전 |
| OVERDUE_1_30 | 기한 다음 날부터30일 |
| OVERDUE_31_60 |31~60일 |
| OVERDUE_61_90 |61~90일 |
| OVERDUE_OVER_90 |91일 이상 |

구간 금액은 현재 미수금만 집계한다. 현재 미수금이0인 문서는 SETTLED·daysOverdue=0이며 과거에 연체한 적이 있는지를 재현하지 않는다. NOT_DUE 합계+모든 연체 구간 합계=outstandingAmount, 모든 연체 구간 합계=overdueAmount다. 입력 날짜 자체에는 시간대가 없고 클라이언트가 업무 기준일을 명시한다. 자동 ‘오늘’ 계산이나 회계 마감을 암묵적으로 적용하지 않는다.

자료가 없을 때는 items=[], invoiceCount=0과 금액0이다. 청구가 있으면서 전액 반제된 경우는 status=ALL/PAID에서 invoiceCount>0·outstandingAmount=0으로 구분한다. OPEN 조회에서0건이라는 사실만으로 청구나 업로드가 전혀 없다고 단정하지 않는다. 범위를 벗어난 페이지도 items=[]이지만 totals는 필터 전체를 유지한다.

## 오류·정합성

- 401: 세션 없음/만료. 403: 해당 사업장 접근 불가. 404: 사업장 내 거래처가 아님.
- 400: 잘못된 날짜·범위·상태·검색어·페이지 크기.
- 503: 저장소 오류 또는 배분 합계 손상. 음수 미수금 등을 OPEN 필터가 조용히 숨기지 않는다. 취소 청구에 활성 배분이 있는 손상도 거절한다.

일반 조회에서는 등록 원본이나 배분 이력을 수정하지 않는다. 조회 Service에서 tenant·세션을 다시 검사한다. 결과 크기는 size로 제한하지만 대용량 SQL 성능이나 SLA를 보장하는 구현은 아니다. 실제 부하와 실행 계획은 운영 확장 전에 측정해야 한다.

## 검증

ReceivablesQueryIntegrationTest의5개 시나리오에서 구간 경계0/1/30/31/60/61/90/91일, 부분/완료 배분, 취소 제외, 전체 summary 대조, 페이지 독립 합계, 거래처·상태·기한·문자 검색, 자료 없음, 세션/tenant 검증, 손상 데이터 거절을 확인한다. 합계 SQL과 페이지 SQL 사이에 별도 스레드의 실제 배분 확정을 커밋시켜 같은 응답이 이전 snapshot을 유지하고 다음 조회는 새 잔액을 보는 것도 검증한다. 현재 단계는 백엔드 API이며 새 명세 화면·엑셀 출력은 추가하지 않는다.
