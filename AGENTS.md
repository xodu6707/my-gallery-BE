# My Gallery Backend — AI 작업 지침

## 구조·기술

- 원격: `https://github.com/xodu6707/my-gallery-BE`. 이 파일이 있는 `backend/`가 저장소 루트다.
- `api/pom.xml`: Spring Boot 4.1.1, Kotlin 2.3.21, Java 21, Maven. Gradle을 사용하지 않는다.
- Spring Security/JDBC 세션, JPA, Flyway, PostgreSQL 17. `api/src/main/kotlin/dev/filmgallery/api/`가 API 코드다.
- `api/src/main/resources/`는 설정·마이그레이션, `workers/ingest/`는 Node 22 + Sharp/exifr, `workers/editor/smoke.py`는 Python/ComfyUI 상태 확인이다.
- 계약: `contracts/photos.openapi.json`, `docs/auth.openapi.yaml`. API 변경 시 FE의 `src/features/{auth,photos}/api.ts`도 확인한다.
- `S3MockStorage`는 인증 없는 로컬 에뮬레이터 전용이다. 운영 S3/B2/OCI 어댑터처럼 사용하지 않는다.

## 실행·검증

저장소 루트에서 실행한다. FE는 옆 `frontend/`에 두거나 `FRONTEND_PATH`를 지정한다.

```powershell
docker compose up -d --build
python smoke_check.py
python workers/editor/smoke.py
docker compose -f compose.ci.yaml up --build --abort-on-container-exit --exit-code-from api-test api-test
docker compose -f compose.ci.yaml down --volumes --remove-orphans
docker build -t gallery-ingest-ci workers/ingest
docker run --rm gallery-ingest-ci npm test
```

- `.github/workflows/ci.yml`: main push·PR·수동 실행 → 격리 PostgreSQL/Flyway/API 테스트 → Sharp 테스트 → API 이미지 빌드.
- API 테스트는 현재 컨텍스트·DB 검증 1개다. 인증·사진 통합 검증 전체를 통과한 것으로 설명하지 않는다.
- CI DB는 휘발성이며 개발 DB와 분리된다. 테스트 초기화를 위해 개발 Compose 볼륨을 삭제하지 않는다.
- 인증·사진 통합 검증은 `compose.auth-test.yaml`을 사용한다. `docs/AUTH.md`, `docs/PHOTOS.md`의 순서·fixture·계정 의존성을 확인한다.
- 웹 15173, API 18080, DB 15432, ingestion 18090, Mailpit 18025, S3Mock 19000. 기본 호스트 포트는 localhost에만 공개한다.
- ComfyUI smoke의 `NOT_READY`는 편집 성공이 아니다. 기존 ComfyUI 모델·큐·설정을 임의 변경하지 않는다.

## 코드·데이터

- 기존 예외 응답·세션·CSRF·소유권 검사 패턴을 따른다. DONGDONG의 BusinessException·권한 어노테이션·Clock 빈을 가정하지 않는다.
- Controller는 HTTP, Service는 비즈니스·트랜잭션, 저장 어댑터는 외부 I/O를 맡는다. 입력 오류는 적절한 4xx로 응답한다.
- 소유권은 서비스에서도 확인하고 이미지 조회·삭제·재시도에 동일하게 적용한다. 작업 멱등성, 삭제 tombstone, 늦은 처리 결과 차단을 보존한다.
- 적용된 Flyway V1~V3는 수정하지 않는다. 다음 번호의 새 파일을 추가하고 PostgreSQL에서 검증한다. `ddl-auto=validate`를 유지한다.
- 외부 HTTP/SMTP를 긴 DB 트랜잭션에 묶지 않도록 확인한다. 작업 상태는 기존 PostgreSQL을 확장하고 MongoDB·Redis·Kafka를 도입하지 않는다.
- 새 시간 의존 로직은 주입 가능한 Clock 등으로 검증 가능하게 만든다. 기존 시간대 설정을 확인하고 시간대 없는 EXIF에 임의 시간대를 덧붙이지 않는다.
- JPEG 시그니처·용량·픽셀 검증, EXIF 방향 한 번 적용, GPS 제거, 비공개 이미지 응답을 보존한다.

## 공통 작업·Git 규칙

- `git status --short`와 관련 코드를 먼저 확인하고 사용자 변경을 보존한다. BE·FE는 독립 저장소이므로 각 디렉터리에서 Git 명령을 실행한다.
- 현재 통합 브랜치는 `main`이다. 일반 변경은 main 기반 작업 브랜치와 PR을 권장한다. develop·자동 릴리즈·commitlint·CodeRabbit 설치를 가정하지 않는다.
- 커밋은 `type(scope): 한국어 설명`을 권장한다. 예: `docs(ai): 공용 지침 현행화`. 현재 형식 강제 훅은 없다.
- 변경한 경로만 명시적으로 stage한다. `git add .`, `git add -A`, 훅 우회, 무관한 전체 포맷을 사용하지 않는다.
- `.env`, 비밀키, 쿠키, 토큰, 사진 내용, GPS, 서명 URL, 개인 AI 설정을 커밋하거나 로그에 남기지 않는다.
- 실제 결함·권한·데이터 정합성·개인정보·회귀를 우선 리뷰하고 한국어로 근거와 수정 방향을 설명한다.
- 코드·설정이 문서의 근거다. 계획과 구현 상태를 구분한다. 문서만 변경하면 링크·경로·명령과 diff를 검사하며 앱 전체 테스트를 반복하지 않는다.
- 사용자 지시가 우선이다. 이미 승인된 범위는 다시 확인하지 않고 진행한다. 스킬은 무관한 게시·배포 권한을 부여하지 않는다.

## 현재 범위·운영

- 최신 배포 선택은 AWS다. 과거 OCI 계획보다 우선한다. 기존 React·Spring Boot/Kotlin·Maven·PostgreSQL 17·Nginx·Docker Compose는 유지한다.
- 현재 CI는 검증용이다. 이미지 발행·AWS CD·운영 배포는 미구현이다. 유료 AWS/B2 자원 생성은 구체적 설정·비용을 준비해 사용자 확인 후 진행한다.
- 현재 제품은 비공개 JPEG 갤러리다. RAW·AI 후보정·자동 공개·RAW 삭제는 후속 요구사항이다. 기존 사진의 공개·원본 보존 정책에 소급 적용하지 않는다.

## 공용 AI 설정

DONGDONG의 지침·스킬 구조를 현재 갤러리에 맞게 이식했다. 원본 프로젝트는 변경하지 않았다.

- 규칙 원본: `AGENTS.md`; Claude Code 진입점: `CLAUDE.md`의 `@AGENTS.md` import.
- 리뷰 반영: `.agents/skills/apply-review/SKILL.md`.
- 문서 동기화: `.agents/skills/update-docs/SKILL.md`.
- `.claude/skills/*/SKILL.md`는 공용 원본을 읽는 래퍼다. Windows에서 심볼릭 링크를 사용하지 않는다.
- 스킬 파일만으로 CodeRabbit·GitHub 앱이 설치되지는 않는다. 개인 AI 설정은 공유하지 않는다.
