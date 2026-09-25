# 인프라·기술 스택: Petgyebu MVP

- 기준일: 2026-09-25
- 과거 의사결정 근거는 `sources/05-infra-stack.md`와 `04-review-log.md`에 보존한다. 아래는 새 프로젝트의 MVP 구성이다.

## 구성

```text
React Native 모바일 앱 (iOS·Android)
    │ HTTPS
    ▼
Spring Web MVC JSON API (Java 25, Gradle, Spring Security)
    │ Spring Data JDBC + Flyway
    ▼
PostgreSQL 16 (users, expenses, monthly_budgets)
```

| 영역 | MVP 선택 | 이유 |
|---|---|---|
| 앱 | React Native | iOS·Android 공용 앱. 화면과 반려동물 정적 에셋을 앱에서 제공한다. |
| 백엔드 | Java 25, 안정 버전 Spring Boot 4.x, Gradle Groovy, Jar, Spring Web MVC | React Native가 호출하는 JSON REST API를 제공한다. 생성 시 선택한 Boot 버전과 Gradle Wrapper를 함께 기록한다. |
| 인증 | 카카오·애플 OAuth 인가 코드 검증, Spring Security, 짧은 수명의 액세스 JWT | 사용자 식별에 필요한 최소 경로다. 인증된 요청마다 `users` 존재를 확인한다. |
| 데이터 | PostgreSQL 16, Spring Data JDBC | 세 테이블을 독립된 집합으로 다루고 월 통계는 명시적 SQL로 집계한다. JPA·QueryDSL은 사용하지 않는다. |
| 스키마 | Flyway 및 PostgreSQL용 Flyway 모듈 | 새 프로젝트의 첫 마이그레이션에서 3테이블을 생성한다. 앱 코드가 스키마를 자동 생성하지 않는다. |
| 설정 | `application.yaml`, 환경 변수 | 한 가지 파일 형식으로 통일하고 비밀값은 환경 변수로 주입한다. |
| 배포 | Google Cloud Run + Cloud SQL | 배포 시 구성한다. 예약 작업이 없으므로 상시 CPU·최소 인스턴스는 필수가 아니다. 실제 비용과 트래픽으로 설정한다. |
| 시간 | `Asia/Seoul` | 지출 날짜와 월 경계를 통일한다. |

백엔드 초기 의존성은 Spring Web MVC, Validation, Spring Data JDBC, PostgreSQL Driver, Flyway와 PostgreSQL용 Flyway 모듈, Spring Security다. 소셜 로그인 구현 방식이 확정되면 필요한 OAuth2·JWT 라이브러리를 추가한다. React Native는 별도 앱 프로젝트의 의존성을 관리한다.

## 구현 원칙

- 월 통계는 `expenses`에서 사용자 ID와 날짜 범위 `[월 첫날, 다음 달 첫날)`로 집계한다. 저장된 집계, 캐시, 야간 배치는 없다.
- 상태와 성장은 조회 시 계산한다. 상태 기준은 `01-prd.md`의 70%·100% 경계이며 별도 임계값 테이블은 없다.
- Cloud SQL 연결 정보·JWT 서명 키·소셜 인증 비밀값은 Secret Manager 또는 배포 환경의 비밀 설정으로 관리한다. 소스와 로그에 남기지 않는다.
- 로컬·CI 통합 테스트는 Docker의 PostgreSQL Testcontainers를 사용해 Flyway와 실제 제약을 확인한다. 새 프로젝트는 빈 DB에서 시작하며 과거 17테이블 마이그레이션을 복사하지 않는다.
- 과거 계정·예산 코드를 참고하더라도 새 계약에 맞는 부분만 옮긴다. 과거 완료 기록으로 새 MVP 작업을 완료 처리하지 않는다.

## 제외하는 구성

Redis, ShedLock, Spring Batch, CODEF SDK, 금융결제원 연동, Resilience4j 금융 API 재시도, JPA, QueryDSL, FCM, 캐릭터 에셋용 서버 스토리지·CDN, 웹 앱은 MVP 런타임·의존성에서 제외한다. 반려동물 정적 에셋은 React Native 앱에 포함한다. `SELECT FOR UPDATE`가 필요한 잔액 차감 흐름도 없다.
