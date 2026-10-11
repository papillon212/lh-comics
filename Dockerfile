# LH Comics 서버 + iOS용 PWA
# 빌드: docker build -t lh-comics .
# 실행: docker compose up -d  (docker-compose.yml 참고)

# 1) PWA
FROM node:22-alpine AS pwa
WORKDIR /pwa
COPY pwa/package.json pwa/package-lock.json ./
RUN npm ci
COPY pwa/ ./
RUN npm run build

# 2) 서버 (Android 모듈은 빼고 빌드)
FROM gradle:8.14-jdk21 AS server
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY core/ core/
COPY server/ server/
RUN gradle --no-daemon -PserverOnly :server:installDist

# 3) 실행 이미지
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends fonts-noto-cjk curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=server /src/server/build/install/lh-comics-server/ /app/
COPY --from=pwa /pwa/dist/ /app/web/
ENV LHC_DATA=/data \
    LHC_WEB=/app/web \
    LHC_LIBRARIES="만화=/comics" \
    LHC_PORT=8080 \
    JAVA_OPTS="-Xmx512m -Djava.awt.headless=true -Dfile.encoding=UTF-8"
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s CMD curl -fs http://localhost:8080/api/info || exit 1
ENTRYPOINT ["/app/bin/lh-comics-server"]
