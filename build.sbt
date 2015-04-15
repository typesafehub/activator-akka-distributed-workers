name := "akka-distributed-workers"

version := "0.1"

scalaVersion := "2.11.5"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-contrib" % "2.3.9",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.9",
  "org.scalatest" %% "scalatest" % "2.2.4" % "test",
  "commons-io" % "commons-io" % "2.4" % "test")
