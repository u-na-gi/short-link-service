name := """server"""
organization := "com.example"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "3.3.6"

libraryDependencies += guice
// URL のパースと正規化 (punycode 化・ホストの小文字化) に使う。5 系は JVM 向けに okhttp-jvm を指定する。
libraryDependencies += "com.squareup.okhttp3" % "okhttp-jvm" % "5.5.0"
// ログを JSON で出す (conf/logback.xml)。8 系は Jackson 2.18 を連れてきて、Play が使う Jackson 2.14 を
// 押し上げてしまう (jackson-module-scala 2.14 が起動時に版を検査して落ちる)。9 系は Jackson 3 で
// パッケージ名 (tools.jackson) が別なので、Play の Jackson 2 とぶつからない。
libraryDependencies += "net.logstash.logback" % "logstash-logback-encoder" % "9.0"
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.2" % Test

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "com.example.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "com.example.binders._"
