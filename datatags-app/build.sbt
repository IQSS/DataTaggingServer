name := """PolicyModelsWebApp"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala, SbtWeb)

organization := "edu.harvard.iq"

maintainer := "mbarsinai@iq.harvard.edu"

scalaVersion := "2.13.1"

routesGenerator := InjectedRoutesGenerator

resolvers += "Typesafe repository releases" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += Resolver.sonatypeRepo("releases")

libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-parallel-collections" % "0.2.0",
  caffeine,
  ws,
  guice,
  openId,
  "org.scalatestplus.play" %% "scalatestplus-play" % "4.0.3" % "test",
  "org.webjars" % "jquery" % "3.4.1",
  "org.webjars" % "bootstrap" % "4.3.1",
  "org.webjars" % "sweetalert" % "2.1.0" ,
  "org.webjars.npm" % "quill" % "1.3.7",
  "com.typesafe.play" %% "play-json" % "2.6.10",
  "com.vladsch.flexmark" % "flexmark-all" % "0.50.40",
  "org.postgresql" % "postgresql" % "42.0.0",
  "org.webjars" % "popper.js" % "1.14.6",
  "com.typesafe.play" %% "play-slick" % "4.0.2",
  "com.typesafe.play" %% "play-slick-evolutions" % "4.0.2",
  "org.mindrot" % "jbcrypt" % "0.3m",
  "com.typesafe.play" %% "play-mailer" % "7.0.1",
  "com.typesafe.play" %% "play-mailer-guice" % "7.0.1"
)

TwirlKeys.templateImports ++= Seq("views.Helpers", "views.Helpers._",
                                  "scala.jdk.CollectionConverters._")

LessKeys.compress in Assets := true

includeFilter in (Assets, LessKeys.less) := "*.less"

pipelineStages := Seq(uglify, digest, gzip)

//javaOptions ++= Seq("--illegal-access=allow")