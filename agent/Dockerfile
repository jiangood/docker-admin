FROM alpine:3.21

LABEL org.opencontainers.image.title="docker-admin-agent" \
      org.opencontainers.image.description="Helper container for docker-admin host file management and Docker CLI" \
      org.opencontainers.image.source="https://github.com/jiangood/docker-admin"

# docker-cli：docker 命令；docker-cli-compose：docker compose；
# ca-certificates：agent 内执行 docker pull/push 时校验 TLS；tzdata：让 docker.agent.tz 生效。
# 其余是终端里常用的运维工具，让 Agent 面板可以直接替代登录宿主机排查
# （BusyBox 自带的 applet 功能太弱，curl / bash / ps / ss 之类都没有）。
# 代价是镜像体积变大（约 +100 MB），目标主机首次部署需要拉取一次；离线环境可预加载。
RUN apk add --no-cache \
        # 基础
        docker-cli \
        docker-cli-compose \
        ca-certificates \
        tzdata \
        bash \
        coreutils \
        grep \
        sed \
        gawk \
        findutils \
        less \
        # 网络排查
        curl \
        wget \
        iproute2 \
        net-tools \
        bind-tools \
        netcat-openbsd \
        openssh-client \
        # 文件与文本
        tar \
        zip \
        unzip \
        git \
        rsync \
        tree \
        file \
        lsof \
        lrzsz \
        # JSON / YAML
        jq \
        yq \
        # 编辑器与进程查看
        vim \
        procps \
        htop

# 独立运行时的默认行为；平台部署时会覆盖 entrypoint/cmd 和工作目录。
WORKDIR /
ENTRYPOINT ["/bin/sh", "-c"]
CMD ["while true; do sleep 3600; done"]
