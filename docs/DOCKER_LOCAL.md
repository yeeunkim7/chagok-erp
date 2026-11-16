# 로컬 Docker 실행 — Phase 2-A / 2-B

2-A는 Java 백엔드·개발 PostgreSQL·테스트 PostgreSQL을 구성한다. 후속 2-B는 기존 Expo 웹을 정적 빌드해 nginx로 제공한다. 화면 확인은 데스크톱 웹을 기본으로 한다. CI/CD와 외부 배포는 별도 단계다.

## 환경과 데이터 경계

Docker Engine + Compose 2.17 이상(BuildKit named context 사용), Python 3가 필요하다. Windows는 Docker Desktop의 WSL2 연동과 Ubuntu 안의 `~/projects/...` Linux 파일시스템에서 실행한다. Windows Python/PowerShell이나 `/mnt/c` checkout의 파일 권한 동작은 지원 대상으로 검증하지 않았다.

| 서비스 | 역할 | 데이터/접속 |
| --- | --- | --- |
| backend | Java 21 JRE, 기존 Spring Boot 앱 | `http://localhost:18091`, 호스트 loopback만 공개 |
| web | 기존 Expo 웹 정적 파일, nginx | `http://localhost:18080`, 호스트 loopback만 공개 |
| db-dev | PostgreSQL 17 개발 DB | reconciliation_dev / chagok_dev, pg-dev 볼륨 |
| db-test | PostgreSQL 17 테스트 DB | reconciliation_test / chagok_test, pg-test 볼륨 |
| test-runner | Java 21 JDK와 기존 Gradle 테스트 | test 프로필에서만 실행 |

Compose 프로젝트 기본 이름은 chagok-local이다. dev/test 네트워크와 볼륨은 서로 다르다. DB 포트는 호스트에 공개하지 않으며 기존 WSL PostgreSQL·SQLite 파일을 마운트하지 않는다. 테스트 실행기는 dev 네트워크에 연결되지 않는다. 일반 bridge 네트워크이므로 외부 통신까지 차단하는 구성은 아니다.

이미지는 확인한 공식 digest로 고정한다. 새 빈 Docker DB에 기존 Flyway V1~V14를 적용한다. Docker 설정 자체가 업무 마이그레이션을 추가하지 않는다. 실제 운영·개발 금융 자료를 가져오지 않는다. 저장 볼륨은 백업을 대신하지 않는다.

## 처음 실행

저장소 루트에서 실행한다.

```sh
python3 scripts/setup-docker-secrets.py
docker compose up -d --build --wait backend
curl --fail http://localhost:18091/health
```

웹 화면까지 실행하려면 다음 명령을 사용한다.

```sh
docker compose up -d --build --wait web
# Windows 브라우저 주소: http://localhost:18080
```

Node 24는 이미지 빌드에만 사용하며 웹 실행에는 nginx만 남는다. 일반 실행에서 별도 Node·Expo 개발 서버를 켤 필요가 없다. 브라우저의 `/api/`와 `/health` 요청은 nginx가 기존 backend로 전달한다. 금액 계산과 인증은 Java가 처리한다.

기본 웹 주소는 반드시 `http://localhost:18080`을 사용한다. 공개 API origin이 `EXPO_PUBLIC_API_URL` 빌드 인자로 정적 JS에 포함되므로 `127.0.0.1`이나 임의 포트로 바꾸면 동일 origin 구성이 달라진다. 주소를 바꿀 때는 web의 포트와 빌드 인자를 함께 수정하고 이미지를 재빌드한다. 이 값은 공개 주소이며 비밀값을 넣으면 안 된다. 휴대폰 LAN 접속·공개 도메인·HTTPS는 이번 로컬 구성의 범위 밖이다.

비밀번호 4개는 `.local/docker-secrets/` 아래에 각각 생성한다. 디렉터리0700·파일0600이며 Git/이미지 빌드 대상에서 제외한다. 재실행하면 기존 값을 유지하고, 일부 파일만 있거나 비어 있으면 중단한다. 비밀번호는 출력하지 않는다. 파일은 호스트에서 암호화 저장되는 것이 아니므로 개인 PC 접근 권한으로 보호해야 한다.

