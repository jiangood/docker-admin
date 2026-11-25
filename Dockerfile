FROM node AS web
WORKDIR /build

ADD web/package.json ./
RUN npm install

ADD web/ ./
RUN npm run build



FROM maven:3-amazoncorretto-21 AS java
WORKDIR /build

ADD pom.xml ./
RUN mvn -B dependency:go-offline

ADD src src
RUN mvn clean package -DskipTests -q



FROM eclipse-temurin:21-jre-alpine
WORKDIR /home

COPY --from=java /build/target/app.jar ./app.jar
COPY --from=web /build/dist/ ./static/

EXPOSE 8601
ENV SERVER_PORT=8601
ENV SERVER_SERVLET_CONTEXT_PATH=/
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-Duser.timezone=Asia/Shanghai","-jar","/home/app.jar"]
