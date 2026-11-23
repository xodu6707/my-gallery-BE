---
name: update-docs
description: 갤러리의 AGENTS.md, CLAUDE.md, README와 관련 실행 문서를 실제 코드·설정·CI에 맞춰 동기화한다. 문서 최신화, AI 지침 갱신, 명령·구조·CI 변경 후 문서 동기화에 사용한다. 일반 기능 사양 작성에는 사용하지 않는다.
---

# 코드와 문서 동기화

DONGDONG의 update-docs를 독립 BE·FE 저장소에 맞게 조정했다.

1. 저장소와 git status를 확인하고 사용자 변경을 보존한다. 규칙 원본은 AGENTS.md, CLAUDE.md는 import 진입점이다.
2. 문서끼리 대조하지 말고 실제 설정을 근거로 삼는다. FE는 package.json·lockfile·src/features·Vite/Playwright/Nginx·Dockerfile·워크플로를 확인한다. BE는 api/pom.xml·application.properties·Flyway·workers·Compose·API 계약·워크플로를 확인한다.
3. 경로·명령·포트·버전·CI 트리거·환경변수 이름·구현 상태를 대조한다. .env 비밀값은 옮기지 않는다. 운영과 mock을 구분한다.
4. 불일치만 수정한다. 과거 OCI와 최신 AWS 선택, 현재 JPEG와 후속 RAW/AI 요구사항을 구분한다. 미설치 도구·미구현 테스트를 완료로 기록하지 않는다.
5. AGENTS.md 변경은 README·CLAUDE import와 함께 확인한다. 스킬 name/description을 바꾸면 .claude/skills 래퍼도 맞춘다. formatter가 없으면 새 도구를 설치하지 않고 기존 Markdown 서식을 따른다.
6. 상대 링크·경로·명령과 diff를 검사한다. 문서 변경에 앱 전체 테스트를 반복하지 않는다. 이미 일치하면 변경 없이 보고한다.
7. 기본 결과는 로컬 문서 변경이다. 커밋·push·PR이 요청됐으면 기존 세션 권한 내에서 처리한다. PR은 현재 main 기준이며 develop·자동 릴리즈·필수 팀 승인을 가정하지 않는다. 요청에 없는 댓글·리뷰 응답·배포를 자동 실행하지 않는다.
8. 변경 파일, 근거, 검증, 남은 미구현 항목과 원격 반영 여부를 보고한다.
