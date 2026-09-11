# 백엔드 실행 이미지. 작업지시-15 2절 B.
#
# 빌드와 실행을 나눈다 — 다만 빌드 단계는 이 파일 안이 아니라 **개발 PC 의 Gradle**
# 이다. 이유가 둘이다. 이 프로젝트의 개발 PC 에는 Docker 가 없어 다단계 빌드를
# 로컬에서 돌릴 수 없고, 2GB 서버에서 bootJar 를 돌리는 것은 4절이 금지한다.
# 그래서 jar 는 `./gradlew bootJar` 로 미리 만들어 올리고, 서버의 docker build 는
# 이 파일의 COPY 두 줄뿐이라 메모리를 거의 쓰지 않는다.
#
# 빌드 컨텍스트는 deploy/app 이다. 서버에서는 /opt/staysync/app 이 그 자리이고
# push.sh 가 jar 를 거기 놓는다.
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY staysync.jar /app/staysync.jar
USER app

# 힙은 compose 의 JAVA_TOOL_OPTIONS 가 정한다. 여기 박으면 서버 크기를 바꿀 때
# 이미지를 다시 만들어야 한다.
ENTRYPOINT ["java", "-jar", "/app/staysync.jar"]
