# Stage 1: Build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q
COPY src/ src/
RUN ./mvnw package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

# Install Docker CLI and kubectl so the agent can manage containers and Kubernetes
RUN apt-get update && apt-get install -y --no-install-recommends docker.io curl \
    && curl -LO "https://dl.k8s.io/release/$(curl -Ls https://dl.k8s.io/release/stable.txt)/bin/linux/arm64/kubectl" \
    && chmod +x kubectl && mv kubectl /usr/local/bin/ \
    && rm -rf /var/lib/apt/lists/*

# Create data directory for H2 database persistence
RUN mkdir -p /app/data

COPY --from=build /app/target/*.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

EXPOSE 18789

ENTRYPOINT ["/app/docker-entrypoint.sh"]
