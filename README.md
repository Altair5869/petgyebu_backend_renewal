# petgyebu_backend_renewal

React Native 앱을 위한 반려동물 가계부 MVP 백엔드입니다. 수동 지출 입력, 월별 통계와 예산, 반려동물 선택·상태·간단한 성장을 구현합니다.

## 개발 환경

- Java 25, Gradle Wrapper
- PostgreSQL 16, Spring Data JDBC, Flyway
- 테스트 실행 시 Docker 필요

```bash
./gradlew test --no-daemon
```

요구사항과 API·DB 계약은 [`docs/`](docs/)에 있습니다. Codex 작업 규칙은 [`AGENTS.md`](AGENTS.md)를 참고하세요. GitHub Actions도 동일한 테스트를 실행합니다.
