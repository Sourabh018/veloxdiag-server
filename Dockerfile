FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy only pom.xml first — dependency layer gets cached separately from
# source code, so repeat builds (and retries after a 429) only re-download
# deps if pom.xml itself changed, not on every single build.
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java","-jar","app.jar"]