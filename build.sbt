import sbt._

ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.3.1"
ThisBuild / organization := "com.llmagent"

val zioVersion = "2.0.21"

lazy val testDependencies = Seq(
  "dev.zio" %% "zio-test"          % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt"      % zioVersion % Test,
  "dev.zio" %% "zio-test-magnolia" % zioVersion % Test
)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xfatal-warnings"
  ),
  libraryDependencies ++= testDependencies,
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
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

lazy val dsl = (project in file("dsl"))
  .dependsOn(common, tools)
  .settings(
    commonSettings,
    name := "llm-agent-dsl"
  )

lazy val submit = (project in file("submit"))
  .dependsOn(common)
  .settings(
    commonSettings,
    name := "llm-agent-submit",
    Compile / mainClass := Some("com.llmagent.submit.Main")
  )

lazy val examples = (project in file("examples"))
  .dependsOn(common, tools, dsl)
  .settings(
    commonSettings,
    name := "llm-agent-examples"
  )

lazy val root = (project in file("."))
  .aggregate(common, tools, dsl, submit, examples)
  .settings(
    name := "llm-agent"
  )
