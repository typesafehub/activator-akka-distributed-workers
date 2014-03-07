name := "akka-distributed-workers"

version := "0.1"

scalaVersion := "2.10.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-contrib" % "2.3.0",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.0",
  "org.scalatest" % "scalatest_2.10" % "2.0" % "test")
