FROM eclipse-temurin:21-jre-alpine AS builder
WORKDIR /app
COPY . .
RUN ./gradlew :app:bootJar -x test -x ktlintCheck -x detekt

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/app/build/libs/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
