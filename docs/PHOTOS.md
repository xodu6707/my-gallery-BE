# 촬영 세션과 사진 관리

2026-10-15: 계획서 PHOTO-01/02 기본 기능을 구현했다. 날짜별 비공개 촬영 세션, JPEG 다중 업로드·삭제·정렬·대표 지정, EXIF, 썸네일·감상본을 제공한다. API 계약은 [photos.openapi.json](../contracts/photos.openapi.json)이다.

인증 후 [사진 관리](http://localhost:15173/manage)를 연다. 제목·날짜로 촬영 세션을 만들고 JPEG 파일을 선택하거나 끌어 놓는다. 준비가 되면 사진을 열어 촬영 정보를 볼 수 있다.

Mailpit과 S3Mock은 로컬 개발용이다. Mailpit은 실제 Gmail로 메일을 보내지 않는 테스트 메일함이고, S3Mock은 로컬 객체 저장소다. 공개 사진 URL과 OCI 저장소는 아직 구성하지 않았다.

## 파일과 권한

- JPEG/JPG만 허용하며 파일당 50 MiB, 사진당 최대 100MP다. 한 번에 최대 3개를 전송한다. 최대 크기는 `PHOTO_MAX_BYTES`, 픽셀 상한은 `PHOTO_MAX_PIXELS`로 조정할 수 있다. 파일 크기를 높이면 Nginx·Spring multipart 제한도 함께 변경한다. PNG·RAW·HEIC은 지원하지 않는다.
- API는 owner·촬영 세션에 연결된 UUID 키를 발급한다. 인증 세션·CSRF 검사를 거친 프록시 경로로 업로드하고 브라우저에서 저장소로 직접 연결하지 않는다.
- MIME 헤더만 신뢰하지 않고 JPEG 시그니처·실제 크기를 확인한다. 원본 SHA-256은 서버가 계산한다. 완료 요청은 DB 대기열에 한 번만 등록한다. 재사용된 `requestId`는 동일 원본을 가리키며 덮어쓰기를 허용하지 않는다.
- 작업 대기열이 사진을 내부 Node/Sharp 프로세스로 넘긴다. 동시에 하나씩 처리하고 실패는 15초 간격으로 최대 세 차례 재시도한다. 완료 통지 누락은 정합성 확인으로 복구한다.
- EXIF 방향을 적용한 최대 480px 썸네일·2560px 감상본을 별도 sRGB JPEG로 만든다. 원본은 그대로 보존한다. 제조사·기종·렌즈·초점 거리·조리개·노출·ISO·촬영 시각을 읽고 GPS는 제외한다. 시간대 없는 촬영 시각에 시간대를 추정하지 않는다. EXIF 누락·파싱 오류는 사진 처리를 막지 않는다.
- 모든 세션·사진·이미지 읽기/쓰기에 owner를 확인한다. 이미지 응답은 `private, no-store`; 원본은 첨부 파일로 내려받는다. 삭제는 즉시 tombstone을 남겨 숨기고 늦게 도착한 처리 결과도 게시하지 않는다.
- 완료되지 않은 업로드는 24시간 뒤 만료된다. 원본·실패한 시도 결과는 별도 garbage queue에서 지운다. 객체를 쓰기 전에 정리 행을 저장해 프로세스 중단에도 객체를 추적한다.
- 사진 내용, 토큰, 저장 경로는 로그에 기록하지 않는다. 운영 환경에서 별도 구성할 수 있도록 DB와 StoragePort 책임을 분리했다.

## 운영 전 작업

저장 어댑터는 로컬 S3Mock 전용이다. OCI Object Storage 서명, presigned 권한, 버킷 정책, 크기·체크섬 검증과 복구는 아직 구현·검증하지 않았다. DB 대기열과 이미지 처리는 로컬 단일 ingestion용이다. 운영 전 서버 메모리, multipart 임시 파일, garbage 수거 재시도·관측성도 설정해야 한다.

## 검증 방법

격리 검증에는 `backend/compose.auth-test.yaml`의 tmpfs PostgreSQL·Mailpit·S3Mock을 쓴다. 개발 DB와 실제 관리자 계정은 사용하지 않는다. backend에서 해당 Compose의 `down`, `up -d`로 초기화·기동한 다음 frontend에서 인증 테스트를 `AUTH_INTEGRATION=1`로 실행한다. 이어서 격리 ingestion 컨테이너에서 `node fixtures.mjs`를 실행하고 `.smoke/photo-fixtures`로 복사한다. `PHOTO_INTEGRATION=1` 사진 Playwright 테스트와 `node scripts/photo-storage-check.mjs`를 순서대로 실행한다. 마지막으로 기본 Playwright 회귀 테스트를 실행하고 backend에서 격리 Compose만 내린다.

합성 사진 테스트는 EXIF 누락·회전 방향·손상 JPEG·PNG, 실제 업로드, 조회 소유권, 대표·정렬·삭제, 100MP 제한, API 재시작 후 보존, 완료 통지 복구, 삭제 후 늦은 결과와 객체 수거를 다룬다.