로컬 file secret의 UID·0600 모드를 지키기 위해 컨테이너 시작 시 root가 파일을 읽고, backend/test-runner는 즉시 UID10001로 권한을 낮춘 뒤 Java/Gradle을 실행한다. PostgreSQL도 공식 entrypoint가 postgres OS 사용자로 전환한다. 앱 DB 역할은 관리자·다른 DB 생성·역할 생성·복제·RLS 우회 권한이 없지만 자신의 DB/schema에서 Flyway를 실행할 수 있다. 운영용 migration 계정과 DML 전용 계정의 분리는 후속 설계다.

DB healthcheck는 앱 계정의 TCP 인증과 SELECT 1을 확인한다. 백엔드 `/health`는 프로세스 응답만 확인하며 지속적인 DB 정상 여부를 보장하지 않는다. 최초 기동 후 로그인·API 조회 및 Flyway 이력을 별도로 확인해야 한다.

## 종료·재시작·로그

```sh
docker compose ps
docker compose logs --tail=100 backend db-dev
docker compose stop backend db-dev
docker compose up -d --wait backend
```

웹까지 중지하려면 `docker compose stop web backend db-dev`, 재시작하려면 `docker compose up -d --wait web`을 사용한다. PC 재부팅 후에도 Docker가 실행 중이면 후자의 명령으로 기존 이미지를 사용해 다시 시작할 수 있다.

`stop`은 컨테이너를 멈추고 데이터를 유지한다. 컨테이너를 제거하는 `docker compose down`도 기본적으로 named volume을 남기지만, `down -v` 또는 volume prune은 데이터를 지우므로 일반 실행 절차에 사용하지 않는다. PC 재부팅 후 Docker를 먼저 실행하고 다시 up한다. 비밀번호 파일과 볼륨을 함께 유지한다.

## 테스트

먼저 이미지를 빌드하면서 wrapper·test runtime 의존성을 준비한다. 이미지 빌드 중 DB 통합 테스트를 실행하지 않는다. 테스트 실행은 저장된 의존성을 사용해 `--offline`으로 진행한다.

```sh
docker compose --profile test up --build --abort-on-container-exit --exit-code-from test-runner test-runner
mkdir -p .local/docker-test-reports
docker compose cp test-runner:/workspace/build/test-results/test .local/docker-test-reports/
docker compose logs --tail=100 test-runner
```

명령 끝의 **test-runner 대상 지정**을 유지한다. `--profile test up`만 쓰면 기본 개발 서비스도 포함된다. 테스트는 dev DB와 분리된 전용 DB에서 검증용 데이터를 정리·생성한다. `TestDatabaseIsolation`을 그대로 사용하며 테스트 JVM의 AI_API_KEY는 비운다. 실제 모델 호출은 하지 않는다. 성공 판단은 프로세스 종료 코드와 XML의 테스트·실패·오류·스킵 수를 함께 확인한다.

## 초기화 실패와 비밀번호 변경

PostgreSQL 공식 초기화 스크립트는 빈 볼륨에서만 실행된다. 초기화 중 실패해 PG_VERSION이 생겼다면 다음 기동에서 자동 재실행하지 않는다. 앱 접속 healthcheck가 실패하면 로그와 아래 메타데이터를 먼저 확인한다.

```sh
docker compose exec db-dev psql -U postgres -d postgres -c "SELECT rolname FROM pg_roles WHERE rolname='chagok_dev';"
docker compose exec db-dev psql -U postgres -d postgres -c "SELECT datname FROM pg_database WHERE datname='reconciliation_dev';"
```

테스트 DB는 서비스·역할·DB 이름을 각각 db-test/chagok_test/reconciliation_test로 바꿔 확인한다. 역할과 DB가 모두 없고 신규 Docker 전용 볼륨임이 확인된 경우에만 초기화 스크립트를 수동 실행할 수 있다. **둘 중 하나라도 있으면 CREATE를 반복하지 말고 중단해 부분 완료 상태를 검토한다.** 기존 데이터를 삭제해서 해결하지 않는다.

```sh
# 위의 '역할·DB 모두 없음' 확인 후, 해당 새 Docker DB에만 실행
# 비밀번호를 명령 인자로 넘기지 않는다.
docker compose exec -T db-dev sh -c 'APP_DB_PASSWORD=$(cat "$APP_DB_PASSWORD_FILE"); export APP_DB_PASSWORD; exec sh /docker-entrypoint-initdb.d/10-app-db.sh'
```

