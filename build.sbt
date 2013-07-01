name := "akka-distributed-workers"

version := "0.1"

scalaVersion := "2.10.2"

resolvers += "Eligosource Snapshots" at "http://repo.eligotech.com/nexus/content/repositories/eligosource-snapshots"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-contrib" % "2.2.0",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.0",
  "org.scalatest" % "scalatest_2.10" % "1.9.1" % "test",
  "org.eligosource" %% "eventsourced-core" % "0.6-SNAPSHOT",
  "org.eligosource" %% "eventsourced-journal-leveldb" % "0.6-SNAPSHOT")

