# Not the "-alpine" variant: Alpine's musl libc crashes (SIGSEGV) when
# argon2-jvm's native library is loaded via JNA (it's built for glibc).
# See docs/authPlan.md and the Argon2/JNA crash investigated in this session.
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.11_10_1.12.14_2.12.21

WORKDIR /app

# Cache dependency resolution — only reruns when build files change
COPY project/ project/
COPY build.sbt .
RUN sbt update

COPY . .

EXPOSE 8080

CMD ["sbt", "~run"]
