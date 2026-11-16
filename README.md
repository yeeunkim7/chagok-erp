# 차곡 — 매출채권·입금 대사 시스템 (Java/Spring 백엔드 · AI 에이전트 개발)

청구·입금을 다대다로 배분하고, 부분입금과 미수금을 관리하는 ERP 매출채권 보조원장입니다.

## 개발 배경

회계·정산 실무에서 원장 대조와 중복 업로드 문제를 겪은 경험에서 출발했습니다.
기존 날짜별 입출금 대사를 청구별 수금 관리로 확장했습니다. 합계가 같아도 어느 청구가 결제됐는지는 별도 확인이 필요합니다.
중복 업로드·재요청은 입금을 중복 등록할 수 있고, 정정 과정의 덮어쓰기는 원본 근거를 잃게 합니다.
차곡은 후보 조회와 사람의 확정을 분리하고, Java·DB가 금액을 계산하며 원본과 취소 이력을 보존하도록 구현했습니다.

## AI 에이전트 개발 체계

| 역할 | 책임 |
| --- | --- |
| 개발자 | 업무 규칙·범위·승인 기준 결정, 핵심 코드 경로 확인, 업무 시나리오 수동 검증 |
| Codex 총괄 | 기존 코드 분석, 작업·파일 범위 배분, 실행 근거 통합 |
| 구현 에이전트 | 승인 범위의 구현·회귀 테스트·문서 작성 |
| 독립 검토 에이전트 | 구현과 별도로 오류·경계·검증 근거 점검 |

