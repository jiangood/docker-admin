[![最新版](https://img.shields.io/github/v/tag/jiangood/docker-admin?label=%E6%9C%80%E6%96%B0%E7%89%88&color=blue)](https://github.com/jiangood/docker-admin/pkgs/container/docker-admin)

# 容器管理
- 多主机容器管理
- 持续集成
- 持续部署
- 支持跨网络

# 安装

## 准备工作
需要提前准备好一个数据库，例如 mysql

## Docker 镜像

```sh
docker pull ghcr.io/jiangood/docker-admin:latest
```

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

## 使用
访问 http://127.0.0.1:7001/docker-admin 账号：superAdmin 密码打印在控制台（注意 context-path 为 `/docker-admin`）
