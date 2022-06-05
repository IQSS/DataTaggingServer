
name := """PolicyModelsWebApp"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala, SbtWeb)

organization := "edu.harvard.iq"

maintainer := "mbarsinai@iq.harvard.edu"

scalaVersion := "2.13.7"

// Targeting JDK11, which is the current LTS
javacOptions ++= Seq("-source", "11", "-target", "11")

routesGenerator := InjectedRoutesGenerator

resolvers += "Typesafe repository releases" at "https://repo.typesafe.com/typesafe/releases/"

resolvers += Resolver.sonatypeRepo("releases")

libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4",
  caffeine,
  ws,
  guice,
  openId,
  "org.webjars" % "jquery" % "3.5.1",
  "org.webjars" % "bootstrap" % "4.5.0",
  "org.webjars" % "popper.js" % "1.16.0",
  "org.webjars" % "sweetalert" % "2.1.0" ,
  "org.webjars.npm" % "quill" % "1.3.7",
//  "com.typesafe.play" %% "play-json" % "2.6.10",
  "com.vladsch.flexmark" % "flexmark-all" % "0.60.2",
  "org.postgresql" % "postgresql" % "42.3.4",
  "com.typesafe.play" %% "play-slick" % "5.0.0",
  "com.typesafe.play" %% "play-slick-evolutions" % "5.0.0",
  "org.mindrot" % "jbcrypt" % "0.4",
  "com.typesafe.play" %% "play-mailer" % "8.0.1",
  "com.typesafe.play" %% "play-mailer-guice" % "8.0.1",
  "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
)

TwirlKeys.templateImports ++= Seq("views.Helpers", "views.Helpers._",
                                  "scala.jdk.CollectionConverters._")

//Assets / LessKeys.compress := true
//
//Assets / LessKeys.less / includeFilter := "*.less"
//
//Assets / LessKeys.less / sourceMap := true

pipelineStages := Seq(digest, gzip)

//javaOptions ++= Seq("--illegal-access=allow")

// Disable documentation creation
Compile / doc / sources := Seq.empty
Compile / packageDoc / publishArtifact := false