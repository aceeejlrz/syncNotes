# The production image (section 14.4). Build context: this directory.
#   docker build -t syncnotes-server .
# Needs at runtime: JWT_SECRET, SPRING_DATASOURCE_URL, _USERNAME and _PASSWORD. Serves on port 8080.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
# Dependencies get their own layer, so a code change doesn't download them all again.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
# Tests need Docker (Testcontainers) and run in CI, not here.
RUN ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/syncnotes-server-*.jar extract --layers \
        --application-filename syncnotes-server.jar --destination /extracted

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 --no-create-home syncnotes
WORKDIR /app
# Ordered from least to most often changed, so a typical deploy only replaces the last layer.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 1001
EXPOSE 8080
# Sized for a 512 MB machine (section 14.4). On 256 MB, 70% gets the process killed by the kernel
# under load, so override this with JAVA_TOOL_OPTIONS there.
# Exit on out-of-memory: a JVM limping along after one is worse than Fly restarting the machine.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
# Exec form: java is PID 1 and gets SIGTERM directly, so Spring shuts down gracefully.
ENTRYPOINT ["java", "-jar", "syncnotes-server.jar"]
