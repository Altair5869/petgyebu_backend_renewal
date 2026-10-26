# 인프라·기술 스택: Petgyebu 리뉴얼 MVP

- 갱신: 2026-10-26
- 기준: [리뉴얼 개발 기준](00-renewal-development-guide.md), 저장소의 `build.gradle`·`AGENTS.md`
- 상태: 현재 저장소의 기술 구성과 MVP 구현 기준. 배포 완료 상태를 뜻하지 않는다.

## 1. 구성과 책임

```text
React Native 앱
    │ HTTPS · JSON
    ▼
Spring Boot API (Java 25 · Spring Web MVC · Spring Security)
    │ Spring Data JDBC · 명시적 월 집계 쿼리 · Flyway
    ▼
PostgreSQL 16 (users · expenses · monthly_budgets)
```

앱은 사용자 입력, 펫 이미지·표정, 화면 이동과 목록 이어보기를 담당한다. 서버는 인증, 사용자 범위의 데이터 접근, 입력 검증, 월 합계와 상태 계산을 담당한다. PostgreSQL의 지출 내역과 월 예산이 원본 데이터다. 같은 계산 규칙을 홈과 상세 통계에 사용한다.

| 영역 | 현재 기준 | 적용 상태·근거 |
|---|---|---|
| 앱 | React Native | 백엔드가 연결될 별도 앱 프로젝트. 현재 저장소에는 앱 코드가 없다. 펫 연출의 에셋 형식·렌더링 방식은 이 문서에서 고정하지 않는다. |
| 백엔드 | Java 25, Spring Boot 4.1.1, Gradle Groovy, Spring Web MVC | `build.gradle`과 `AGENTS.md`에 명시된 현재 프로젝트 구성 |
| 인증 | Spring Security, 카카오·구글 로그인, 서버 자체 액세스·리프레시 토큰 | Security 의존성은 존재한다. 소셜 연동과 토큰 발급·갱신은 구현 대상이다. 액세스 토큰은 HS256 JWT이며 `spring-boot-starter-security-oauth2-resource-server`로 발급·검증한다. 리프레시 토큰은 무작위 값이며 DB에 SHA-256 해시만 저장한다. |
| 데이터 접근 | Spring Data JDBC, PostgreSQL 16 | JDBC 의존성이 있다. 사용자·월 집계는 명시적 쿼리로 계산한다. JPA·QueryDSL은 도입하지 않는다. |
| 스키마 | Flyway, PostgreSQL용 Flyway 모듈 | 의존성이 있다. 세 논리 테이블과 제약·인덱스는 마이그레이션으로 관리한다. 앱 실행 시 자동 스키마 생성에 맡기지 않는다. |
| 설정·비밀값 | `application.yaml`, 환경 변수 | 설정 파일은 저장소에 있다. DB 접속 정보·토큰 서명 키·소셜 인증 비밀값은 소스와 로그에 남기지 않는다. 토큰 서명 키는 `PETGYEBU_JWT_SECRET`(32바이트 이상, `openssl rand -base64 32`처럼 무작위로 생성한 값이며 사람이 정한 문자열은 쓰지 않는다)으로 주입하며 없으면 앱이 시작되지 않는다. 카카오 앱 ID(PETGYEBU_KAKAO_APP_ID)와 구글 클라이언트 ID 목록(PETGYEBU_GOOGLE_CLIENT_IDS, 쉼표 구분)은 공개 값이며 비어 있으면 해당 공급자 로그인이 503이다. |
| 테스트 | JUnit, Spring Boot 테스트, PostgreSQL Testcontainers | 현재 테스트 의존성이 있다. DB 계약을 검증하는 통합 테스트는 Docker의 PostgreSQL을 사용한다. |
| 배포 계획 | Google Cloud Run(서울) + PostgreSQL 16. 초기 DB는 Supabase Free(서울), 사용자가 늘면 Cloud SQL | 비용을 줄이려고 단계별로 구성한다(5절). 실제 서비스 생성, 요금·용량·네트워크 설정은 배포 시 검증한다. 현재 구성만으로 배포 완료를 뜻하지 않는다. |
| 시간 기준 | `Asia/Seoul` | ‘오늘’, 지출 날짜, 월 시작과 다음 달 시작을 동일하게 해석한다. |

## 2. 인증과 세션

카카오·구글 로그인 뒤 서버가 자체 액세스·리프레시 토큰을 발급한다. 액세스 토큰은 15분, 리프레시 토큰은 마지막 사용 후 30일 유효하다. 갱신할 때 두 토큰을 새로 발급하고 이전 리프레시 토큰은 무효화한다. 카카오 액세스 토큰은 토큰 정보 API로, 구글 ID 토큰은 JWKS로 확인하며 연결 2초·읽기 3초 타임아웃을 둔다. 앱인토스용 토스 로그인은 후속 작업이다.

