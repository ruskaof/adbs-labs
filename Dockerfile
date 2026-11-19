FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY build.gradle settings.gradle gradlew ./
COPY gradle/ gradle/
COPY app/ app/
RUN --mount=type=cache,target=/root/.gradle sh gradlew --no-daemon :app:bootJar

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/app/build/libs/energosbyt.jar .
USER 65532:65532
ENTRYPOINT ["java", "-jar", "/app/energosbyt.jar"]
