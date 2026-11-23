# 이메일 인증 및 서버 세션

2026-10-13: 계획서 5장의 로컬 인증 흐름을 구현했다. 사진 업로드·객체별 소유권 API와 로그인 방해 게임은 별도 후속 범위다.

## 실행

`backend/.env.example`을 `.env`로 복사하고 `OWNER_EMAIL`을 지정한다. `.env`는 Git에서 제외된다. 현재 작업 환경은 사용자가 지정한 소유자 이메일로 설정했으며 아직 계정을 생성하지 않았다. `REGISTRATION_OPEN=true`여도 지정 주소만 가입할 수 있고 이미 등록된 주소에는 가입 링크를 발급하지 않는다. 가입 후 `REGISTRATION_OPEN=false`로 바꿔 컨테이너를 재생성하면 가입 요청·미사용 가입 링크를 모두 닫는다. 로그인·재설정은 계속 가능하다. 설정을 생략하면 가입을 허용하지 않는다.

```powershell
docker compose up -d --build api web mailpit
```

1. http://localhost:15173/signup 에서 소유자 이메일을 입력한다.
2. http://localhost:18025 에서 받은 인증 메일의 링크를 연다.
3. 15~128자 비밀번호를 설정하고 로그인한다.
4. `/account`에서 현재 계정 확인·로그아웃·재설정을 진행한다.

개발 메일은 Mailpit에만 보관되며 실제 Gmail 수신함으로 전송하지 않는다. `PUBLIC_URL`과 실제 사용하는 브라우저 호스트를 맞춘다(`localhost`와 `127.0.0.1` 쿠키는 별개).

## 결정

