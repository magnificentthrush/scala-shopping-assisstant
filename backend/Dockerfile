FROM sbtscala/scala-sbt:eclipse-temurin-17.0.9_9_1.9.7_2.13.12

WORKDIR /app

# Cache dependency resolution — only reruns when build files change
COPY project/ project/
COPY build.sbt .
RUN sbt update

COPY . .

EXPOSE 8080

CMD ["sbt", "~run"]
