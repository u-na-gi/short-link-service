name := """server"""
organization := "com.example"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "3.3.6"

libraryDependencies += guice
// Used to parse and normalize URLs (punycode, lowercase host). For 5.x, use okhttp-jvm for the JVM.
libraryDependencies += "com.squareup.okhttp3" % "okhttp-jvm" % "5.5.0"
// Writes logs as JSON (conf/logback.xml). 8.x pulls in Jackson 2.18 and bumps the Jackson 2.14 that Play uses
// (jackson-module-scala 2.14 checks the version at startup and fails). 9.x uses Jackson 3 with a different
// package name (tools.jackson), so it does not clash with Play's Jackson 2.
libraryDependencies += "net.logstash.logback" % "logstash-logback-encoder" % "9.0"
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.2" % Test

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "com.example.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "com.example.binders._"
