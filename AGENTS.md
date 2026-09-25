# Petgyebu backend 작업 규칙

이 저장소는 React Native 앱이 호출하는 Petgyebu MVP 백엔드다. Java 25, Spring Boot 4.1.1, Gradle, Spring Web MVC, Spring Data JDBC, PostgreSQL 16, Flyway를 사용한다.

## 작업 시작

- 구현 전 `docs/01-prd.md`, `docs/02-requirements-features.md`, `docs/08-feature-implementation-map.md`, `docs/09-db-design.md`에서 해당 기능의 계약을 확인한다. 작업 순서는 `docs/10-task-backlog.md`를 따른다.
- `docs/04-review-log.md`와 `docs/11-troubleshooting-log.md`는 과거 기록이다. 현재 요구사항의 근거로 사용하지 않는다.
- 한 번에 하나의 작은 기능을 완성하고, API·DB 계약을 바꾸면 관련 문서를 함께 수정한다.

## MVP 범위

- 수동 지출 입력·수정·삭제, 월별 목록·통계, 월 예산, 반려동물 선택·상태·간단한 성장만 구현한다.
- 기본 테이블은 `users`, `expenses`, `monthly_budgets` 세 개다. 스키마는 Flyway로 변경한다. 월 경계와 상태 기준은 문서의 정의를 따른다.
- 계좌 연동과 자동 수집, Redis, Batch, 푸시, 크레딧, 상점, 아이템, 자동 분류 등 MVP 밖의 기능·의존성은 추가하지 않는다.
- Spring Data JDBC의 집합 경계를 명확히 하고 월 통계는 명시적 쿼리로 계산한다. `UNIQUE`, `CASCADE`, 잠금은 실제 불변식이나 동시성 요구가 있을 때만 사용한다.

## 검증과 보안

- 변경한 동작에는 의미 있는 테스트를 추가한다. 특히 사용자별 접근, 입력 검증, 월 경계와 예산 상태 경계를 확인한다.
- 완료 전 `./gradlew test --no-daemon`을 실행한다. 통합 테스트는 Docker에서 PostgreSQL Testcontainers를 사용하며 CI도 같은 명령을 실행한다.
- 비밀값과 개인 데이터는 소스·문서·로그·커밋에 넣지 않는다. 설정은 환경 변수로 주입한다.
- 프로젝트 Hooks는 아직 사용하지 않는다. 새 Hook 도입은 실행 동작과 신뢰 범위를 검토한 뒤 별도 작업으로 진행한다.