secret 파일만 바꿔도 기존 DB 비밀번호가 바뀌지 않는다. 변경이 필요하면 먼저 해당 서비스를 중지하고 DB 백업·역할 비밀번호 변경·secret 교체·재기동 절차를 별도로 준비한다. 실행 중인 볼륨에 새 secret을 무작정 생성하거나 초기화 스크립트를 재실행하지 않는다. 이번 설정은 자동 비밀번호 회전·백업/복구 자동화를 제공하지 않는다.

## 프록시가 있는 클라우드 빌드

일반 PC에서는 별도 CA 설정이 필요 없다. TLS 검사 프록시가 있는 검증 환경은 저장소 밖 Compose override로 `proxy_ca` build secret을 backend/test-runner 빌드에 전달한다. `docker/gradle-network.sh`는 해당 RUN 안의 임시 Java truststore에만 CA를 넣고 종료 시 제거한다. 세션 CA·프록시 인증값을 이미지에 COPY하거나 TLS 검증을 끄지 않는다. 인증정보가 포함된 프록시 URL은 지원하지 않고 명시적으로 거절한다.

## 실제 검증 결과

현재 2026-11-16 재실행 결과는 [검증 기록](REPOSITORY_VALIDATION.md)을 따른다. 아래 197개·V13 결과는 2026-11-13 당시 이력이다.

2026-11-13 클라우드 Linux의 로컬 Docker Engine 28.4.0 / Compose 2.40.3에서 검증했다. 이미지 내부는 Java 21.0.13, PostgreSQL 17.12다. 사용자 Windows/WSL의 기존 PostgreSQL 17.11과는 별도 환경이다.

| 확인 항목 | 실행 결과 |
| --- | --- |
| backend / test-runner 이미지 빌드 | 두 이미지 모두 성공. 프록시 CA는 저장소 밖 override의 BuildKit secret으로 전달 |
| 전체 Java 테스트 | 23개 suite, **197개 통과 / 실패 0 / 오류 0 / 스킵 0**, runner 종료 코드 0. 실행 57초 |
| Flyway | 개발·테스트 DB 각각 기존 V1~V13 성공, 실패 이력 0 |
| 실제 HTTP 업무 흐름 | 가입·로그인 → 거래처·청구 100,000원·입금 40,000원 → 배분 40,000원 → 미수금 60,000원 |
| 중복 요청·인증 | 같은 입금 요청 키 재전송 후 입금 1건 유지, 비로그인 `/api/v1/auth/me` 401 |
| 데이터 보존 | backend/db-dev 정상 종료·재시작, 각각 컨테이너 재생성 후 재로그인·같은 건수와 잔액 확인 |
| 최초 DB 기동 | 별도 빈 테스트 볼륨에서 앱 역할·DB 자동 생성 및 앱 계정 인증 healthcheck 성공 |
| 격리 | 별도 계정·볼륨·네트워크, DB 호스트 포트 미공개. 테스트 컨테이너에서 개발 DB의 실제 IP로 TCP 접속 불가 |
| 실행 권한 | backend PID 1의 실제 Java UID/GID 10001. 양쪽 앱 DB 역할의 superuser/createdb/createrole/replication/bypassrls 모두 false |
| 비밀값 | 확인한 최종 빌드·테스트·서비스 로그와 컨테이너 설정에 생성한 비밀번호 값 일치 0건. Git 제외 확인 |

전체 테스트 명령은 위 테스트 절의 `up`에서 이미 빌드한 이미지를 쓰도록 `--build` 대신 `--no-build`를 사용했다. 테스트 XML과 HTTP 검증 결과는 저장소 밖 `/workspace/.local/run/docker-2a/`에 보관했다. 검증용 계정과 데이터만 생성했으며 기존 WSL DB·원본 파일은 가져오거나 변경하지 않았다. 테스트 서비스는 검증 후 중지했고 볼륨은 보존했다.

최초 실행에서 init 스크립트와 빌드 helper 파일의 읽기 권한 문제로 초기화·빌드가 실패했다. bind 파일 권한과 Docker COPY 모드를 수정했다. 부분 초기화된 새 Docker DB는 앱 역할·DB가 모두 없음을 확인한 뒤 스크립트를 실행해 복구했으며 볼륨을 삭제하지 않았다. 수정 후 별도 빈 볼륨의 자동 초기화도 성공했다.

