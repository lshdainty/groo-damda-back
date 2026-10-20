# 베이스는 버전을 고정한다 — 움직이는 태그는 빌드 시점 따라 내용물이 바뀐다. 업데이트는 태그를 올려서.
FROM eclipse-temurin:25.0.4_7-jdk-alpine AS builder

WORKDIR /app

# GitHub Packages(groo-core) 자격증명.
#
# 토큰은 ENV 로도 ARG 로도 두지 않는다. ENV 는 빌더 스테이지 이미지 설정(Config.Env)에
# 평문으로 박히고, ARG 는 빌드 캐시 스텝 메타데이터에 남는다. 멀티스테이지라 최종 런타임
# 이미지에는 안 남지만 빌드 호스트(Jenkins)의 이미지·캐시에는 남는다 — 데몬을 읽을 수
# 있으면 그대로 새어 나간다. BuildKit 도 옛 형태에 SecretsUsedInArgOrEnv 경고를 낸다.
#
# secret 마운트는 RUN 이 도는 동안에만 tmpfs 로 붙고 어떤 레이어에도 남지 않는다.
# 계정명(GITHUB_ACTOR)은 비밀이 아니므로 ARG 로 두고 RUN 환경으로 그대로 넘긴다.
ARG GITHUB_ACTOR

COPY . .

RUN --mount=type=secret,id=github_token \
    chmod +x gradlew \
    && GITHUB_TOKEN="$(cat /run/secrets/github_token)" ./gradlew clean build -x test

FROM eclipse-temurin:25.0.4_7-jre-alpine AS runtime

WORKDIR /app

# 비-root 실행 — 컨테이너가 침해돼도 root 권한을 넘기지 않고, 마운트된 호스트 볼륨에
# root 소유 파일이 쌓이지 않게 한다(호스트에서 백업·정리 시 sudo 불필요).
# UID/GID 는 호스트 볼륨 소유권과 맞아야 하므로 고정한다 — Jenkinsfile 이 같은 값으로
# 마운트 디렉터리를 chown 하고 build-arg 로도 같은 값을 넘긴다.
ARG APP_UID=1000
ARG APP_GID=1000
# 컨테이너 사용자 이름. 옛 스택은 porest, 새 스택(Jenkinsfile.groo)은 groo.
ARG APP_USER=porest
RUN addgroup -g ${APP_GID} ${APP_USER} \
    && adduser -u ${APP_UID} -G ${APP_USER} -D -H -h /app ${APP_USER}

COPY --from=builder --chown=${APP_USER}:${APP_USER} /app/build/libs/*.jar app.jar

USER ${APP_USER}

EXPOSE 8002

# 프로세스 생존이 아니라 앱 응답(actuator/health)을 본다 — Jenkins 헬스 게이트가 이 상태를 기다린다
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -qO- "http://localhost:${SERVER_PORT:-8002}/actuator/health" | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
