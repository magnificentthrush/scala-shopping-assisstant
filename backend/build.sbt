ThisBuild / scalaVersion := "2.12.21"

lazy val root = (project in file("."))
  .settings(
    name := "assistant",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "cask" % "0.9.2",
      "com.lihaoyi" %% "upickle" % "3.3.1",
      "org.postgresql" % "postgresql" % "42.7.3",
      "com.google.genai" % "google-genai" % "1.64.0",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    )
  )