이미지 논리 크기는 backend 약333MiB, test-runner 약720MiB, PostgreSQL 약434MiB였다. 공유 레이어·빌드 캐시·WSL 가상 디스크를 포함한 실제 디스크 사용량과는 다르다. 유휴 시점 backend 약459MiB, 개발 DB 약45MiB 메모리를 관찰했지만 Docker Desktop/WSL·빌드·테스트 및 최대 사용량은 포함하지 않는다.

**2-A 종료 시 미검증:** 사용자 PC의 Docker Desktop/WSL 실행·재부팅, 웹 컨테이너·실제 브라우저 연동, 운영 부하·장애 복구·백업 복원, CI/CD·외부 배포. 18091은 백엔드 API 포트이며 웹 화면 주소가 아니다. 유료 AI 호출은 하지 않았다. 후속 웹 검증 결과는 아래 2-B 기록으로 구분한다.

## 2-B 웹 연결 검증

2026-11-13 후속 진행 지시에 따라 mobile/Dockerfile·.dockerignore, docker/web.conf와 Compose web 서비스를 추가했다. 기존 화면·Java 업무 로직은 유지했다. Node 24와 nginx 공식 이미지는 digest로 고정했다. Node 빌드 단계는 `npm ci`와 `expo export --platform web`을 수행하며 로컬 dotenv 파일을 읽지 않는다. nginx는 비관리자 사용자로 실행한다. 브라우저 Host의 포트를 보존해 같은 origin으로 API를 전달하며 CORS 허용 범위를 넓히지 않는다. API 오류는 SPA HTML로 바꾸지 않고, 없는 정적 파일은404·화면 경로는 index fallback으로 처리한다. backend 주소는 Docker DNS로 재조회한다.

| 검증 | 결과 |
| --- | --- |
| 이미지·기동 | 기본18080 / 테스트18081 주소별 웹 빌드 성공, web/backend/DB health 정상 |
| Chromium 업무 시나리오 | 기존9개 통과, 페이지 오류0. 가입·거래처/청구 등록·입금 응답 유실 후 같은 키 재전송·잔액·월 사용량·세션 만료 확인 |
| 금액 의미 | 미연결 입금40,000원을 등록해도 청구 미수100,000원 유지. 입금만 등록한 상태를 상환 확정으로 표시하지 않음 |
| HTTP 프록시·정적 파일 |7개 통과: health, 인증401 JSON, 동일 origin POST, 없는 asset404, index 재검증, SPA fallback, 7MiB 초과413 |
| 기본 웹 주소 | localhost:18080 실제 Chromium1440×1000 샘플 체험·가로 넘침 없음, 샘플 API0회 |
| 실행·재연결 | 실제 nginx PID1 UID/GID101, 테스트 backend 컨테이너 재생성 후 web 재시작 없이 health 프록시 응답 복구 |

브라우저 업무 검증은 별도 `chagok-2b-check` 프로젝트의 reconciliation_test/chagok_test만 사용했다. 기존 검증 스크립트의 로컬 사본에서 DB 메타데이터 가드·정리 명령을 Docker exec로 바꾸고 기본 뷰포트를 데스크톱으로 맞췄다. 생성한 테스트 계정만 정리했다. 테스트용 Compose override·로그·캡처는 저장소 밖 `/workspace/.local/run/docker-2b/`에 보관했다. 기본 개발 DB에서 자동 업무 테스트를 실행하지 않았다.

첫 실행에서 계정 화면의 비동기 조회와 강제 세션 종료가 겹쳐 테스트 클릭 대상이 사라졌다. 기존 검증 스크립트에 계정 화면·네트워크 완료 대기를 추가한 후9개 전체가 통과했다. 별도로 웹 healthcheck의 wget이 클라우드 프록시로 loopback 요청을 보내403을 받았다. 해당 로컬 프로브에만 `-Y off`를 적용하고 재빌드해 healthy를 확인했다. TLS나 외부 요청의 인증서 검증을 끄지 않았다. 독립 재검토에서 두 수정과 결과를 확인했다.

Java 전체197개 결과는 같은 작업 브랜치의 2-A 검증을 재사용했다. 2-B에서 Java 소스·마이그레이션 변경이 없어 전체 Java 테스트는 반복하지 않았다. 사용자 PC Docker Desktop/WSL·재부팅, iOS/Android 실기기, 운영 부하·백업 복원·CI/CD·외부 배포는 여전히 미검증이다. 커밋·푸시·공개 배포·유료 AI 호출은 수행하지 않았다.
