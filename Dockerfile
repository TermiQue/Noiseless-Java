FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B verify

FROM eclipse-temurin:21-jre
RUN groupadd --gid 10001 noiseless && useradd --uid 10001 --gid 10001 --create-home noiseless && mkdir /data && chown noiseless:noiseless /data
WORKDIR /app
COPY --from=build /build/target/noiseless.jar /app/noiseless.jar
ENV NOISELESS_HOST=0.0.0.0 NOISELESS_PORT=8080 NOISELESS_DATA_DIR=/data
USER noiseless
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/noiseless.jar","server"]
