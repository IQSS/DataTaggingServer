name := """SamplePoMoSClientApp"""
organization := "edu.harvard.iq"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.1"

libraryDependencies ++= Seq(
  guice,
  ws,
  ehcache
)

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "edu.harvard.iq.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "edu.harvard.iq.binders._"
