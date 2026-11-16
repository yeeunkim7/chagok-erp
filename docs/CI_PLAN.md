# GitHub Actions CI 구성과 검증

2026-11-15 설계안을 사용자 승인 후 구현했다. 클라우드의 새 임시 PostgreSQL에서 빌드·테스트를 검증했으며 실제 GitHub Actions 실행은 아직 하지 않았다. 사용자 PC의 Docker 실행 확인과 독립적으로 진행한 작업이다. 운영 배포는 포함하지 않는다.

## 확인한 현재 상태

- 작업 브랜치는 chore/docker-local, HEAD는 d457bff다. 입금 복구 통합 및 Docker 2-A/2-B 변경은 미커밋 상태다. 이번 분석에서 main·기존 변경·Git 이력을 변경하지 않았다.
- 사전 분석 당시 .github/workflows는 없었다. 현재 ci.yml을 추가하고 Java 21·Gradle wrapper 9.7.1과 기존 test 태스크를 재사용한다.
- application-test.yml은 TEST_DB_URL·TEST_DB_USERNAME·TEST_DB_PASSWORD를 요구하며 Flyway 활성화와 JPA 스키마 검증을 사용한다.
- TestDatabaseIsolation은 reconciliation_test라는 정확한 DB 이름, 개발 대상과의 차이, 실제 인증 및 Spring의 최종 datasource 설정을 검사한다. 이 가드는 유지한다.
- build.gradle의 test 태스크는 AI_API_KEY를 빈 값으로 강제한다. CI에도 실제 AI 키나 운영 자격증명을 전달하지 않는다. 일부 테스트의 가짜 키·모의 모델 검증과 실제 유료 호출은 구분한다.
- 11월13일 Docker 검증에서 Java197개, 브라우저9개와 HTTP7개가 통과했다. 이번 사전 분석에서 다시 실행하지 않았으며 GitHub CI 통과 결과로 간주하지 않는다.

## 권장하는 첫 구현 범위

첫 CI는 GitHub 제공 Ubuntu runner에서 Java를 직접 실행하고 PostgreSQL17 service를 붙인다. Docker Compose 전체를 매 PR마다 빌드하는 방식보다 검증 경로가 짧고 Gradle 캐시를 활용하기 쉽다. Docker 이미지 검증은 이후 별도 job으로 추가한다.

| 항목 | 제안 |
| --- | --- |
| 실행 계기 | pull_request와 수동 workflow_dispatch. pull_request_target은 사용하지 않음 |
| 권한·시간 | contents: read, job timeout20분, 같은 PR의 이전 실행 취소 |
| Java | Temurin21, 저장소 Gradle wrapper 사용 |
| PostgreSQL | 기존 Compose와 같은 PostgreSQL17 공식 digest, healthcheck 후 초기화 |
| 테스트 계정 | chagok_test, 관리자·DB/역할 생성·복제·RLS 우회 권한 없음 |
| DB | runner 전용 reconciliation_test만 생성. 사용자 PC·클라우드 기존 DB에는 연결하지 않음 |
| 비밀값 | 매 실행 테스트용 앱 비밀번호 생성·로그 마스킹. 실제 AI·운영·Supabase 키와 저장소 쓰기 토큰 불필요 |
| 실행 명령 | backend-java에서 bash ./gradlew --no-daemon --console=plain clean build |
| 마이그레이션 | 기존 Spring 통합 테스트로 빈 DB에 V1~V13 적용, SQL 이력의 성공 여부·누락 확인 |
| 결과물 | 종료 코드와 XML의 실행/실패/오류/스킵 집계, 테스트 HTML·XML을 실패 시에도 업로드 |
| 배포 | 이미지 게시·서버 접속·앱 배포 단계 없음 |

서비스 초기화용 postgres 관리 계정은 runner의 일회성 DB 안에서만 사용한다. 애플리케이션 테스트는 별도 앱 역할로 접속한다. 초기화 스크립트는 DB명·역할명을 고정하고 비밀번호를 SQL 문자열이나 명령 인자에 직접 보간하지 않는다. 서비스 관리 비밀번호도 운영 비밀번호와 무관한 일회성 검증용 값으로 분리한다.

