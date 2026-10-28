# Petgyebu backend 작업 규칙

이 저장소는 React Native 앱이 호출하는 Petgyebu MVP 백엔드다. Java 25, Spring Boot 4.1.1, Gradle, Spring Web MVC, Spring Data JDBC, PostgreSQL 16, Flyway를 사용한다.

## 작업 시작

- 요청을 하나의 기능 ID·백로그 항목·API 또는 재현 가능한 오류 단위로 나누고, 각 단위의 완료 기준을 정한다. 여러 단위가 요청되면 범위를 임의로 줄이지 말고 순서대로 모두 처리한다. 관련 없는 기능·리팩터링은 추가하지 않는다.
- 먼저 요청에 지정된 파일과 관련 코드만 확인한다. 문서가 필요하면 해당 기능 ID나 항목을 검색해 관련 부분만 읽는다. 전체 문서를 미리 읽거나 같은 내용을 반복해서 읽지 않는다.
- 문서 선택 기준: 먼저 `docs/00-renewal-development-guide.md`를 읽는다. 이 파일이 현재 리뉴얼에서 확정된 제품·화면·API 경로 기준이다. `01`~`03`, `05`~`10`은 갱신된 세부 문서이며 충돌하면 `00`이 우선한다. 미확정 항목을 임의로 확정하지 않고 계약 변경 시 관련 문서를 함께 갱신한다.
- `docs/04-review-log.md`는 과거 기록이다. 현재 요구사항의 근거로 사용하지 않는다.
- API·DB 계약을 바꾸면 관련 문서를 함께 수정한다.

## MVP 범위

- 수동 지출 입력·수정·삭제, 월별 목록·통계, 필수 월 예산, 반려동물 선택·현재 월 상태를 구현한다. 성장·레벨은 MVP에서 제외한다.
- 4개 논리 테이블(`users`, `expenses`, `monthly_budgets`, `category_goals`)의 필드·제약·삭제 방식은 `docs/00-renewal-development-guide.md`와 `docs/09-db-design.md`를 따른다. FK·UNIQUE는 유지하고 DB CHECK·ON DELETE CASCADE는 두지 않는다. 월 경계와 상태 기준도 `00`을 따른다.
- 계좌 연동과 자동 수집, Batch, 푸시, 크레딧, 상점, 아이템, 자동 분류 등 MVP 밖의 기능·의존성은 추가하지 않는다. 월 합계 캐시는 실험 대상으로만 다루고 채택 전 성능·정합성을 검증한다.
- Spring Data JDBC의 집합 경계를 명확히 하고 월 지출 행은 명시적 쿼리로 조회하고 합계·카테고리 집계는 Service의 도메인 객체에서 계산한다. `UNIQUE`, `CASCADE`, 잠금은 실제 불변식이나 동시성 요구가 있을 때만 사용한다.

## 구현과 검토 역할

- 코드 작성·수정·리뷰 시 저장소의 `$karpathy-guidelines` 스킬(`.agents/skills/karpathy-guidelines/SKILL.md`)을 반드시 적용한다. 이 규칙은 구현 작업에서 유지한다.
- 기능 개발을 역할로 나눌 때 `.codex/agents/backend-engineer.toml`이 구현하고 `.codex/agents/qa-engineer.toml`이 완료된 변경을 독립 검토한다. 같은 파일을 동시에 수정하지 않는다.
- 역할 구분은 `docs/06-sprint-plan.md`의 현재 MVP 항목에만 적용한다. 예전 프로젝트의 계좌 연동·배치 중심 스프린트 절차는 가져오지 않는다.

## 검증과 보안

- 변경한 동작에는 의미 있는 테스트를 추가한다. 특히 사용자별 접근, 입력 검증, 월 경계와 예산 상태 경계를 확인한다.
- 완료 전 `./gradlew test --no-daemon`을 실행한다. 통합 테스트는 Docker에서 PostgreSQL Testcontainers를 사용하며 CI도 같은 명령을 실행한다.
- 비밀값과 개인 데이터는 소스·문서·로그·커밋에 넣지 않는다. 설정은 환경 변수로 주입한다.
- 프로젝트 Hooks는 아직 사용하지 않는다. 새 Hook 도입은 실행 동작과 신뢰 범위를 검토한 뒤 별도 작업으로 진행한다.
