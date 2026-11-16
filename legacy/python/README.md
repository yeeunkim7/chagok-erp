# Python 레거시

FastAPI·SQLAlchemy 전표 실험 코드다. Java 매출채권 API·Expo 클라이언트·Docker·CI는 이 디렉터리를 실행하거나 import하지 않는다.

- `app/`: API·모델·서비스·저장소.
- `alembic.ini`, `migrations/`: Python 전용 Alembic 스키마. Java Flyway와 별개다.
- 실행 작업 디렉터리: `legacy/python/`. 이 위치에서 `app.main:app`과 Alembic의 상대 경로가 해석된다. 전체 API 의존성 잠금 파일은 아직 없으며 전체 API 기동은 미검증이다.
- `DATABASE_URL`이 없으면 **기존 저장소 루트의 accounting.db**를 사용한다. 이동 전 기본 DB 위치를 보존하도록 `app/core/config.py`의 기준 경로만 조정했다. DB 파일 자체는 이동·열람·수정하지 않았다. 검증에는 별도의 임시 빈 DB만 사용한다.

[DB 보존과 빈 DB 검증 절차](migrations/README). 로컬 `.venv`는 이동하거나 재사용하지 않았으므로 필요한 환경은 별도 준비한다.
