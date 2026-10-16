# 카카오·구글 로그인 실제 연동 설계

- 작성: 2026-10-16
- 백로그: `M-003` (`F-M01`) 후속 — 운영 `SocialIdentityVerifier`를 실제 공급자 검증으로 교체
- 기준: [리뉴얼 개발 기준](../../00-renewal-development-guide.md) 6·8·11절, [기능명세](../../02-requirements-features.md) F-M01, [M-003 설계](2026-10-13-m003-auth-session-design.md)
- 대상 클라이언트: 스토어 배포용 React Native 앱(카카오·구글 네이티브 SDK)
- 범위 밖: 앱인토스용 토스 로그인(5절), 인가 코드 교환 방식, 실제 개발자 콘솔 키로 확인하는 일(`M-010`)

## 1. 결정 요약

| 항목 | 결정 | 근거 |
|---|---|---|
| `credential` 의미 | 카카오는 SDK가 준 **액세스 토큰**, 구글은 Google Sign-In이 준 **ID 토큰** | 네이티브 SDK의 기본 결과를 그대로 쓴다. 서버에 비밀값·리디렉션 URI가 필요 없다 |
| 카카오 검증 | `GET https://kapi.kakao.com/v1/user/access_token_info` (`Authorization: Bearer <토큰>`) 응답의 `app_id`가 설정한 앱 ID와 같으면 `id`를 사용자 ID로 쓴다 | `app_id` 비교로 다른 앱에서 발급된 토큰을 거부한다 |
| 구글 검증 | JWKS `https://www.googleapis.com/oauth2/v3/certs`로 RS256 서명 확인, `iss` ∈ {`accounts.google.com`, `https://accounts.google.com`}, `aud` ∈ 설정한 클라이언트 ID, `exp` 미경과. 통과하면 `sub` | 이미 있는 Spring Security `NimbusJwtDecoder` 재사용, 새 의존성 없음 |
| 설정 | `PETGYEBU_KAKAO_APP_ID`, `PETGYEBU_GOOGLE_CLIENT_IDS`(쉼표 구분). 모두 공개 값 | 비밀값을 늘리지 않는다 |
| 설정이 빈 공급자 | 그 공급자만 503 `SOCIAL_LOGIN_UNAVAILABLE` | 설정 없이도 앱이 시작되고 로컬·테스트 동작이 그대로다 |
| 트랜잭션 | 공급자 확인(외부 HTTP)은 DB 트랜잭션 밖에서 실행하고, 검증 뒤 짧은 트랜잭션으로 사용자 upsert·세션 시작 | 외부 호출 동안 DB 연결을 잡지 않는다 (M-003 리뷰 체크리스트) |
| 사용자 ID 검증 | 공급자가 준 ID가 비었거나 255자를 넘으면 401 `SOCIAL_LOGIN_FAILED` | `provider_user_id VARCHAR(255)` (M-003 리뷰 체크리스트) |

로그인 API 경로·요청 형식(`{credential}`)·응답은 바뀌지 않는다.

## 2. 구성 요소

| 구성 요소 | 책임 |
|---|---|
| `SocialIdentityVerifier` (기존 인터페이스) | `(provider, credential)` → `provider_user_id` |
| 운영 구현 (기존 `UnavailableSocialIdentityVerifier` 대체) | 공급자별 검증기로 위임 |
| `KakaoTokenVerifier` | 카카오 토큰 정보 API 호출, `app_id` 비교, `id` 반환 |
| `GoogleIdTokenVerifier` | JWKS 기반 ID 토큰 검증, `sub` 반환 |
| 설정 프로퍼티 | 카카오 앱 ID, 구글 클라이언트 ID 목록, 카카오 API 기본 주소, 구글 JWKS 주소(기본값은 실제 주소, 테스트에서 가짜 서버로 교체) |

- HTTP 호출은 Spring `RestClient`, 연결 2초·읽기 3초 타임아웃.
- 구글 JWKS는 Nimbus 기본 캐시를 쓰고, 알 수 없는 키 ID가 오면 다시 가져온다.
- 테스트의 가짜 확인기(`TestAuthConfig`)와 `bootTestRun`(`LocalDevApplication`)은 `@Primary`로 운영 구현을 덮으므로 그대로 동작한다.

## 3. 오류 처리

