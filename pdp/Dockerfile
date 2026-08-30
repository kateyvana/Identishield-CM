# PDP — build multi-etapes reproductible (wrapper Gradle 8.14.3 committe) puis
# execution sur un JRE mince. Base epinglee par tag (ADR-024). AUCUNE cle ici :
# le certificat serveur et l'ancre CA sont montes au runtime depuis le volume
# `certs` (genere au demarrage). L'image ne contient que du bytecode.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x ./gradlew && ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8443
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
