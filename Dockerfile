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



FROM eclipse-temurin:21-jre-alpine
WORKDIR /home

COPY --from=java /build/target/app.jar ./
COPY --from=web /build/dist/ ./static/

EXPOSE 7001
# 容器内以根路径访问，不再使用 prod profile（application-prod.yml 已移除）。
# 通过环境变量而非 profile：docker run 与 docker compose 行为一致，
# 且仍可用 -e SERVER_SERVLET_CONTEXT_PATH=/docker-admin 覆盖回带前缀的路径。
ENV SERVER_SERVLET_CONTEXT_PATH=/
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-Duser.timezone=Asia/Shanghai","-jar","/home/app.jar"]
