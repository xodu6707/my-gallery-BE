# A6400 Gallery 백엔드 실행 골격

## GitHub CI

저장소: https://github.com/xodu6707/my-gallery-BE

`main` push, PR, 수동 실행 시 `.github/workflows/ci.yml`이 API 테스트와 Flyway 마이그레이션, Sharp 테스트, API Docker 이미지 빌드를 검증한다. `compose.ci.yaml`은 개발 DB와 다른 프로젝트의 휘발 PostgreSQL을 사용한다.

```powershell
docker compose -f compose.ci.yaml up --build --abort-on-container-exit --exit-code-from api-test api-test
docker compose -f compose.ci.yaml down --volumes --remove-orphans
docker build -t gallery-ingest-ci workers/ingest
docker run --rm gallery-ingest-ci npm test
```

FE는 별도 저장소 `my-gallery-FE`다. 로컬에서는 이 저장소를 `backend`, FE를 옆 `frontend` 폴더로 두면 기존 Compose가 동작한다. 다른 위치라면 `FRONTEND_PATH`를 지정한다. `.env`는 커밋하지 않는다. CI는 AWS 접근이나 배포를 수행하지 않는다. AWS CD는 운영 스토리지·SMTP·HTTPS 및 EC2/OIDC 설정 이후 추가한다.

독립 백엔드 저장소로 사용할 디렉터리다. 이메일 인증 가입·로그인·로그아웃·비밀번호 재설정·PostgreSQL 서버 세션과 비공개 JPEG 촬영 세션·업로드·EXIF·이미지 처리·삭제를 구현했다. 자연어 보정 작업 큐와 OCI 운영 어댑터는 다음 단계다. 기능과 남은 한계는 [인증 문서](docs/AUTH.md), [사진 관리 문서](docs/PHOTOS.md)를 참고한다.

사전 조건: Docker Desktop의 Linux 엔진(Windows에서는 WSL2 등 지원 백엔드), Docker Compose, Python 3.10 이상. Docker 빌드가 Java 21/Maven/Node를 준비하므로 호스트에 별도 설치하지 않아도 된다.

프론트 저장소를 이 디렉터리 옆 `frontend`에 두거나 `FRONTEND_PATH` 환경변수에 경로를 지정한다.

```powershell
docker compose up -d --build
python smoke_check.py
python workers/editor/smoke.py
docker compose ps
docker compose stop
```

`docker compose start`로 재시작한다. `docker compose down`은 컨테이너를 제거하지만 이름 있는 볼륨을 보존한다. 데이터 초기화는 **이 테스트 프로젝트의 데이터가 필요 없을 때만** `docker compose down --volumes`로 명시적으로 수행한다. 자동 초기화는 하지 않는다.

| 대상 | 로컬 주소 |
|---|---|
| 웹 | http://127.0.0.1:15173 |
| API 상태 / DB 조회 | http://127.0.0.1:18080/api/stack |
| Spring health | http://127.0.0.1:18080/actuator/health |
| Sharp 상태 | http://127.0.0.1:18090/health |
| Mailpit | http://127.0.0.1:18025 |
| SMTP | Compose 내부 mailpit:1025 |
| PostgreSQL | 127.0.0.1:15432 |
| S3Mock | http://127.0.0.1:19000 |
| 사진 업로드·관리 | http://127.0.0.1:15173/manage (인증 필요) |

Compose의 자격 정보와 ingestion 공유 키는 고정된 로컬 테스트 값이다. 운영에 사용하지 않는다. S3Mock은 테스트 도구이며 OCI의 서명·만료·정책 검증을 대신하지 않는다. 기존 운영 서비스와 데이터는 사용하지 않는다. Mailpit UI는 열 수 있지만 SMTP 컨테이너 포트는 호스트에 공개하지 않으며, API는 Compose 내부 SMTP를 사용한다.

API 시작 시 Flyway V1은 검증용 테이블, V2는 계정·인증 토큰·요청 제한·JDBC 세션, V3는 촬영 세션·사진·작업 대기열·정리 원장 테이블을 생성한다. `/api/stack`은 JPA로 실제 행 수를 조회한다. API는 세션 소유권을 검사하고 이미지는 인증 후 프록시한다. 입력 제한과 통합 테스트는 [사진 관리 안내](docs/PHOTOS.md)에 있다.

인증·사진 통합 테스트는 `compose.auth-test.yaml`의 별도 휘발 테스트 DB를 사용한다. 프로세싱 단위 테스트는 Sharp fixture를 만들어 Docker Node 이미지 안에서 실행한다. 실행 순서와 범위는 [사진 관리 안내](docs/PHOTOS.md)에 기록했다. 개발 DB와 테스트 계정은 공유하지 않는다.

```powershell
docker build --target build -t a6400-api-test ./api
docker run --rm --network a6400-stack-smoke_default -e DATABASE_URL=jdbc:postgresql://postgres:5432/gallery -e DATABASE_USER=gallery -e DATABASE_PASSWORD=local-smoke-only -e MAIL_HOST=mailpit -e MAIL_PORT=1025 a6400-api-test mvn -B -ntp test
```

Sharp는 실행 시 메모리에서 JPEG를 생성하고 120×80 → 60×40 변환 및 EXIF 누락 처리를 확인한다. 이 프로세스는 실제 ingestion 큐를 소비하지 않는다.

Python 스크립트는 `COMFYUI_URL`(기본 localhost:8188)의 `/system_stats`만 조회한다. 설치된 ComfyUI의 설정·큐·모델을 변경하지 않으며 실제 편집을 실행하지 않는다. 서비스 전용 ComfyUI 분리와 워크플로 검증은 후속 작업이다.