| 상황 | 응답 |
|---|---|
| 카카오가 401/400 응답 (만료·위조 토큰) | 401 `SOCIAL_LOGIN_FAILED` |
| 카카오 `app_id` 불일치 | 401 `SOCIAL_LOGIN_FAILED` |
| 구글 서명·`iss`·`aud`·`exp` 불일치, JWT 형식 아님 | 401 `SOCIAL_LOGIN_FAILED` |
| 공급자 사용자 ID가 비었거나 255자 초과 | 401 `SOCIAL_LOGIN_FAILED` |
| 카카오 5xx·429·타임아웃·연결 실패 | 503 `SOCIAL_LOGIN_UNAVAILABLE` |
| 구글 JWKS를 가져오지 못함 | 503 `SOCIAL_LOGIN_UNAVAILABLE` |
| 공급자 응답 본문이 예상과 다름 (`id`·`app_id` 누락 등) | 503 `SOCIAL_LOGIN_UNAVAILABLE` |
| 해당 공급자 설정이 비어 있음 | 503 `SOCIAL_LOGIN_UNAVAILABLE` |

- 원인은 서버 로그에 WARN으로 남긴다: 공급자, HTTP 상태, 오류 코드만. 토큰과 응답 본문은 남기지 않는다.
- 서버는 재시도하지 않는다. 503이면 앱이 다시 시도를 안내한다.

## 4. 테스트

새 의존성 없이 JDK `com.sun.net.httpserver.HttpServer`로 가짜 공급자 서버를 띄운다.

- 카카오: 정상, `app_id` 불일치, 401, 5xx, 429, 읽기 타임아웃, 필드 누락, 빈·256자 `id`
- 구글: 테스트에서 만든 RSA 키로 서명한 정상 토큰, 다른 키로 서명, `aud` 불일치, `iss` 불일치, 만료, 형식 오류, JWKS 서버 장애
- 공급자 설정이 비었을 때 503
- 공급자 확인이 트랜잭션 밖에서 실행됨 (검증기 안에서 활성 트랜잭션이 없음을 확인)
- 기존 테스트와 `bootTestRun`이 그대로 동작

## 5. 앱인토스(토스 로그인) — 후속 결정

2026-10-16 조사 결과, 앱인토스 미니앱은 로그인으로 **토스 로그인만** 쓸 수 있다 ("미니앱에서는 로그인 기능으로 토스 로그인만 쓸 수 있어요. 자사 로그인이나 다른 간편 로그인 방식은 쓸 수 없어요." — [토스 로그인 소개](https://developers-apps-in-toss.toss.im/login/intro.html)). 따라서 앱인토스 출시에는 카카오·구글 대신 별도 공급자 `TOSS`가 필요하다. 이번 범위에서는 구현하지 않고 다음을 기록만 한다.

- 흐름: 미니앱 `appLogin` → 인가 코드(10분, 1회용)·`referrer` → 서버가 `POST /api-partner/v1/apps-in-toss/user/oauth2/generate-token`으로 교환 → `GET .../login-me`로 `userKey`(앱 단위 식별자) 조회 ([연동 방법](https://developers-apps-in-toss.toss.im/documentation/common/authentication/toss-login))
- 서버 간 통신에 mTLS 인증서 필요, 운영 방화벽에 토스 IP 대역 허용 필요 ([API 사용하기](https://developers-apps-in-toss.toss.im/documentation/integration/server-api))
- 연결 끊기 콜백(`UNLINK`, `WITHDRAWAL_TERMS`, `WITHDRAWAL_TOSS`) 처리 방식은 미결정

## 6. 문서 변경

- `00`·`02`·`05`·`08`·`09`: "`credential` 의미는 잠정" 문구를 1절 결정으로 확정, 환경 변수 2개 추가, 앱인토스는 토스 로그인만 허용된다는 조사 결과를 후속 결정으로 기록
- 앱 개발자 안내: 카카오는 SDK의 `accessToken`, 구글은 `idToken`을 보낸다. Android에서 `idToken`을 받으려면 Google Sign-In 설정에 `webClientId`가 필요하며, 이 웹 클라이언트 ID도 `PETGYEBU_GOOGLE_CLIENT_IDS`에 포함한다
- `10`: `M-003`은 `[ ]`로 둔다. "서버 검증 구현 완료, 실제 키 확인은 `M-010`" 기록

## 7. 완료 기준

- 위 테스트를 포함해 `./gradlew test --no-daemon` 통과
- 공급자 설정이 없는 운영 실행에서 두 공급자 모두 503 유지
- qa-engineer 독립 검토 통과
