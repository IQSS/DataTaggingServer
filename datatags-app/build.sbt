name := """PolicyModelsWebApp"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala, SbtWeb)

organization := "edu.harvard.iq"

scalaVersion := "2.12.8"

routesGenerator := InjectedRoutesGenerator

resolvers += "Typesafe repository releases" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += Resolver.sonatypeRepo("releases")

libraryDependencies ++= Seq(
  caffeine,
  ws,
  guice,
  openId,
//  "org.scalatestplus" % "scalatestplus-play" % "3.1.2" % "test",
  "org.webjars" % "jquery" % "3.3.1-2",
  "org.webjars" % "bootstrap" % "4.2.1",
  "org.webjars" % "sweetalert" % "2.1.0" ,
  "com.typesafe.play" %% "play-json" % "2.6.10",
  "com.vladsch.flexmark" % "flexmark-all" % "0.40.0",
  "org.postgresql" % "postgresql" % "42.0.0",
  "org.webjars" % "popper.js" % "1.14.6",
  "com.typesafe.play" %% "play-slick" % "4.0.0",
  "com.typesafe.play" %% "play-slick-evolutions" % "4.0.0",
  "org.mindrot" % "jbcrypt" % "0.3m",
  "com.typesafe.play" %% "play-iteratees" % "2.6.1",
  "com.typesafe.play" %% "play-mailer" % "6.0.1",
  "com.typesafe.play" %% "play-mailer-guice" % "6.0.1"
)

TwirlKeys.templateImports ++= Seq("views.Helpers",
                                  "scala.collection.JavaConverters._")

LessKeys.compress in Assets := true

includeFilter in (Assets, LessKeys.less) := "*.less"

//pipelineStages := Seq(rjs, uglify, digest, gzip)
pipelineStages := Seq(uglify, digest, gzip)


//javaOptions ++= Seq("--illegal-access=allow")