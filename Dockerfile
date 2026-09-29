FROM node AS web
WORKDIR /build

ADD web/package.json ./
RUN npm install

ADD web/ ./
RUN npm run build



FROM maven:3-amazoncorretto-21 AS java
WORKDIR /build

ADD pom.xml ./
RUN mvn package -DskipTests -q  --fail-never

ADD src src
RUN mvn clean package -DskipTests -q

# 按依赖/应用分层抽出，使体积最大的 lib 层在依赖版本未变时保持同一 digest，
# 避免每次发布都把整个 fat jar（约 120MB）重新推送到镜像仓库。
RUN java -Djarmode=tools -jar target/app.jar extract --layers --destination /build/extracted



FROM eclipse-temurin:21-jre-alpine
WORKDIR /home

# 依赖层：约 120MB，仅在依赖变动时更新
COPY --from=java /build/extracted/dependencies/lib ./lib
# 应用层：约 200KB（抽出的瘦 jar，通过 MANIFEST 的 Class-Path 引用 ./lib），每次发布都会变化
COPY --from=java /build/extracted/application/app.jar ./
COPY --from=web /build/dist/ ./static/

EXPOSE 7701
# 镜像自身即监听 7701，与 EXPOSE、application.yml、docker-compose 及 README 完全一致，
# 因此直接 docker run 无需再传 -e SERVER_PORT。
ENV SERVER_PORT=7701
# 容器内以根路径访问，不再使用 prod profile（application-prod.yml 已移除）。
# 通过环境变量而非 profile：docker run 与 docker compose 行为一致，
# 且仍可用 -e SERVER_SERVLET_CONTEXT_PATH=/docker-admin 覆盖回带前缀的路径。
ENV SERVER_SERVLET_CONTEXT_PATH=/
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-Duser.timezone=Asia/Shanghai","-jar","/home/app.jar"]
