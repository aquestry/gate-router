FROM gradle:8.7-jdk21-alpine AS builder
WORKDIR /workspace
COPY build.gradle.kts settings.gradle.kts ./
COPY src src
RUN gradle clean build -x test

FROM ghcr.io/minekube/gate/jre:latest
WORKDIR /app
COPY --from=builder /workspace/build/libs/*.jar /app/gate-panel.jar
COPY config.yml /app/config.yml
COPY start.sh /app/start.sh
RUN chmod +x /app/start.sh
EXPOSE 25565 8080
ENTRYPOINT ["/app/start.sh"]