TEST_DB_URL은 runner의 명시적 PostgreSQL 주소와 reconciliation_test로 지정한다. DB_URL은 개발 DB 이름의 비교 입력으로만 사용하며 그 DB를 생성하거나 접속할 필요는 없다. 비밀번호·JDBC URL 전체·환경 변수 목록을 디버그 출력하지 않는다. Actions shell tracing도 사용하지 않는다.

현재 build.gradle에는 Flyway Gradle 플러그인이 없으므로 `gradlew flywayValidate` 명령을 임의로 추가하지 않는다. 기존 Spring Boot/Flyway 경로와 실제 flyway_schema_history 조회로 확인한다. 적용된 버전 수는 현재13이지만 테스트 수197을 영구 고정하지 않고 실행 시 집계한다.

## 구현 파일

1. .github/workflows/ci.yml — PR·수동 실행, PostgreSQL service, Java/Gradle 준비, 빌드·검증·결과 업로드.
2. scripts/ci/init-test-db.sh — runner 전용 DB·비관리자 역할 초기화. 로컬 DB에는 실행하지 않도록 CI 실행 조건을 검사.
3. scripts/ci/verify-flyway.py — 소스 migration 목록과 실제 DB 이력의 버전·파일명·성공 여부 대조.
4. scripts/ci/summarize-tests.py — XML 집계, 실제 빌드 종료 코드와 결과를 Markdown 요약으로 저장. XML 없음·파손·0건·실패/오류를 성공으로 처리하지 않음.
5. 이 문서 및 docs/AI_REVIEW_WORK.md — 최종 실행 방법·결과·미검증 범위 기록.

기존 업무 코드·Flyway SQL·인증·금액 계산·TestDatabaseIsolation은 변경하지 않았다. 공식 저장소의 v6 ref와 action metadata를 확인한 commit SHA로 고정했다. gradle/actions의 annotated tag는 peeled commit을 사용했다. 버전 갱신 시에도 공식 ref·입력 계약을 확인해야 한다.

| 외부 Action | 고정한 공식 commit SHA |
| --- | --- |
| actions/checkout | d23441a48e516b6c34aea4fa41551a30e30af803 |
| actions/setup-java | d1374f8ac74474200d16ef8ecee22c7ae7a51880 |
| gradle/actions/setup-gradle | 8da46b539cd8fe6ab27ca75128919a18d757604c |
| actions/upload-artifact | b7c566a772e6b6bfb58ed0dc250532a479d7789f |

setup-gradle v6의 상용 캐시 제공자를 사용하지 않도록 `cache-provider: basic`을 명시했다. PR에서는 cache 읽기만 허용하고 wrapper 검증은 활성화했다. dependency graph·build scan 게시는 끈다. checkout은 인증정보를 Git 설정에 남기지 않는다.

## 검증 및 완료 기준

1. workflow 구문·셸 구문과 권한·secret 전달 경로를 검토한다.
2. 별도 임시 DB에서 초기화와 비관리자 접속을 검증한다. 기존 reconciliation_dev에는 접근하지 않는다.
3. 같은 환경에서 기존 Java 전체 테스트와 build를 실행한다. 빈 DB Flyway 적용과 XML 집계를 확인한다.
4. 잘못된 테스트 DB 이름을 지정하면 기존 격리 가드가 거절하는지 확인한다.
5. 구현 담당과 다른 에이전트가 workflow·초기화 스크립트·검증 결과를 검토한다.
6. 사용자 검토·커밋·푸시 승인 후 PR 또는 수동 실행으로 실제 GitHub Actions를 확인한다. 업로드된 보고서·실패 표시까지 확인한 뒤에만 CI 동작 검증 완료로 기록한다.

로컬 또는 클라우드의 유사 명령 성공은 실제 GitHub Actions 성공과 구분한다. GitHub 사용 가능 분량·저장소 Actions 정책은 아직 확인하지 않았다. 이 단계는 무료 runner 사용을 제안하며 유료 runner·결제는 별도 승인 사항이다.

