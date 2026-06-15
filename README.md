[![ghcr.io](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fghcr.io%2Fv2%2Fjiangood%2Fdocker-admin%2Ftags%2Flist&query=%24.tags%5B0%5D&label=ghcr.io&logo=docker&color=blue)](https://github.com/jiangood/docker-admin/pkgs/container/docker-admin)

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
docker pull ghcr.io/jiangood/docker-admin:v3.0.5
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
