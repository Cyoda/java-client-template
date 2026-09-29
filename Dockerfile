# Stage 1: Build the application using Gradle
FROM gradle:8.4-jdk21 AS builder

# Set the working directory in the container
WORKDIR /app

# Copy the project files from the build context (current directory)
COPY . .

# Run Gradle to build the bootJar
RUN ./gradlew bootJar -x test

# Stage 2: Set up the production runtime environment
FROM eclipse-temurin:21-jdk-jammy AS production

# An unprivileged system user (fixed numeric ids, so Kubernetes runAsNonRoot can verify it) owns the app
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --no-create-home --shell /usr/sbin/nologin app

# Set the working directory in the container
WORKDIR /app

# Copy the generated JAR file from the builder stage, owned by the app user
COPY --from=builder --chown=app:app /app/build/libs/app.jar /app/app.jar
RUN chown app:app /app

# Run as the unprivileged user, never root
USER 10001:10001

# Expose the port on which the app will run (default Spring Boot port)
EXPOSE 8080

# Set the default command to run the jar file
ENTRYPOINT ["java", "-jar", "app.jar"]