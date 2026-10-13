# M-003 카카오·구글 로그인과 세션 설계

- 작성: 2026-10-13
- 백로그: `M-003` (`F-M01`, Sprint 0)
- 기준: [리뉴얼 개발 기준](../../00-renewal-development-guide.md) 6·8절, [기능명세](../../02-requirements-features.md) F-M01, [DB 설계](../../09-db-design.md) `users`, [인프라](../../05-infra-stack.md)
- 범위: 로그인·갱신·로그아웃 API, 자체 액세스·리프레시 토큰, 한 활성 로그인, 인증 필터, 공통 오류 응답. 실제 카카오·구글 연동, 내 정보·탈퇴(`M-004`), `device_id`는 범위 밖이다.

## 1. 결정 요약

| 항목 | 결정 | 근거 |
|---|---|---|
| 공급자 연동 | `SocialIdentityVerifier` 인터페이스 뒤로 분리. 운영 구현은 연동 전까지 503으로 거부. 테스트는 가짜 구현 | 로그인 요청 본문은 앱인토스 결정 전까지 잠정(`00` 11절) |
| 로그인 본문 | `{ "credential": "<문자열>" }` (잠정) | 인가 코드·공급자 토큰 어느 쪽이든 담을 수 있어 경로·응답을 유지한 채 의미만 확정하면 된다 |
| 액세스 토큰 | HS256 JWT, `sub`=userId, `sid`=세션 ID, 15분. `spring-boot-starter-security-oauth2-resource-server` | 저장 컬럼이 없으므로 자체 검증형이어야 한다. 서명·만료·Bearer 처리는 검증된 라이브러리에 맡긴다 |
| 리프레시 토큰 | `{userId}.{무작위 32바이트 base64url}`, DB에는 SHA-256 16진수 해시만 저장 | PK로 사용자를 찾으므로 해시 컬럼 인덱스·스키마 변경이 필요 없다. 256비트 난수라 느린 해시가 불필요하다 |
| `device_id` | 넣지 않음 | `00` 6절. 한 활성 로그인은 `active_session_id`로 충분하다 |

## 2. API

모든 경로는 `/api/v1` 아래다. 세 경로 모두 인증 없이 호출한다.

| 요청 | 성공 | 실패 |
|---|---|---|
| `POST /auth/login/{provider}` `{credential}` — `provider`는 `kakao` 또는 `google` (대소문자 무시) | 200 `{accessToken, refreshToken, accessExpiresInSeconds: 900}` | 400 `INVALID_REQUEST`, 401 `SOCIAL_LOGIN_FAILED`, 503 `SOCIAL_LOGIN_UNAVAILABLE` |
| `POST /auth/refresh` `{refreshToken}` | 200 (로그인과 같은 형식) | 400 `INVALID_REQUEST`, 401 `UNAUTHORIZED` |
| `POST /auth/logout` `{refreshToken}` | 204 | 400 `INVALID_REQUEST`, 401 `UNAUTHORIZED` |

세 경로는 `Authorization` 헤더가 있어도 무시한다.

그 밖의 모든 API는 `Authorization: Bearer <accessToken>`이 필요하고, 실패하면 401 `UNAUTHORIZED`다.

## 3. 구성 요소

| 구성 요소 | 책임 | 의존 |
|---|---|---|
| `SocialIdentityVerifier` | `(provider, credential)` → `provider_user_id`. 확인 실패는 예외 | — |
| `UnavailableSocialIdentityVerifier` | 운영 기본 구현. 항상 `SOCIAL_LOGIN_UNAVAILABLE` 예외 | — |
| `AuthService` | 로그인(사용자 찾기·생성, 세션 교체), 갱신, 로그아웃 | verifier, `UserRepository`, `TokenIssuer`, `Clock` |
| `TokenIssuer` | 액세스 JWT 발급, 리프레시 토큰 생성·파싱·해시 | `JwtEncoder`, `Clock` |
| `User` / `UserRepository` | `users` 집합(Spring Data JDBC). 지출·예산 컬렉션을 포함하지 않는다. 조건부 UPDATE는 명시적 쿼리 | — |
| 보안 설정 | 무상태 필터 체인, `JwtDecoder`, 세션 검사, JSON 401 진입점 | `UserRepository`, `Clock` |
| `ApiExceptionHandler` | 컨트롤러 예외를 공통 오류 JSON으로 변환 | — |
| `AuthUser(userId)` | 인증된 사용자 ID를 컨트롤러에 전달 | — |

`Clock`은 빈으로 등록해 모든 시각 계산에 쓴다.

## 4. 흐름

### 로그인

1. verifier로 `provider_user_id`를 얻는다. 실패하면 401 `SOCIAL_LOGIN_FAILED`.
2. `INSERT … ON CONFLICT (provider, provider_user_id) DO UPDATE SET last_login_time = EXCLUDED.last_login_time RETURNING id`로 사용자를 찾거나 만들고 `last_login_time = now`를 기록한다. 동시에 들어온 첫 로그인도 사용자 행이 하나만 생긴다.
3. 같은 트랜잭션에서 새 무작위 UUID `active_session_id`, 새 리프레시 토큰 해시, `refresh_token_expires_at = now + 30일`을 저장한다. 이전 로그인의 액세스·리프레시 토큰은 이로써 모두 무효가 된다.
4. 액세스 JWT(`exp = now + 15분`)와 리프레시 토큰을 반환한다.

### 갱신

