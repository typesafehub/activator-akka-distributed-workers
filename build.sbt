name := "akka-distributed-workers"

version := "0.1"

scalaVersion := "2.10.4"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-contrib" % "2.3.4",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.4",
  "org.scalatest" % "scalatest_2.10" % "2.1.6" % "test",
  "commons-io" % "commons-io" % "2.4" % "test")
