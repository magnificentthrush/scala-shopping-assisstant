FROM sbtscala/scala-sbt:eclipse-temurin-alpine-21.0.11_10_1.12.14_2.12.21

WORKDIR /app

# Cache dependency resolution — only reruns when build files change
COPY project/ project/
COPY build.sbt .
RUN sbt update

COPY . .

EXPOSE 8080

CMD ["sbt", "~run"]
