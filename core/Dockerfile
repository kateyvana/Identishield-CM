# Coeur — meme schema que le PDP : build multi-etapes reproductible (wrapper
# Gradle committe) puis JRE mince. Base epinglee (ADR-024). AUCUNE cle ici : le
# certificat client et l'ancre CA sont montes au runtime depuis le volume `certs`.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src
# Carte #96 voie 2 (2026-10-25) : le repertoire Gradle (/root/.gradle : distribution du wrapper et
# dependances) est un CACHE de construction BuildKit, partage entre le coeur et le PDP (meme id).
# Un changement de source ou de build.gradle ne retelecharge plus ni Gradle ni les dependances
# deja presentes. Le cache vit hors de l'image : il disparait avec « docker builder prune » ; ne
# jamais l'executer avant la demonstration. Pas de ligne « # syntax= » : elle tirerait une image
# du registre, injoignable depuis la VM ; le frontend integre de BuildKit suffit.
RUN --mount=type=cache,id=isc-gradle-home,target=/root/.gradle \
    chmod +x ./gradlew && ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
