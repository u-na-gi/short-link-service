name := """server"""
organization := "com.example"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "3.3.6"

libraryDependencies += guice
// URL のパースと正規化 (punycode 化・ホストの小文字化) に使う。5 系は JVM 向けに okhttp-jvm を指定する。
libraryDependencies += "com.squareup.okhttp3" % "okhttp-jvm" % "5.5.0"
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.2" % Test

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "com.example.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "com.example.binders._"
