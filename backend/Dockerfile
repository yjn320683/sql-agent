FROM node:20-bullseye AS front-build
WORKDIR /workspace/front
COPY front/package*.json ./
RUN npm ci --no-audit --no-fund
COPY front ./
RUN npm run build

FROM maven:3.8.8-eclipse-temurin-8 AS backend-build
WORKDIR /workspace
COPY pom.xml ./
COPY backend/pom.xml backend/pom.xml
COPY datacompare/pom.xml datacompare/pom.xml
COPY parse-sql/pom.xml parse-sql/pom.xml
RUN mvn -q -pl backend -am -DskipTests dependency:go-offline
COPY backend backend
COPY datacompare datacompare
COPY parse-sql parse-sql
COPY --from=front-build /workspace/front/dist front/dist
RUN mvn -q -pl backend -am -DskipTests package

FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=backend-build /workspace/backend/target/sql-agent-backend-*.jar /app/sql-agent-backend.jar
EXPOSE 8382
ENTRYPOINT ["java", "-jar", "/app/sql-agent-backend.jar"]
