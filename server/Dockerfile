# Build from the repository root: docker build -f server/Dockerfile .
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx1g :server:installDist

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 --no-create-home hive
COPY --from=build /src/server/build/install/server /opt/hive
USER hive
ENV HIVE_MODE=production
EXPOSE 8080
# The server listens on PORT, or on HIVE_PORT, or on 8080.
ENTRYPOINT ["/opt/hive/bin/server"]
