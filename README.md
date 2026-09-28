[![最新版](https://img.shields.io/github/v/tag/jiangood/docker-admin?label=%E6%9C%80%E6%96%B0%E7%89%88&color=blue)](https://github.com/jiangood/docker-admin/pkgs/container/docker-admin)

# 容器管理

- 多主机容器管理
- 持续集成
- 持续部署
- 支持跨网络

# Docker 镜像

以 `node:latest` 为例：
- `node` — **镜像名**
- `latest` — **版本标签**
- `node:latest` — **镜像地址**

```sh
docker pull ghcr.io/jiangood/docker-admin:latest
```

# 安装

## 准备工作

无需准备外部数据库：内置 H2 数据库（文件模式），数据持久化在数据目录中。
只需保证宿主机可访问 Docker 守护进程（`/var/run/docker.sock`）。

## 快速体验（最新版）

```sh
# 一键启动（数据持久化到当前目录 ./data）
docker run -d --name docker-admin \
  -p 7001:7001 \
  -e SERVER_PORT=7001 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v ./data:/data \
  ghcr.io/jiangood/docker-admin:latest
```

或使用 docker compose：

```sh
# 下载 docker-compose 文件
curl -O https://raw.githubusercontent.com/jiangood/docker-admin/main/docker-compose/docker-compose.yml
curl -O https://raw.githubusercontent.com/jiangood/docker-admin/main/docker-compose/application-prod.yml

# application-prod.yml 为可选配置（端口 / 镜像仓库 / git 凭据），数据库无需配置
docker compose up -d
```

## docker-compose 安装

参考
[docker-compose.yml](docker-compose%2Fdocker-compose.yml)

# 使用指南

## 登录

地址：http://127.0.0.1:7001/docker-admin
账号：admin，密码：Open@1234（框架内置种子数据，首次登录后请及时修改；注意 context-path 为 `/docker-admin`）

## 新建项目（负责打包）

点击【项目】->【新建】，依次填写项目名、git 地址（需授权访问）

## 打包

点击项目名称进入项目详情，点击【立即构建】，点击【日志】可查看打包日志

## 部署应用

依次点【应用】【创建应用】，选择镜像和部署主机后确定。等待部署日志显示"部署结束"即可。

## 配置

### 开放端口

比如应用端口是 8080，希望通过 8081 端口访问，则配置映射即可。

### 环境变量

配置 yml 格式。如果是 spring 项目相当于 yml 文件。

### 文件映射

持久化文件，比较重要的文件（如用户上传的文件等）需要设置，否则重新部署后会消失。

## 更新版本

测试环境默认构建（打包）成功自动部署。