통제: [범위 제한·승인 게이트](AGENTS.md#목표와-판단-주체)(이탈 예방) · [구현/검토 분리](AGENTS.md#기능-개발-절차) · [결정 출처 기록](docs/ai/DECISION_LOG.md) · [테스트 DB 격리 가드](backend-java/src/test/java/com/smallbiz/reconciliation/vendor/TestDatabaseIsolation.java) · [검토당 모델 요청 5회](AGENTS.md#ai-api-사용-규칙)·[동시 하위 에이전트 최대 2개](AGENTS.md#총괄과-하위-에이전트)(비용 통제), 자동 테스트 유료 호출 금지.
검토 기록의 결함 → 영향 → 수정 → 회귀 근거:
- [월 잠금 전 시각·plan 확정](docs/receivables-usage-review.md) → 오래된 월/등급으로 승인 → 잠금 후 재조회·월 변경 롤백 → [UsageIntegrationTest](backend-java/src/test/java/com/smallbiz/reconciliation/receivables/matching/UsageIntegrationTest.java).
- [입력 공백 변경으로 새 요청 키 생성](docs/receivables-mobile-core-review.md) → 응답 유실 뒤 중복 입금 가능 → 정규화 입력 기준 키 유지 → [critical.test.ts](mobile/tests/critical.test.ts).
- [거래처 코드 `ß`/`ſ` 정규화 결함](docs/receivables-registration-review.md)(구현 담당 자체 검토에서 발견) → 대문자 변환 후 ASCII 검사 우회 → 원문 ASCII 검사 후 대문자 변환 → [ReceivablesRegistrationIntegrationTest](backend-java/src/test/java/com/smallbiz/reconciliation/receivables/ReceivablesRegistrationIntegrationTest.java).
[자세한 작업 이력](docs/ai/AI_REVIEW_WORK.md). 개발자는 핵심 코드 경로를 읽고 본인 PC에서 Hermes로 테스트 실행을 확인했으며, 청구 등록→입금 연결 흐름을 직접 수동 검증했습니다([사용자 확인 기록](docs/ai/DECISION_LOG.md#사용자-확인-사항)).

## 핵심 설계

| 문제 | 설계 | 코드 / 회귀 테스트 |
| --- | --- | --- |
| 동시 초과 배분 | 입금→청구 ID 정렬 잠금, 잠금 후 잔액 재검사, 배분·감사 함께 롤백 | [AllocationService](backend-java/src/main/java/com/smallbiz/reconciliation/receivables/allocation/AllocationService.java) / [AllocationIntegrationTest](backend-java/src/test/java/com/smallbiz/reconciliation/receivables/allocation/AllocationIntegrationTest.java) |
| 응답 유실·중복 요청 | tenant·작업별 키와 내용 비교, 같은 요청 결과 재사용, 입금 재시작 복구 | [ReceivablesService](backend-java/src/main/java/com/smallbiz/reconciliation/receivables/ReceivablesService.java)·[복구](mobile/src/pending-payment.ts) / [등록 테스트](backend-java/src/test/java/com/smallbiz/reconciliation/receivables/ReceivablesRegistrationIntegrationTest.java)·[복구 테스트](mobile/tests/pending-payment.test.ts) |
| 정정 시 원본 유실 | 반제·청구 취소 사실 추가, 대체 청구 연결, 원문 보존 | [배분 취소](backend-java/src/main/java/com/smallbiz/reconciliation/receivables/allocation/AllocationService.java)·[InvoiceCancellationService](backend-java/src/main/java/com/smallbiz/reconciliation/receivables/InvoiceCancellationService.java) / [청구 취소 테스트](backend-java/src/test/java/com/smallbiz/reconciliation/receivables/InvoiceCancellationIntegrationTest.java) |
| 사업장 간 데이터 혼입 | 서버 tenant 권한 검사와 복합 FK | [TenantAccess](backend-java/src/main/java/com/smallbiz/reconciliation/account/TenantAccess.java)·[V9](backend-java/src/main/resources/db/migration/V9__tenant_receivables_registration.sql) / [등록 테스트](backend-java/src/test/java/com/smallbiz/reconciliation/receivables/ReceivablesRegistrationIntegrationTest.java) |

## 기술과 구조

Java 21 · Spring Boot 4.1.1 · PostgreSQL 17 · Flyway V1~V14 · JPA/JdbcTemplate · Spring Security · Gradle 9.7.1 · Docker · GitHub Actions. [빌드 설정](backend-java/build.gradle)
Expo 58 / React Native 0.88.1 / TypeScript 6.0.3: API 검증용 클라이언트. [설정](mobile/package.json)

[ERD](docs/receivables-erd.md) · [아키텍처·호출 흐름](docs/architecture.md) · [설계 결정](docs/adr/0003-ar-erp-workflow.md)

## 실행·테스트

[백엔드 환경 변수](backend-java/README.md)를 먼저 설정합니다. 개발 DB는 `reconciliation_dev`, 테스트는 `reconciliation_test`만 사용합니다.

```sh
(cd backend-java && bash gradlew bootRun)       # API 서버
(cd backend-java && bash gradlew clean build)   # 테스트 포함
(cd mobile && npm ci && npm test && npm run typecheck)
```

[Docker 실행](docs/DOCKER_LOCAL.md): 최초 비밀 파일 준비 후 `docker compose up -d --build --wait web`. 웹 주소는 `http://localhost:18080`입니다.

## 검증 결과 · 2026-11-16

Java **220개**, 클라이언트 **26개** 통과(각 실패·오류·스킵 0), 타입 검사 통과. 테스트 DB Flyway **14개** 적용, Docker 백엔드·웹 기동 확인. 세부 실행 결과와 명령은 [검증 기록](docs/REPOSITORY_VALIDATION.md)에 있습니다.
GitHub Actions는 **워크플로 구성 완료, 원격 실행 미검증**입니다.

## 현재 범위와 후속 계획

현재 원장 기준 청구·입금·배분·취소·미결/연체 조회를 제공합니다. `asOf`는 연체 판정일이며 과거 잔액 재현이 아닙니다. 운영 배포·성능 수치·운영 백업/복구·iOS/Android 실기기는 미검증입니다. 제품의 AI 실행은 차단 상태입니다.

<details><summary>부록</summary>

[Hermes 조회 도구 실습](examples/hermes/chagok-local-api) · [Python 레거시](legacy/python/README.md) · [기존 계획](docs/RECEIVABLES_FREE_MVP_PLAN.md)

</details>
