name := """DataTaggingServer"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

organization := "edu.harvard.iq"

scalaVersion := "2.11.7"

routesGenerator := InjectedRoutesGenerator

resolvers += "Typesafe repository releases" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= Seq(
  cache,
  ws,
  "org.scalatestplus" % "play_2.11" % "1.2.0" % "test"
)

LessKeys.compress in Assets := true

includeFilter in (Assets, LessKeys.less) := "*.less"

TwirlKeys.templateImports += "views.Helpers"
