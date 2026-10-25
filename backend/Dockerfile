FROM gradle:8.11.1-jdk21 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . /home/gradle/src
RUN gradle :backend:installDist -Dorg.gradle.java.home=/opt/java/openjdk --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget
RUN addgroup -S gymtracker && adduser -S gymtracker -G gymtracker
WORKDIR /app
COPY --from=build /home/gradle/src/backend/build/install/backend /app
USER gymtracker
EXPOSE 8081
ENTRYPOINT ["/app/bin/backend"]
