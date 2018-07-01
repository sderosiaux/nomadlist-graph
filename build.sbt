name := "DownloadNomadUsers"

version := "0.1"

scalaVersion := "2.12.6"

libraryDependencies += "com.softwaremill.sttp" %% "core" % "1.2.1"
libraryDependencies += "com.softwaremill.sttp" %% "akka-http-backend" % "1.2.1"
libraryDependencies += "org.typelevel" %% "cats-effect" % "1.0.0-RC2"
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.5.11"
libraryDependencies += "io.scalaland" %% "chimney" % "0.2.0"

val circeVersion = "0.9.3"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser",
  "io.circe" %% "circe-optics"
).map(_ % circeVersion)
