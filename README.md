# petgyebu_backend_renewal

React Native 앱을 위한 반려동물 가계부 리뉴얼 MVP 백엔드입니다. 수동 지출 입력, 필수 월 예산, 월별 통계와 현재 월의 반려동물 상태를 구현할 계획입니다. 펫 성장·레벨은 MVP 범위 밖입니다.

## 개발 환경

- Java 25, Gradle Wrapper
- PostgreSQL 16, Spring Data JDBC, Flyway
- 테스트 실행 시 Docker 필요

```bash
./gradlew test --no-daemon
```

요구사항과 API·DB 계약은 [`docs/`](docs/)에 있습니다. Codex 작업 규칙은 [`AGENTS.md`](AGENTS.md)를 참고하세요. GitHub Actions도 동일한 테스트를 실행합니다.
