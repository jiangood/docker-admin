# build web
FROM node AS web
WORKDIR /build


ADD web/package.json ./
RUN npm install

ADD web/ ./
RUN npm run build

# build jar
FROM maven:3-openjdk-17 AS java
WORKDIR /build

ADD pom.xml ./
RUN mvn package -DskipTests -q  --fail-never

ADD src src
RUN mvn clean package -DskipTests -q

# merge web and jar
FROM amazoncorretto:17
WORKDIR /home

COPY --from=java /build/target/app.jar ./
COPY --from=web /build/dist/ ./static/

EXPOSE 80
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-Duser.timezone=Asia/Shanghai","-jar","/home/app.jar","--spring.profiles.active=default,prod"]
