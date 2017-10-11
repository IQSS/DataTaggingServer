name := """PolicyModelsWebApp"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

organization := "edu.harvard.iq"

scalaVersion := "2.11.7"

routesGenerator := InjectedRoutesGenerator

resolvers += "Typesafe repository releases" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += Resolver.sonatypeRepo("releases")

libraryDependencies ++= Seq(
  ehcache,
  ws,
  guice,
  openId,
  "org.scalatestplus" % "play_2.11" % "1.2.0" % "test",
  "com.typesafe.play" %% "play-json" % "2.6.0",
  "com.vladsch.flexmark" % "flexmark-all" % "0.20.0",
  "org.postgresql" % "postgresql" % "42.0.0",
  "com.typesafe.play" %% "play-slick" % "3.0.0",
  "com.typesafe.play" %% "play-slick-evolutions" % "3.0.0",
  "org.mindrot" % "jbcrypt" % "0.3m",
  "com.typesafe.play" %% "play-iteratees" % "2.6.1",
  "com.typesafe.play" %% "play-mailer" % "6.0.0",
  "com.typesafe.play" %% "play-mailer-guice" % "6.0.0"

)

LessKeys.compress in Assets := true

includeFilter in (Assets, LessKeys.less) := "*.less"

TwirlKeys.templateImports += "views.Helpers"

TwirlKeys.templateImports += "scala.collection.JavaConverters._"


