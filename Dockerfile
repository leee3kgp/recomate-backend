# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy@sha256:a3549cccdf55692d4ea15142d53da096d7181c1645d0a5aa2940271cb74e80c9 AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY src ./src

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon test bootJar

FROM eclipse-temurin:21-jre-jammy@sha256:8ed277f7e3f513b3f7fe9be8e22bbd2a5f9ccd9a8f87dcfa49fbbb2c6a82690f AS runtime-base
WORKDIR /app

COPY --from=build --chmod=0444 /workspace/build/libs/recomate.jar /app/recomate.jar

ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp -Duser.home=/tmp" \
    AWS_LWA_PORT=8080 \
    AWS_LWA_READINESS_CHECK_PROTOCOL=tcp \
    AWS_LWA_ASYNC_INIT=true \
    AWS_LWA_INVOKE_MODE=buffered

USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080"]

ENTRYPOINT ["java", "-jar", "/app/recomate.jar"]

FROM runtime-base AS runtime-ci

FROM public.ecr.aws/awsguru/aws-lambda-adapter:1.1.0@sha256:9d6b29a45b14a4a70f88d53c68b6d5bab1b22d75baca733958fee59308c509b8 AS adapter

FROM runtime-base AS runtime
COPY --from=adapter --chmod=0555 /lambda-adapter /opt/extensions/lambda-adapter
