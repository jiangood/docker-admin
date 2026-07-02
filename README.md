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

需要提前准备好一个数据库，例如 mysql

## 快速体验（最新版）

```sh
# 下载 docker-compose 文件
curl -O https://raw.githubusercontent.com/jiangood/docker-admin/main/docker-compose/docker-compose.yml
curl -O https://raw.githubusercontent.com/jiangood/docker-admin/main/docker-compose/application-prod.yml

# 按需修改 application-prod.yml 中的数据库配置后启动
docker compose up -d
```

## docker-compose 安装

参考
[docker-compose.yml](docker-compose%2Fdocker-compose.yml)

# 使用指南

## 登录

地址：http://127.0.0.1:7001/docker-admin
账号：superAdmin，密码打印在控制台（注意 context-path 为 `/docker-admin`）

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
