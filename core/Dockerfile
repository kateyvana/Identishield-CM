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
# sharing=locked (fenetre 6 du 2026-11-03) : les constructions du coeur et du PDP tournent en
# parallele dans deux conteneurs. Gradle ne sait pas se passer un verrou d'un conteneur a l'autre
# (« Timeout waiting to lock journal cache … in use by another Gradle instance ») : BuildKit
# donne donc le cache a une seule construction a la fois ; l'autre attend la fin de son RUN.
RUN --mount=type=cache,id=isc-gradle-home,target=/root/.gradle,sharing=locked \
    chmod +x ./gradlew && ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
