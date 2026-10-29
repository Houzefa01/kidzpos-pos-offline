# T13 — Image Docker pour le backend KidzPOS.
#
# Multi-stage : on builde le JAR dans le stage builder (Maven + JDK 17 lourd),
# puis on le copie dans une image runtime mince (JRE 17 alpine, ~150 Mo).
#
# Le JAR est attendu en input via le build context :
#   docker build -t kidzpos-backend:dev -f backend/Dockerfile backend/
# où backend/ contient le pom.xml + src/ + (optionnellement) un app.jar pré-buildé.
# Le workflow .github/workflows/release.yml pré-construit le JAR avant le build.

FROM eclipse-temurin:17-jre-alpine AS runtime

# Sécurité : utilisateur non-root.
RUN addgroup -S kidzpos && adduser -S kidzpos -G kidzpos -h /app
WORKDIR /app

# Le JAR peut venir :
#  - directement à la racine du context (release.yml fait `cp target/*.jar app.jar`)
#  - sinon, monte ton JAR avec `-v target/kidzpos-backend-1.0.0.jar:/app/app.jar`
COPY --chown=kidzpos:kidzpos app.jar /app/app.jar

USER kidzpos

# Variables runtime — toutes overridables. Le backend refuse de démarrer sans
# JWT_SECRET ≥ 32 octets (cf JwtService) — DOIT être passé en prod.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
ENV SERVER_PORT=8080

EXPOSE 8080

# Healthcheck local : /actuator/health/liveness (Spring Boot 3+) répond toujours
# tant que le process tourne ; pour un readiness complet (DB OK, Flyway OK)
# utiliser /actuator/health côté orchestrateur.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
