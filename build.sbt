name := "akka-distributed-workers"

version := "0.1"

scalaVersion := "2.10.2"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-contrib" % "2.3-SNAPSHOT",
  "com.typesafe.akka" %% "akka-persistence-experimental" % "2.3-SNAPSHOT",
  "com.typesafe.akka" %% "akka-testkit" % "2.3-SNAPSHOT",
  "org.scalatest" % "scalatest_2.10" % "1.9.1" % "test",
  "commons-io" % "commons-io" % "2.0.1" % "test")

