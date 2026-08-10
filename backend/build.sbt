ThisBuild / scalaVersion := "2.12.21"

lazy val root = (project in file("."))
  .settings(
    name := "assistant",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "cask" % "0.9.2",
      "com.lihaoyi" %% "upickle" % "3.3.1",
      "org.postgresql" % "postgresql" % "42.7.3",
      "com.google.genai" % "google-genai" % "1.64.0",
      // Auth (see docs/authPlan.md): JWT signing/verification, Argon2id password
      // hashing, and an HTTP client for Supabase REST + the Resend email API.
      // Note: jwt-core (not jwt-upickle) — jwt-upickle 11.0.4 pulls in upickle
      // 4.4.0, which conflicts with cask 0.9.2's upickle 3.x. jwt-core has no
      // upickle dependency; JwtService encodes/decodes the raw claim JSON with
      // our existing upickle version instead.
      "com.github.jwt-scala" %% "jwt-core" % "11.0.4",
      "de.mkammerer" % "argon2-jvm" % "2.12",
      "com.softwaremill.sttp.client3" %% "core" % "3.11.0",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    )
  )