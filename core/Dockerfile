# Coeur — meme schema que le PDP : build multi-etapes reproductible (wrapper
# Gradle committe) puis JRE mince. Base epinglee (ADR-024). AUCUNE cle ici : le
# certificat client et l'ancre CA sont montes au runtime depuis le volume `certs`.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x ./gradlew && ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
