[![最新版](https://img.shields.io/github/v/tag/jiangood/docker-admin?label=%E6%9C%80%E6%96%B0%E7%89%88&color=blue)](https://github.com/jiangood/docker-admin/pkgs/container/docker-admin)

# 容器管理

多主机容器管理 · 持续集成 · 持续部署 · 支持跨网络

## 快速开始

内置 H2 数据库，无需外部依赖；数据、日志、上传文件均持久化在 `./data`。

```sh
docker run -d --name docker-admin \
  -p 8601:8601 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v ./data:/data \
  ghcr.io/jiangood/docker-admin:latest
```

或使用 docker compose（配置见 [docker-compose.yml](docker-compose.yml)）：

```sh
curl -O https://raw.githubusercontent.com/jiangood/docker-admin/main/docker-compose.yml
docker compose up -d
```



访问 http://127.0.0.1:8601
账号 `admin`，密码 `Open@1234`（首次登录后请修改）

国内用户可使用阿里云镜像：`registry.cn-hangzhou.aliyuncs.com/jiangood/docker-admin:latest`（与 ghcr.io 同步发布）。



