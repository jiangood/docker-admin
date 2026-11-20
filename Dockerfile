FROM node AS web
WORKDIR /build

ADD web/package.json ./
RUN npm install

ADD web/ ./
RUN npm run build



FROM maven:3-amazoncorretto-21 AS java
WORKDIR /build

ADD pom.xml ./
# 只按 pom.xml 预拉依赖，pom 不变时该层命中缓存
RUN mvn -B dependency:go-offline

ADD src src
RUN mvn clean package -DskipTests -q



FROM eclipse-temurin:21-jre-alpine
WORKDIR /home

# 直接运行 fat jar（约 120MB），不再分层抽取：分层模式下 SNAPSHOT 依赖会被单独放进
# snapshot-dependencies 层，若漏复制就会导致运行时缺框架 jar（application-lib.yml 找不到）。
COPY --from=java /build/target/app.jar ./app.jar
COPY --from=web /build/dist/ ./static/

EXPOSE 8601
# 镜像自身即监听 8601，与 EXPOSE、application.yml、docker-compose 及 README 完全一致，
# 因此直接 docker run 无需再传 -e SERVER_PORT。
ENV SERVER_PORT=8601
# 容器内以根路径访问，不再使用 prod profile（application-prod.yml 已移除）。
# 通过环境变量而非 profile：docker run 与 docker compose 行为一致，
# 且仍可用 -e SERVER_SERVLET_CONTEXT_PATH=/docker-admin 覆盖回带前缀的路径。
ENV SERVER_SERVLET_CONTEXT_PATH=/
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-Duser.timezone=Asia/Shanghai","-jar","/home/app.jar"]
