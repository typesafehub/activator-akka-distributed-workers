name := "akka-distributed-workers"

version := "0.1"

scalaVersion := "2.10.1"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-contrib" % "2.2.0-RC1",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.0-RC1",
  "org.scalatest" % "scalatest_2.10" % "1.9.1" % "test")
