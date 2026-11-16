# 저장소 정리·검증 기록 — 2026-11-16

기준 HEAD: `a93ee7a`. 작업 브랜치: `docs/portfolio-repository`. Codex가 클라우드 Linux에서 실행한 결과이며 사용자 PC·실기기·운영 환경의 검증 완료를 뜻하지 않는다. Java 업무 코드·SQL·Flyway V1~V14·테스트 내용·AGENTS.md는 변경하지 않는다.

## 테스트와 실행

| 대상 | 실제 명령 / 결과 |
| --- | --- |
| Java 전체 빌드·테스트 | `backend-java`에서 `bash ./gradlew --offline --no-daemon --console=plain --project-cache-dir <외부 캐시> -Dorg.gradle.java.installations.paths=<JDK21> clean build`. 220개, 실패 0 / 오류 0 / 스킵 0, 25 suite. 이동 전·후 clean build 모두 같은 결과. `build/test-results/test/TEST-*.xml` 집계 |
| 클라이언트 | `mobile`에서 `npm test`: critical 14 + pending-payment 12 = 26개, 실패·오류·취소·스킵 0. `npm run typecheck`: exit 0 |
| Flyway | `python3 scripts/ci/verify-flyway.py`: 전용 PostgreSQL 17 `reconciliation_test`의 소스 14개와 성공 이력 일치 |
| Docker | 현재 백엔드·웹 이미지 빌드 및 test DB·backend·web 기동 성공. 웹 `/` 200, backend/proxy `/health` 200, 비로그인 `/api/v1/auth/me` 401. 새 Docker 테스트 DB V1~V14 성공 |
| CI 구성 | `actionlint .github/workflows/ci.yml`: exit 0. `gh run list --repo yeeunkim7/smallbiz-accounting-backend --limit 3 --json status,conclusion,headSha,url`: GitHub API Forbidden. **워크플로 구성 완료, 원격 실행 미검증** |
| Python 이동 | 이동한 파일 AST 구문 검사, 기존 검증 venv의 `python -m alembic upgrade head` 2회. 새 임시 SQLite: 업무 테이블 3개 / 0행 / integrity_check=ok. 기존 루트 accounting.db 기본 경로 보존 assertion 통과 |

Java 명령은 저장소 밖 실행 도우미로 JDK 21·캐시 경로·테스트 접속 변수를 주입했다. DB는 기존 개발 DB와 분리된 `chagok-ar-erp-check` 컨테이너의 `reconciliation_test`만 사용했다. 비밀번호는 출력하지 않았고 `AI_API_KEY=''`, `APP_AI_ENABLED=false`로 실행했다. 테스트 XML·로그는 저장소 밖 `/workspace/.local/run/ar-erp-validation/portfolio-before*`, `portfolio-after*`에 보관한다. 테스트 수는 이 실행의 전체 XML 결과이며 과거 단계별 수치를 합산하지 않았다.

### Docker 실행 범위와 초기 실패

검증은 개발 DB 대신 새 프로젝트 `chagok-portfolio-check`의 `db-test`만 사용하도록 저장소 밖 Compose override를 적용했다. backend/web을 test 네트워크에 연결하고 테스트 계정·비밀 파일을 지정했다. 포트는 backend 18096 / web 18097, 웹 빌드 origin도 `http://localhost:18097`로 맞췄다. 기존 볼륨·WSL DB를 초기화하지 않았다. 일반 개발용 기본 주소는 계속 `http://localhost:18080`이다.

```sh
# 아래 OVERRIDE는 위 조건의 환경별 검증 파일; 저장소의 필수 설정이 아님
# TLS 검사 프록시가 있는 이 환경에서만 proxy_ca BuildKit secret을 추가했다.
docker compose --profile test -p chagok-portfolio-check -f compose.yaml -f "$OVERRIDE" build backend web
docker compose --profile test -p chagok-portfolio-check -f compose.yaml -f "$OVERRIDE" up -d --no-build --wait --wait-timeout 150 web
```

