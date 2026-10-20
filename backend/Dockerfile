# 백엔드 실행 이미지. 두 단계로 만들고, 최종 이미지에는 2단계(JRE + jar)만 남는다.
# 서버는 Arm(linux/arm64)이다. 1단계는 이미지를 만드는 컴퓨터의 CPU($BUILDPLATFORM)로 돌려서 Gradle을 흉내(에뮬레이션) 없이 빠르게 돌린다.
# jar는 CPU 종류와 상관없는 파일이라 2단계(Arm)로 그대로 옮겨 담는다.

# 1단계: 소스에서 jar를 만든다
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 빌드 설정만 먼저 복사해 의존성을 받아 둔다. 소스만 바뀌면 이 층은 다시 쓰여서 의존성을 또 받지 않는다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null

COPY src src
# 테스트는 CI가 PR에서 이미 돌렸다
RUN ./gradlew --no-daemon bootJar -x test

# 2단계: 실행에 필요한 JRE와 jar만 담는다
FROM eclipse-temurin:21-jre
WORKDIR /app

# root로 돌리지 않는다. 앱이 뚫려도 컨테이너 안에서 할 수 있는 일이 줄어든다
RUN groupadd --system bandal && useradd --system --gid bandal bandal
COPY --from=build /workspace/build/libs/bandal.jar app.jar
USER bandal

EXPOSE 8080
# 컨테이너에 준 메모리의 75%까지 힙으로 쓴다. 나머지는 스레드, 메타데이터 같은 힙 밖 메모리 몫이다
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
