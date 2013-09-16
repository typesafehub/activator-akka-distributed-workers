name := "akka-distributed-workers"

version := "0.1"

scalaVersion := "2.10.2"

resolvers += "Typesafe Snapshots" at "http://repo.typesafe.com/typesafe/snapshots/"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-contrib" % "2.3-20130916-200212",
  "com.typesafe.akka" %% "akka-persistence-experimental" % "2.3-20130916-200212",
  "com.typesafe.akka" %% "akka-testkit" % "2.3-20130916-200212",
  "org.scalatest" % "scalatest_2.10" % "1.9.1" % "test",
  "commons-io" % "commons-io" % "2.0.1" % "test")

