FROM alpine:3.21

LABEL org.opencontainers.image.title="docker-admin-agent" \
      org.opencontainers.image.description="Helper container for docker-admin host file management and Docker CLI" \
      org.opencontainers.image.source="https://github.com/jiangood/docker-admin"

# docker-cli：docker 命令；docker-cli-compose：docker compose；
# ca-certificates：agent 内执行 docker pull/push 时校验 TLS；tzdata：让 docker.agent.tz 生效。
# Alpine 自带 BusyBox，现有文件管理用到的 sh / ls / stat / head / rm / mv / mkdir / sleep / test 都已具备。
RUN apk add --no-cache \
        docker-cli \
        docker-cli-compose \
        ca-certificates \
        tzdata

# 独立运行时的默认行为；平台部署时会覆盖 entrypoint/cmd 和工作目录。
WORKDIR /
ENTRYPOINT ["/bin/sh", "-c"]
CMD ["while true; do sleep 3600; done"]