## PC 확인과 후속 순서

CI 구성안·구현·클라우드 검증·독립 검토는 PC 실행 전에 진행할 수 있다. 사용자 PC Docker Desktop/WSL 실행·재부팅 검증은 별도로 남긴다. CI는 PC에서 앱을 켜 주는 기능이나 운영 배포가 아니다.

현재 구현과 클라우드 검증까지 진행했으며 커밋·푸시·배포는 별도 지시에 따른다. 웹 TypeScript/단위 테스트 job과 Docker 이미지 빌드 검사는 첫 Java CI가 검증된 뒤 추가 범위를 결정한다.

## 클라우드 검증 결과 — 2026-11-15

새 Docker PostgreSQL17.12를 호스트127.0.0.1:25432에 연결하고 Java21.0.12.1 JDK·Gradle9.7.1을 호스트에서 실행했다. 기존 개발/테스트 DB를 사용하지 않았다. 일회성 앱 비밀번호는 출력하지 않았고 검증 파일은 저장소 밖에 보관했다.

| 검증 | 결과 |
| --- | --- |
| workflow·구문 | 공식 체크섬과 일치한 actionlint1.7.7 검사, Bash 구문·Python 컴파일·diff 검사 통과 |
| 초기화 | 빈 PostgreSQL17에 reconciliation_test/chagok_test 생성 및 앱 계정 인증 성공 |
| 거절 조건 | CI 모드 아님·원격 호스트·기본5432·개발 DB 이름·초기화 재실행 거절. 개발 DB 생성0개 |
| 역할 | superuser/createdb/createrole/replication/bypassrls/inherit 모두 false |
| 실제 Spring 격리 가드 | 별도 출력 경로에서 개발 DB 이름을 지정한 통합 테스트1개를 실행해 JDBC 접속 전 거절 확인. 예상된 종료 코드1을 정상 전체 테스트 결과와 분리 보관 |
| clean build | 종료 코드0, 1분1초. 23 suite·197개, 실패0·오류0·스킵0 |
| Flyway | 빈 테스트 DB에 기존 소스13개 적용, DB 이력과 파일 목록 일치 |
| 보고서 오류 경로 | 별도 임시 XML에서 없음·파손·실패는 거절, 정상 XML 집계 확인. Flyway 실패 이력 거절은 모의 DB 응답으로 확인 |
| 독립 검토 | workflow·helper 검토 및 실제 XML/빌드/Flyway 결과 대조에서 차단 문제 없음 |

최초 빌드는 클라우드 기본 Java가 컴파일러 없는 실행 환경이어서 실패했다. 이미 설치된 Java21 JDK를 명시한 뒤 성공했으며 workflow의 Temurin JDK 설정을 변경할 필요는 없었다. 클라우드 실행은 기존 캐시를 사용하는 `--offline`과 별도 project cache/JDK 경로를 추가했다. 실제 GitHub에서는 workflow의 일반 `clean build` 명령으로 dependency 다운로드·Actions 캐시·wrapper 검증을 확인해야 한다.

증거: 저장소 밖 `/workspace/.local/run/ci-validation-20261115/`의 build.log·test-results·test-summary.json·init-checks.json·flyway.log·summary.log·negative-spring-guard.json. helper 오류 경로 검증은 `/workspace/.local/ci-implementation-checks-20261115.md`에 구분했다. 검증용 PostgreSQL 컨테이너는 종료 후 중지하고 전용 볼륨을 보존한다. 실제 GitHub runner 기동·Action 실행·artifact 업로드·PR 상태 표시는 아직 미검증이다.

다음은 변경 검토와 커밋·푸시 승인 후 PR에서 Java CI가 실행되는지 확인하는 것이다. 실패 시 Actions job 로그와 java-test-reports artifact를 함께 확인한다. workflow_dispatch는 기본 브랜치에 workflow가 반영된 뒤 수동 실행할 수 있다. 로컬용 초기화 명령으로 이 스크립트를 사용하지 않는다.
