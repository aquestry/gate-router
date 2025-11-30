FROM ghcr.io/minekube/gate:latest AS gate

FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app

COPY --from=gate /usr/local/bin/gate /usr/local/bin/gate

COPY build/libs/gate-panel.jar /app/gate-panel.jar
COPY config.yml /app/config.yml

COPY start.sh /app/start.sh
RUN chmod +x /app/start.sh

EXPOSE 25565 8080

CMD ["/app/start.sh"]