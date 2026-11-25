val scala213 = "2.13.14"

ThisBuild / scalaVersion := scala213
ThisBuild / organization := "com.ccfb"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "privacy-safe-friend-recommendations",
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.17" % Test,
    scalacOptions ++= Seq(
      "-encoding", "utf8",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xlint:adapted-args",
      "-Xlint:infer-any"
    ),
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
    fork := true,
    Compile / mainClass := Some("privacyrec.PrivacyRecommendationDemo")
  )