한 계정에는 활성 로그인 하나만 둔다. 새 로그인은 기존 로그인을 무효화한다. `users.active_session_id`는 로그인 식별자이며 기기 ID가 아니다. 인증 요청은 액세스 토큰의 세션 ID를 현재 사용자 값과 비교해 종료된 로그인을 거부한다. DB에는 원본 리프레시 토큰 대신 해시와 만료 시각을 저장한다. `last_login_time`은 소셜 로그인 성공 시 갱신한다. `device_id`는 MVP에 저장하지 않는다.

로그아웃은 서버의 현재 로그인을 무효화하고 앱이 보관한 토큰을 지운다. 회원 탈퇴는 지출 → 월 예산 → 사용자 순으로 한 DB 트랜잭션에서 명시적으로 삭제한다. 외래 키는 유지하되 `ON DELETE CASCADE`를 사용하지 않는다.

## 3. 데이터와 계산

논리 테이블은 `users`, `expenses`, `monthly_budgets` 세 개다. 별도 펫·세션·월 집계 테이블은 두지 않는다. `users`에는 펫 종류와 현재 로그인 정보가, `expenses`에는 수동 지출이, `monthly_budgets`에는 사용자별 월 예산이 들어간다. 월 예산은 API의 `YYYY-MM`을 해당 월 첫날의 `DATE`로 바꿔 저장한다.

DB에는 합의한 `NOT NULL`, 외래 키, `UNIQUE(provider, provider_user_id)`, `UNIQUE(user_id, budget_month)`를 둔다. 지출 목록의 날짜·ID 역순 조회를 위해 `(user_id, expense_date DESC, id DESC)` 인덱스를 둔다. 동일 사용자·월 예산의 동시 PUT은 한 행만 남기는 upsert로 처리한다. 금액 양수, 카테고리 일곱 가지, 예산 월 첫날 정규화는 애플리케이션에서 검증하고 DB `CHECK`는 두지 않는다. 직접 DB 변경은 이 검증을 우회하므로 마이그레이션·운영 절차에서 별도 확인한다.

월 합계는 사용자와 `Asia/Seoul` 월 범위 `[첫날, 다음 달 첫날)`에 속한 지출을 DB에서 계산한다. 예산 잔액은 `budgetAmount - spentAmount`의 부호를 유지한다. 사용률 표시값은 소수 첫째 자리에서 버리고, 펫 상태는 버리기 전 실제 비율로 판정한다. 펫 상태는 현재 월에만 계산한다. 과거 월에는 펫 데이터가 없으며 예산 없는 과거 월은 예산 관련 값만 `null`이다. 카테고리 비중은 소수 첫째 자리 반올림이다. 정확한 필드와 경계는 개발 기준을 따른다.

데이터 접근은 Spring Data JDBC로 한다. 단순 조회는 파생 쿼리를 쓰고, 다음 경우는 `@Query`의 PostgreSQL SQL로 쓴다. 예산·로그인의 `INSERT … ON CONFLICT … RETURNING` upsert, 리프레시 토큰의 조건부 `UPDATE`(비교와 교체를 한 문장에서 처리), `(expense_date, id)` 행 값 비교 커서, 월 `SUM`·`GROUP BY` 집계, 탈퇴의 명시적 `DELETE`다. JPA·QueryDSL은 도입하지 않는다(2026-10-25 재검토).

- 세 테이블 사이에 연관 탐색이 없고 쓰기는 모두 한 문장 `UPDATE`다. 그래서 JPA의 연관 매핑·변경 감지·1차 캐시로 얻는 이점이 작다.
- 원자적 upsert, 조건부 교체, 행 값 커서는 JPQL로 표현할 수 없다. JPA를 쓰면 네이티브 쿼리로 돌아가거나, 잠금·재시도를 새로 설계해야 한다.
- 요청마다 실행되는 SQL이 코드에 그대로 보여야 성능을 측정하고 `EXPLAIN`으로 분석할 수 있다. flush 시점과 지연 로딩이 SQL을 바꾸면 이 대조가 어려워진다.
- 모든 쿼리 조건이 고정(사용자·월 범위·커서)이라 QueryDSL이 강점을 보이는 동적 조건 조합이 없다.

지출 검색·필터처럼 선택 조건이 늘어나거나, 집합 사이 탐색이 많은 도메인이 추가되면 다시 판단한다. 그때는 JPA 전환보다 비용이 작은 jOOQ·Spring Data JDBC `Criteria`를 먼저 비교한다.

## 4. 월 합계 캐시 실험

1. DB 원본 조회로 홈·통계의 월 합계를 먼저 정확하게 구현하고 실제 성능을 측정한다.
2. 병목이 확인되면 **사용자·월별 지출 합계만** 캐시하는 실험을 한다. 홈 응답 전체나 펫 상태를 캐시하지 않는다.
3. 지출 생성·수정·삭제가 DB에 확정된 뒤 영향받은 월의 캐시를 무효화한다. 날짜가 다른 월로 바뀌면 이전 월과 새 월을 모두 무효화한다. 예산 변경은 지출 합계 캐시를 지울 필요가 없다.
4. 캐시 장애 시 DB 조회로 동작한다. 성능 개선과 동시 변경 시 최신성을 검증한 후 출시 적용 여부를 정한다. 구현 제품·TTL·키 포맷은 그때 결정한다.

