# Cloud Run 배포 이미지. 설정과 비밀값은 실행 환경 변수로 주입한다(docs/11-deploy-runbook.md).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src/main src/main
RUN ./gradlew --no-daemon bootJar && cp "$(find build/libs -name '*.jar' ! -name '*-plain.jar')" app.jar

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
# 컨테이너 메모리 한도의 75%를 힙으로 쓴다(JVM 기본값은 25%).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
