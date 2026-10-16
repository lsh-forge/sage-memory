# 先在项目目录执行 mvn -DskipTests package；镜像只封装本项目的可执行 JAR。
FROM eclipse-temurin:17-jre-jammy

ENV TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=60.0 -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8" \
    SAGE_MEMORY_ADD_WORKSPACE_ROOT=/app/.sage-memory/workspace

ARG JAR_FILE=target/sage-memory-0.1.0-SNAPSHOT.jar

WORKDIR /app

# 无需修改 APT 源或安装额外软件；Temurin Jammy 基础镜像已包含 curl、tzdata。
RUN groupadd --system --gid 10001 sage-memory \
    && useradd --system --uid 10001 --gid 10001 --home-dir /app --no-create-home --shell /usr/sbin/nologin sage-memory \
    && mkdir -p /apps/logs/sage-memory/bak /app/.sage-memory/workspace /app/.sage-memory/add-state \
    && chown -R 10001:10001 /apps/logs/sage-memory /app/.sage-memory \
    && ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

COPY --chown=10001:10001 ${JAR_FILE} /app/sage-memory.jar

USER 10001:10001

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=6s --start-period=60s --retries=3 \
    CMD curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8080/health > /dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/sage-memory.jar"]
