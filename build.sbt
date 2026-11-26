ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "example.recprivacy"

lazy val root = (project in file("."))
  .settings(
    name := "rec-privacy",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.2" % Test,
    Test / parallelExecution := false
  )