- 계획서의 Spring Security 서버 세션을 유지한다. JWT를 브라우저 저장소에 저장하지 않는다. PostgreSQL JDBC 세션은 API 재시작 후에도 유지되고 30분 비활동 시 만료된다.
- 비밀번호는 Argon2id, 메모리 19 MiB·2회·병렬도 1·무작위 salt 16바이트로 저장한다. [OWASP 권고](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)를 적용했다. 15~128자를 허용하고 붙여넣기·비밀번호 관리자 사용을 막지 않는다.
- 패스키/WebAuthn은 아직 구현하지 않았다. RP ID·운영 도메인·HTTPS·기기 등록/분실 복구 정책을 확정한 후 추가하는 별도 인증 수단으로 남겼다. [Spring Security 공식 문서](https://docs.spring.io/spring-security/reference/servlet/authentication/passkeys.html).
- 인증 전에 비밀번호를 저장하지 않는다. 메일을 받은 사람이 링크를 직접 열고 비밀번호를 정한다.
- 256비트 무작위 토큰, 20분 만료, 일회용. DB에는 SHA-256 digest만 저장한다. 링크의 fragment에서 읽고 즉시 주소창에서 제거하며 토큰을 localStorage/sessionStorage에 보관하지 않는다. 새로고침으로 메모리 토큰이 사라지면 원래 메일 링크를 다시 연다.
- 토큰 소비와 계정 변경은 트랜잭션으로 처리한다. 계정별 PostgreSQL advisory lock으로 서로 다른 재설정 링크의 동시 사용을 직렬화한다. 성공 시 해당 계정의 다른 인증 링크도 모두 폐기한다.
- 비밀번호 재설정 시 기존 JDBC 세션을 삭제하고 계정 version을 올린다. 요청마다 version을 검증해 동시에 실행 중이던 로그인에서 생성된 오래된 세션도 거부한다.
- HttpOnly·SameSite=Lax 쿠키, 로그인 시 세션 ID·CSRF 토큰 교체, 모든 POST에 CSRF 적용. `/api/auth/csrf`에서 발급한 토큰을 해당 headerName으로 전달한다. 응답은 no-store다.
- 가입 가능 여부와 이메일 존재 여부는 메일 요청 응답으로 공개하지 않는다. 없는 계정의 로그인도 dummy hash 비교를 수행한다. 메일 발송 시간까지 완전히 동일하게 만들지는 않는다.
- PostgreSQL 요청 제한: 로그인 IP 100회/10분, IP+이메일 10회/10분, 메일 IP 30회/시간·용도+이메일 5회/시간, 토큰 검증 IP 30회/10분. 실패한 트랜잭션도 제한 횟수를 유지한다. 현재 Nginx 뒤에서는 IP가 프록시 주소로 묶이므로 운영에서 신뢰 프록시와 실제 IP 전달 정책을 설정해야 한다. 무검증 X-Forwarded-For는 신뢰하지 않는다.
- 자격 증명·원문 인증 토큰은 애플리케이션 로그에 기록하지 않는다. 운영 프록시/APM에서도 인증 요청 본문 수집을 끈다.

## API

명세: [auth.openapi.yaml](auth.openapi.yaml). 인증 요청은 프론트 Nginx의 동일 출처 `/api/` 프록시를 사용한다.

| 메서드·경로 | 입력 | 결과 |
|---|---|---|
| GET `/api/auth/csrf` | 없음 | headerName, token |
| POST `/api/auth/signup/request` | email | 202, 일반 안내 |
| POST `/api/auth/signup/complete` | token, password | 204 |
| POST `/api/auth/login` | email, password | id, email, role + 세션 쿠키 |
| GET `/api/me` | 세션 | id, email, role; 없으면 401 |
| POST `/api/auth/logout` | CSRF | 204, 세션 폐기 |
| POST `/api/auth/reset/request` | email | 202, 일반 안내 |
| POST `/api/auth/reset/complete` | token, password | 204, 기존 세션 폐기 |

유효하지 않은 입력·토큰은 400, 미인증 401, CSRF 오류/닫힌 가입 403, 요청 제한 429, SMTP 실패 503을 반환한다.

## 운영 전 설정

현재 Compose는 로컬 개발용이며 운영 배포가 완료된 상태가 아니다. 운영 DB 비밀번호·백업, HTTPS 프록시, SMTP 공급자를 설정해야 한다.

`PUBLIC_URL=https://실제도메인`, `COOKIE_SECURE=true`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM`, `MAIL_USER`, `MAIL_PASSWORD`, `MAIL_AUTH=true`, `MAIL_TLS=true`를 운영 환경에 제공한다. SMTP는 STARTTLS 방식이며 공급자가 요구하는 포트를 사용한다. `.env`나 비밀 값을 Git에 올리지 않는다. 발송 도메인의 SPF/DKIM/DMARC는 메일 공급자 설정 범위다. SMTP 송신 실패가 DB 반영 전에 발생하면 인증 토큰 저장도 롤백된다.

## 검증

실제 소유자 계정/DB를 변경하지 않는 `compose.auth-test.yaml`을 사용한다. 테스트 DB는 tmpfs이며 테스트 스택을 내리면 폐기된다. 테스트 후 개발 서버는 계속 실행해 둔다.

```powershell
# backend 폴더
docker compose build api web
docker compose -f compose.auth-test.yaml up -d
# 서버 기동 후 frontend 폴더
$env:AUTH_INTEGRATION='1'
npx.cmd playwright test tests/auth-flow.spec.ts --reporter=line
node scripts/auth-storage-check.mjs
Remove-Item Env:AUTH_INTEGRATION
npx.cmd playwright test --reporter=line
# backend 폴더: 테스트 스택만 종료
docker compose -f compose.auth-test.yaml down
```

통합 테스트는 빈 테스트 DB에서 시작해야 한다. 재실행 전 테스트 Compose만 down/up한다. 기본 Playwright 실행은 격리 DB가 필요한 인증 통합 테스트를 건너뛴다.

검증 범위: 지정 외 이메일 차단, 이메일 인증 전 로그인 거부, 실제 메일 링크 가입·재설정, 일회용/만료 토큰, 세션 ID·CSRF 교체, HttpOnly/SameSite, 로그인 새로고침 유지, 기존 세션 일괄 폐기, 로그아웃·요청 제한, Argon2id 저장, API 재시작 후 PostgreSQL 세션 유지. 갤러리·3D·캐릭터·로그인 UI 14개 회귀 테스트도 통과했다.