따라서 현재 기본 런타임에는 Redis 등 캐시 서비스가 필요하지 않다. 캐시는 출시 필수 조건이 아니다. 저장된 월 집계 테이블, 스냅샷, 비동기 이벤트 집계, 월 전환 배치도 MVP에 포함하지 않는다.

## 5. 로컬 개발·검증·배포

- 빈 PostgreSQL에 Flyway 마이그레이션을 적용해 시작한다. 이전 프로젝트의 17개 테이블과 계좌 연동 마이그레이션을 복사하지 않는다.
- 로컬 실행(`./gradlew bootRun`)은 개발 전용 의존성 `spring-boot-docker-compose`(`testAndDevelopmentOnly`)가 `compose.yaml`의 PostgreSQL 16을 띄우고 접속 정보를 연결한다. 로컬 DB는 `127.0.0.1`에만 열고 비밀번호 없는 trust 인증을 쓴다. 이 의존성은 배포 산출물에 포함되지 않고 테스트에서는 Spring Boot 기본값으로 건너뛰며, 운영 DB 접속 정보는 `SPRING_DATASOURCE_*` 환경 변수로 주입한다.
- 소셜 연동 전 전체 흐름 확인은 `./gradlew bootTestRun`으로 한다. 테스트 소스의 `LocalDevApplication`이 `credential: "ok:<아무 ID>"`를 받는 가짜 검증기와 실제 시계로 같은 로컬 DB에 붙는다. 배포 jar에는 포함되지 않으며 `bootRun`은 실제 검증기(공급자 설정이 없으면 503)를 유지한다.
- 로컬·CI에서 `./gradlew test --no-daemon`으로 테스트한다. 사용자별 접근, 입력 검증, 서울 시간대의 월 경계, 50%·80%·100% 상태 경계, 예산 upsert·탈퇴 트랜잭션을 검증한다.
- 배포는 비용에 맞춰 단계별로 구성한다. 앱 서버는 처음부터 Cloud Run(`asia-northeast3`, 요청 기반 과금, 최소 인스턴스 0)에 두고 무료 사용량 안에서 운영하는 것을 목표로 한다. 최소 인스턴스 0이면 한동안 요청이 없던 뒤 첫 요청은 JVM 기동 시간만큼 늦다. 불편하면 최소 인스턴스를 1로 올린다. Vercel·Cloudflare Workers는 JVM을 실행하지 못해 제외했다.
  1. 출시 초기: DB는 Supabase Free(서울 리전, 500MB). 7일 동안 활동이 없으면 일시정지되어 다시 켜기 전까지 API가 실패한다. 무료 플랜의 백업에 기대지 않고 `pg_dump`로 정기 백업을 받는다.
  2. 사용자 증가 또는 무료 한도 근접: Cloud SQL for PostgreSQL 16(같은 리전, `db-f1-micro`부터. 공유 코어라 SLA 대상이 아니다)으로 옮긴다. 스키마는 Flyway로 같게 만들고 데이터는 `pg_dump`·`pg_restore`로 옮긴다. Supabase 유료 플랜보다 Cloud SQL 소형 인스턴스가 싸다.
- DB 접속은 `SPRING_DATASOURCE_*`만 바꾼다. Supabase는 세션 풀러로 연결하고(직접 연결은 IPv6 전용이고, 트랜잭션 풀러는 pgJDBC의 서버 측 prepared statement와 충돌한다), Hikari 풀 크기와 Cloud Run 최대 인스턴스 수를 DB 연결 한도 안에서 함께 정한다.
- DB 접속 정보·토큰 서명 키는 Secret Manager로 주입한다. 카카오 앱 ID·구글 클라이언트 ID는 공개 값이라 일반 환경 변수로 둔다. 예약 작업이 없다는 가정 아래 인스턴스·CPU·비용 설정은 실제 트래픽에 맞춰 정한다.
- 계정 설정과 배포 절차는 [배포 절차](11-deploy-runbook.md)를 따른다. 배포 이미지는 `Dockerfile`, 배포는 `.github/workflows/deploy.yml`(`v*` 태그 또는 `main` 수동 실행)이다. Supabase 신규 프로젝트는 PostgreSQL 17이 기본이며 현재 마이그레이션이 PG 17에서 적용되는 것을 확인했다.
- 해외 사업자(Google·Supabase)가 개인정보를 처리하므로 개인정보처리방침에 처리 위탁·국외 이전 내용을 적는다.
- 계좌 연동, CODEF·금융결제원 SDK, Spring Batch·ShedLock, 금융 API 재시도, FCM, 보상·상점용 저장소는 MVP 구성에 넣지 않는다. 앱의 펫 연출 에셋을 위한 서버 스토리지·CDN도 현재 필수 구성으로 두지 않는다.

## 6. 후속 확인

앱인토스용 토스 로그인과 캐시의 출시 적용·구현 제품은 [개발 기준의 후속 결정](00-renewal-development-guide.md)으로 남긴다. 이 문서의 배포 계획은 인프라 선택을 기록한 것이며, 계정·네트워크·비용을 검증해 실제 배포할 때 운영 설정을 확정한다.
