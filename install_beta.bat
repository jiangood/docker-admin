chcp 65001 > nul
@echo off


echo 中文显示测试
cd web
call pnpm install @jiangood/open-admin@beta --registry https://packages.aliyun.com/62d39be70065edd3d51c1984/npm/npm-registry/