실제 CLI에는 상속된 원격 Docker 설정을 제외하고 `--host=unix:///var/run/docker.sock`을 사용했다. 최초 build에서 test 프로필 누락으로 db-test 의존성 오류가 났으며 `--profile test`로 수정했다. 최초 DB 기동은 복원된 `docker/init-app-db.sh`의 로컬 모드가 600이어서 postgres 사용자가 읽지 못했다. 공개 스크립트만 `chmod 644`로 복원했다(비밀 파일은 600 유지, Git 실행 비트·파일 내용 변경 없음). 새 검증 DB에 앱 역할과 DB가 모두 0건임을 확인한 뒤 [기존 복구 절차](DOCKER_LOCAL.md#초기화-실패와-비밀번호-변경)로 초기화했다. 데이터를 삭제하거나 볼륨을 재생성해 해결하지 않았다.

이후 세 서비스 health와 HTTP를 확인했다. 이번에는 브라우저 사용자 흐름·인증 후 전체 업무를 재실행하지 않았다. 로그·status는 `/workspace/.local/run/portfolio-cleanup/`에 있다. 기본 개발 프로필 전체를 재기동한 검증이나 Windows 사용자 PC 검증으로 해석하지 않는다.

## 구조 이동과 보존

이동 전 Git 추적 목록과 Dockerfile·compose·CI·scripts의 경로 참조를 확인했다. Java/mobile/CI는 Python `app/`, `migrations/`, `alembic.ini`를 참조하지 않는다.

| 이전 | 이후 | 영향과 보존 |
| --- | --- | --- |
| `app/` (추적 Python 25개) | `legacy/python/app/` | `app.core.config`의 저장소 루트 기준만 조정. 업무 로직은 그대로, 기본 accounting.db 위치 보존 |
| `alembic.ini`, `migrations/` (5개) | `legacy/python/` | 설정·마이그레이션 코드 내용 그대로. 실행 cwd를 legacy/python으로 안내 |
| `docs/AI_REVIEW_WORK.md`, `docs/ai-review-*.md` (7개) | `docs/ai/` | 링크 갱신. AGENTS의 기존 경로를 위해 이전 AI_REVIEW_WORK에는 이동 안내만 유지 |
| `examples/hermes/` | 그대로 | 루트 README 부록 링크로만 소개 |
| `index.html` | 그대로 | 정적 전표 예시 HTML. API 호출·외부 script 연결 없고 Docker/CI에서 참조하지 않음. `legacy/python/` 이동 후보로만 보고 |
| 루트 `accounting.db`·개인 자료 | 그대로, Git 제외 | DB 파일 이동·열람·수정 없음. Python 작업 경로에 생성될 수 있는 DB도 .gitignore에 추가 |

기능별 `docs/receivables-*-review.md`는 위치와 과거 검증 주체·날짜를 보존했다. 과거 186/197개 등의 기록은 당시 이력으로 남기고 현재 README와 실행 안내는 이번 220/26개 결과를 연결한다. 전체 Python API의 의존성 설치·기동은 미검증이다.

## 확인이 필요한 내용

- 개발자가 직접 읽고 설명·검증한 코드 범위와 날짜: 자동 테스트 통과로 대체하지 않는다.
- 개인 회계·정산 실무 경험: 이번 README는 제품의 문제 상황을 설명하며 개인 경험으로 주장하지 않는다.
- JdbcTemplate 선정 과정, 과거 DB·잠금 설계의 사용자 개별 결정: [결정 출처 초안](ai/DECISION_LOG.md)을 사용자 검토 후 보완한다.
- 명시적으로 거절한 AI 제안 사례: 기록된 입금 복구 채택과 별개이며 추가 근거가 필요하다.
- 원격 CI, 운영 배포·성능·운영 백업/복구, iOS/Android 실기기: 후속 검증이다.

## 파일 이동 목록

- `alembic.ini` → `legacy/python/alembic.ini`
- `app/__init__.py` → `legacy/python/app/__init__.py`
- `app/api/__init__.py` → `legacy/python/app/api/__init__.py`
- `app/api/deps.py` → `legacy/python/app/api/deps.py`
- `app/api/routers/__init__.py` → `legacy/python/app/api/routers/__init__.py`
- `app/api/routers/account_subjects.py` → `legacy/python/app/api/routers/account_subjects.py`
- `app/api/routers/journals.py` → `legacy/python/app/api/routers/journals.py`
- `app/api/routers/vendors.py` → `legacy/python/app/api/routers/vendors.py`
- `app/core/__init__.py` → `legacy/python/app/core/__init__.py`
- `app/core/config.py` → `legacy/python/app/core/config.py`
- `app/core/db.py` → `legacy/python/app/core/db.py`
- `app/main.py` → `legacy/python/app/main.py`
- `app/models/__init__.py` → `legacy/python/app/models/__init__.py`
- `app/models/account_subject.py` → `legacy/python/app/models/account_subject.py`
- `app/models/base.py` → `legacy/python/app/models/base.py`
- `app/models/journal.py` → `legacy/python/app/models/journal.py`
- `app/models/vendor.py` → `legacy/python/app/models/vendor.py`
- `app/repositories/__init__.py` → `legacy/python/app/repositories/__init__.py`
- `app/repositories/journal_repository.py` → `legacy/python/app/repositories/journal_repository.py`
- `app/schemas/__init__.py` → `legacy/python/app/schemas/__init__.py`
- `app/schemas/account_subject.py` → `legacy/python/app/schemas/account_subject.py`
- `app/schemas/journal.py` → `legacy/python/app/schemas/journal.py`
- `app/schemas/vendor.py` → `legacy/python/app/schemas/vendor.py`
- `app/services/__init__.py` → `legacy/python/app/services/__init__.py`
- `app/services/journal_service.py` → `legacy/python/app/services/journal_service.py`
- `app/services/validators.py` → `legacy/python/app/services/validators.py`
- `migrations/README` → `legacy/python/migrations/README`
- `migrations/env.py` → `legacy/python/migrations/env.py`
- `migrations/script.py.mako` → `legacy/python/migrations/script.py.mako`
- `migrations/versions/cc75580579fd_init_schema.py` → `legacy/python/migrations/versions/cc75580579fd_init_schema.py`
- `docs/AI_REVIEW_WORK.md` → `docs/ai/AI_REVIEW_WORK.md`
- `docs/ai-review-ai.md` → `docs/ai/ai-review-ai.md`
- `docs/ai-review-code-analysis.md` → `docs/ai/ai-review-code-analysis.md`
- `docs/ai-review-design.md` → `docs/ai/ai-review-design.md`
- `docs/ai-review-independent-review.md` → `docs/ai/ai-review-independent-review.md`
- `docs/ai-review-research.md` → `docs/ai/ai-review-research.md`
- `docs/ai-review-running.md` → `docs/ai/ai-review-running.md`

## 독립 검토

별도 검토 에이전트가 링크·원본 해시·Python 경로·README/면접 설명과 Java/DDL을 대조했고 차단 결함을 발견하지 못했다. 검토자는 테스트를 중복 실행하지 않았다. 총괄의 이동 후 재실행도 Java 220개, 클라이언트 26개, 실패·오류·스킵 0과 typecheck exit 0으로 끝났다. 문서 파일 링크·제목 앵커 검사와 `git diff --check`를 통과했다. 사용자 직접 코드 검토 완료나 운영 준비 완료를 뜻하지 않는다.
