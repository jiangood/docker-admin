[![最新版](https://img.shields.io/github/v/tag/jiangood/docker-admin?label=%E6%9C%80%E6%96%B0%E7%89%88&color=blue)](https://github.com/jiangood/docker-admin/pkgs/container/docker-admin)

# 容器管理

多主机容器管理 · 持续集成 · 持续部署 · 支持跨网络

## 快速开始

内置 H2 数据库，无需外部依赖；数据、日志、上传文件均持久化在 `./data`。

```sh
docker run -d --name docker-admin \
  -p 7001:7001 -e SERVER_PORT=7001 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v ./data:/data \
  ghcr.io/jiangood/docker-admin:latest
```

或使用 docker compose（配置见 [docker-compose.yml](docker-compose/docker-compose.yml)）：

```sh
curl -O https://raw.githubusercontent.com/jiangood/docker-admin/main/docker-compose/docker-compose.yml
curl -O https://raw.githubusercontent.com/jiangood/docker-admin/main/docker-compose/application-prod.yml  # 可选：端口 / 镜像仓库 / git 凭据
docker compose up -d
```

访问 http://127.0.0.1:7001/docker-admin
账号 `admin`，密码 `Open@1234`（首次登录后请修改）

## 使用

- **新建项目**：【项目】→【新建】，填写项目名、git 地址（需授权访问）
- **构建**：项目详情 →【立即构建】，【日志】查看构建日志
- **部署**：【应用】→【创建应用】，选择镜像与主机，日志显示“部署结束”即完成
- **配置**：支持开放端口、环境变量（yml 格式）、文件映射（持久化重要文件）

> 测试环境默认构建成功即自动部署。

## 镜像与构建

```sh
# 拉取镜像（可指定版本，如 :v4.0.0）
docker pull ghcr.io/jiangood/docker-admin:latest

# 从源码构建
mvn clean package -DskipTests
cd web && npm install && npm run build
docker build -t docker-admin .
```