1. 토큰을 `userId`와 비밀 부분으로 나눈다. 형식이 틀리면 401.
2. 하나의 조건부 UPDATE로 검증과 교체를 함께 한다.

   ```sql
   UPDATE users
   SET refresh_token_hash = :newHash, refresh_token_expires_at = :newExpiresAt
   WHERE id = :userId AND refresh_token_hash = :oldHash AND refresh_token_expires_at > :now
   ```

   바뀐 행이 1이면 성공, 0이면 401. 같은 토큰의 동시 갱신은 하나만 성공한다. 만료 시각은 갱신마다 `now + 30일`로 다시 정한다(마지막 사용 후 30일).
3. `active_session_id`는 유지한다. 갱신 전에 발급된 액세스 토큰은 자신의 만료(최대 15분)까지 유효하다. 계약은 갱신 시 이전 리프레시 토큰의 무효화만 요구한다.

### 로그아웃

갱신과 같은 조건으로 세 세션 컬럼(`active_session_id`, `refresh_token_hash`, `refresh_token_expires_at`)을 `NULL`로 바꾼다. 1행이면 204, 0행이면 401. 이후 이 로그인의 액세스 토큰은 세션 검사에서 거부된다.

### 인증된 요청

1. `JwtDecoder`가 HS256 서명과 `exp`를 검증한다. 허용 시계 오차는 0이고 주입한 `Clock`을 쓴다.
2. 세션 검사: `sub` 사용자를 PK로 조회해 존재하고 `active_session_id = sid`이면 통과한다. 새 로그인·로그아웃·탈퇴 뒤의 이전 액세스 토큰은 여기서 거부된다.

### 해시 비교

리프레시 토큰은 SHA-256 16진수 64자로 저장한다. 조건부 UPDATE가 DB에서 해시 일치를 판단하므로 애플리케이션의 문자열 비교는 없다.

## 5. 오류 응답

형식은 `00` 8절을 따른다: `{ "code", "message" }`, 입력 오류는 `fields: [{ field, message }]`를 더한다. 컨트롤러 예외는 `@RestControllerAdvice`, 보안 필터의 401은 `AuthenticationEntryPoint`가 같은 형식으로 쓴다.

| 상황 | 상태 | `code` |
|---|---|---|
| 본문 형식 오류, 빈 `credential`·`refreshToken`, 지원하지 않는 `provider` | 400 | `INVALID_REQUEST` |
| 공급자 확인 실패 | 401 | `SOCIAL_LOGIN_FAILED` |
| 액세스 토큰 없음·위조·만료, 세션 불일치, 사용자 없음 | 401 | `UNAUTHORIZED` |
| 리프레시 토큰 형식 오류·불일치·만료 | 401 | `UNAUTHORIZED` |
| 운영에서 공급자 연동 전 | 503 | `SOCIAL_LOGIN_UNAVAILABLE` |

401은 사유를 구분하지 않는다. 앱의 처리(갱신 시도 → 실패 시 로그인 화면)가 같고 사유 노출을 피한다.

## 6. 보안 설정과 비밀값

- 무상태. CSRF·폼 로그인·HTTP Basic·서버 세션을 끈다. CORS는 설정하지 않는다(React Native 앱).
- 허용 경로: `POST /api/v1/auth/login/*`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`. 나머지는 인증 필요.
- `petgyebu.jwt.secret` ← 환경 변수 `PETGYEBU_JWT_SECRET`. 없거나 32바이트 미만이면 시작 실패.
- 토큰, `credential`, 리프레시 토큰은 로그에 남기지 않는다.

## 7. 테스트

MockMvc와 PostgreSQL Testcontainers 통합 테스트. 테스트 설정은 가짜 verifier, 앞당길 수 있는 `Clock`, 테스트 전용 서명 키를 쓴다. 인증이 필요한 API가 아직 없으므로 테스트 소스에만 있는 보호 엔드포인트로 액세스 토큰을 검증한다.

| 영역 | 사례 |
|---|---|
| 로그인 | 신규 사용자 생성과 `last_login_time`·세션 ID·해시(원본과 다름)·만료(+30일), 응답 `900`; 기존 사용자 재사용과 `last_login_time` 갱신; 같은 `provider_user_id`·다른 공급자는 별도 계정; `apple`·빈 `credential` 400; verifier 실패 401 `SOCIAL_LOGIN_FAILED`; 운영 verifier 503; 동시 첫 로그인 시 사용자 1행 |
| 한 활성 로그인 | 두 번째 로그인 뒤 첫 로그인의 액세스·리프레시 토큰 401 |
| 액세스 토큰 | 없음·엉터리·다른 키 서명 401; 14분 59초 통과, 15분 정각 401; 401 응답 JSON 형식 |
| 갱신 | 두 토큰 교체, 이전 리프레시 401; 30일 경계 만료; 갱신 시 만료 연장; 형식 오류·다른 사용자 ID 401; 같은 토큰 동시 갱신은 정확히 하나만 200 |
| 로그아웃 | 204 뒤 액세스·리프레시 401; 같은 토큰 재요청 401 |
| 시작 설정 | 서명 키 없음·32바이트 미만이면 컨텍스트 시작 실패 |

## 8. 함께 갱신할 문서

- `00`·`08`: 잠정 로그인 본문 `{credential}`, 새 오류 코드 5개
- `05`: 리소스 서버 starter, `PETGYEBU_JWT_SECRET`
- `10`: `M-003` 검증 기록. 실제 공급자 연동 전이므로 완료 표시하지 않는다.

## 9. 범위 밖

실제 카카오·구글 연동(앱인토스 결정 후 별도 작업), `GET/DELETE /users/me`(`M-004`), 리프레시 토큰 재사용 탐지, `device_id`, 여러 기기 동시 로그인.
