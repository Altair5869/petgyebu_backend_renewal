# petgyebu_backend_renewal

React Native 앱을 위한 반려동물 가계부 리뉴얼 MVP 백엔드입니다. 수동 지출 입력, 필수 월 예산, 월별 통계와 현재 월의 반려동물 상태를 구현할 계획입니다. 펫 성장·레벨은 MVP 범위 밖입니다.

## 개발 환경

- Java 25, Gradle Wrapper
- PostgreSQL 16, Spring Data JDBC, Flyway
- 테스트 실행 시 Docker 필요

```bash
./gradlew test --no-daemon
```

### 로컬 실행

`PETGYEBU_JWT_SECRET` 환경 변수(32바이트 이상 무작위 값)를 설정하고 Docker를 켠 뒤 실행합니다. 운영 로그인 확인에는 `PETGYEBU_KAKAO_APP_ID`, `PETGYEBU_GOOGLE_CLIENT_IDS`(공개 값)가 필요하다. 비어 있으면 해당 공급자 로그인은 503이다.

```bash
./gradlew bootRun
```

`spring-boot-docker-compose`가 [`compose.yaml`](compose.yaml)의 PostgreSQL 16을 자동으로 띄우고 접속 정보를 연결합니다. 앱을 종료하면 컨테이너도 멈추고 데이터는 `postgres-data` 볼륨에 남습니다. 로컬 DB는 `127.0.0.1:5432`에만 열리며 비밀번호 없이 접속합니다. 초기화하려면 `docker compose down -v`를 실행합니다.

`bootRun`은 실제 소셜 검증기를 쓰므로 위 공급자 설정이 없으면 로그인이 503을 반환합니다. 로그인부터 탈퇴까지 전체 흐름을 로컬 DB로 확인하려면 같은 환경에서 다음을 실행합니다.

```bash
./gradlew bootTestRun
```

`credential: "ok:<아무 ID>"`로 로그인하는 가짜 검증기를 사용합니다. 이 진입점(`LocalDevApplication`)은 테스트 소스에만 있어 배포 jar에는 포함되지 않습니다. `PETGYEBU_JWT_SECRET` 환경 변수가 없으면 시작하지 않습니다.

요구사항과 API·DB 계약은 [`docs/`](docs/)에 있습니다. Codex 작업 규칙은 [`AGENTS.md`](AGENTS.md)를 참고하세요. GitHub Actions도 동일한 테스트를 실행합니다.
