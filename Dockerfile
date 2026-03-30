FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

COPY --chown=appuser:appgroup --from=build /app/target/quarkus-app/lib/ /app/lib/
COPY --chown=appuser:appgroup --from=build /app/target/quarkus-app/*.jar /app/
COPY --chown=appuser:appgroup --from=build /app/target/quarkus-app/app/ /app/app/
COPY --chown=appuser:appgroup --from=build /app/target/quarkus-app/quarkus/ /app/quarkus/

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseContainerSupport", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
