import sbt._

ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.3.1"
ThisBuild / organization := "com.llmagent"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xfatal-warnings"
  )
)

lazy val common = (project in file("common"))
  .settings(
    commonSettings,
    name := "llm-agent-common",
    libraryDependencies ++= Seq(
      "com.rabbitmq" % "amqp-client" % "5.20.0",
      "org.xerial" % "sqlite-jdbc" % "3.44.1.0",
      "org.slf4j" % "slf4j-simple" % "2.0.9",
      "dev.zio" %% "zio" % "2.0.21",
      "dev.zio" %% "zio-json" % "0.6.2"
    )
  )

lazy val tools = (project in file("tools"))
  .dependsOn(common)
  .settings(
    commonSettings,
    name := "llm-agent-tools"
  )

lazy val pipeline = (project in file("pipeline"))
  .dependsOn(common, tools)
  .settings(
    commonSettings,
    name := "llm-agent-pipeline"
  )

lazy val submit = (project in file("submit"))
  .dependsOn(common)
  .settings(
    commonSettings,
    name := "llm-agent-submit",
    Compile / mainClass := Some("com.llmagent.submit.Main")
  )

lazy val runners = (project in file("runners"))
  .dependsOn(common, tools, pipeline)
  .settings(
    commonSettings,
    name := "llm-runners"
  )

lazy val root = (project in file("."))
  .aggregate(common, tools, pipeline, submit, runners)
  .settings(
    name := "llm-agent"
  )
