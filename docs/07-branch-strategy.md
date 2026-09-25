# 브랜치 전략

- 관련 프로젝트: 반려동물 감정 기반 소비 관리 가계부 앱 백엔드
- 최종 갱신: 2026-09-25 (새 MVP와 새 저장소 기준)
- 관련 문서: `02-requirements-features.md`, `06-sprint-plan.md`

## 1. 브랜치 단위 — 기능(F-MXX) 단위

`02-requirements-features.md`의 기능 ID 하나당 브랜치 하나가 원칙이다. 현재 ID는 `F-M01`~`F-M06`이다. 스프린트 하나가 기능
여러 개로 구성되는 경우(예: Sprint 1의 F-M02 + F-M03), 기능별로 별도 브랜치를
만들어 독립적으로 병합한다.

| 유형 | 네이밍 | 예시 |
|------|--------|------|
| 기능 구현 | `feature/{F-ID}-{영문-슬러그}` | `feature/F-M03-manual-expenses` |
| 인프라/설정 (F-ID 없는 Sprint 0 항목) | `chore/{영문-슬러그}` | `chore/spring-boot-init` |
| 버그 수정 | `fix/{영문-슬러그}` | `fix/monthly-expense-total` |

모든 브랜치는 최신 `main`에서 분기한다.

## 2. 병합 방식 — Squash merge

PR을 `main`에 병합할 때는 **Squash merge만 사용**한다. 기능/작업 단위로 커밋 1개가 남아
`main`의 이력이 작업·PR 단위로 남는다. 병합 후 브랜치는 삭제한다.

새 저장소의 GitHub 설정은 아직 적용 여부를 확인하지 않았다. 저장소를 만든 뒤 squash merge,
병합 후 브랜치 삭제, `main`에 PR을 요구하는 규칙을 설정하고 실제 적용 상태를 확인한다.
이전 저장소의 2026-09-15 설정 기록은 새 저장소의 설정 상태를 증명하지 않는다.

## 3. main 원칙

`main`은 항상 빌드 가능한 상태를 유지한다. 백엔드 PR 병합 전에는 최소 빌드 통과를 확인한다
(CI 구성 전까지는 로컬 `./gradlew build`로 확인). React Native 앱은 앱의 빌드·검증 명령을 별도로 실행한다.

## 4. 태그 — 스프린트 완료 시점

스프린트 하나가 완전히 완료되면(`06-sprint-plan.md`의 해당 완료 조건 충족)
`main`에 `v0.{N}.0` 태그를 남긴다. 배포·롤백 기준점으로 사용한다.

## 5. 커밋 메시지

Conventional Commits 형식을 따른다 (`feat:`, `fix:`, `chore:`, `docs:` 등). PR 제목이 squash
커밋 메시지가 되므로, PR 제목을 Conventional Commits 형식으로 작성한다.
