FROM eclipse-temurin:21.0.12.1_1-jdk-noble@sha256:d1eb0297924c2d5a37ba7042a59ae84a3487e086b077ac054019a423767d4311 AS build
WORKDIR /build
COPY gradlew gradlew.bat build.gradle settings.gradle gradle.properties gradle.lockfile ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon resolveDependencies
COPY src ./src
RUN ./gradlew --no-daemon test bootJar

FROM eclipse-temurin:21.0.12.1_1-jre-noble@sha256:817f192b914584dac49595ae662461250b21aaa31df8698feac767d08defb3d0
RUN groupadd --gid 10001 fnord && useradd --uid 10001 --gid 10001 --no-create-home fnord \
    && mkdir -p /app /var/lib/fnord/artifacts /run/jna \
    && chown 10001:10001 /var/lib/fnord/artifacts /run/jna
WORKDIR /app
COPY --from=build /build/build/libs/fnord-dedup.jar ./fnord-dedup.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-Djava.io.tmpdir=/tmp", "-Djna.tmpdir=/run/jna", "-jar", "/app/fnord-dedup.jar"]
