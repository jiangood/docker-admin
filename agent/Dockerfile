# lrzsz：ZMODEM 上传/下载依赖的 rz / sz。
# Alpine 3.20 起仓库里已经没有 lrzsz（apk add lrzsz 会报 "no such package"），
# 所以单独编译一份，再把二进制拷进最终镜像。
FROM alpine:3.21 AS lrzsz

RUN apk add --no-cache gcc musl-dev make curl tar \
    && curl -fsSL -o /tmp/lrzsz.tgz https://ohse.de/uwe/releases/lrzsz-0.12.20.tar.gz \
    && mkdir -p /lrzsz && tar -xzf /tmp/lrzsz.tgz -C /lrzsz --strip-components=1 \
    && cd /lrzsz \
    && CC="gcc -std=gnu89" ./configure --disable-nls \
    && make -j"$(nproc)" \
    && strip src/lrz src/lsz \
    && cp src/lrz src/lsz /usr/local/bin/ \
    && test -x /usr/local/bin/lrz && test -x /usr/local/bin/lsz


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
        # JSON / YAML
        jq \
        yq \
        # 编辑器与进程查看
        vim \
        procps \
        htop

# ZMODEM 上传 / 下载需要 rz / sz（见文件顶部的编译阶段）。
# lrzsz 编译出来是 lrz / lsz，rz / sz 是 make install 时建的软链，这里手动补上。
COPY --from=lrzsz /usr/local/bin/lrz /usr/local/bin/lsz /usr/local/bin/
RUN ln -sf lrz /usr/local/bin/rz && ln -sf lsz /usr/local/bin/sz

# 独立运行时的默认行为；平台部署时会覆盖 entrypoint/cmd 和工作目录。
WORKDIR /
ENTRYPOINT ["/bin/sh", "-c"]
CMD ["while true; do sleep 3600; done"]
