# 构建：docker build -t jinbang-platform .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 10001 jb && mkdir -p /app/data && chown jb /app/data
COPY --from=build /src/jb-app/target/jinbang-platform.jar /app/app.jar
USER jb
ENV JB_PROFILE=prod JB_STORAGE_DIR=/app/data/files
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
