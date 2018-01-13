import com.typesafe.sbt.packager.archetypes.ServerLoader.Systemd

name := "kith-and-kin"

version := "1.0"

scalaVersion := "2.11.8"

val awsVersion = "1.11.86"

resolvers += "Plambda Releases" at "https://dl.bintray.com/sihil/plambda"

libraryDependencies ++= Seq(
  // webjars
  "org.webjars" %% "webjars-play" % "2.5.0",
  // scala deps
  "com.gu" %% "play-googleauth" % "0.6.0",
  "com.gu" %% "scanamo" % "0.9.1",
  "com.softwaremill.quicklens" % "quicklens_2.11" % "1.4.8",
  // email and db
  "com.amazonaws" % "aws-java-sdk-dynamodb" % awsVersion,
  "com.amazonaws" % "aws-java-sdk-ses" % awsVersion,
  // parsing CSV files
  "com.nrinaudo" %% "kantan.csv-generic" % "0.1.17",
  "com.nrinaudo" %% "kantan.csv-joda-time" % "0.1.17",
  // akka agents
  "com.typesafe.akka" % "akka-agent_2.11" % "2.4.12",
  // stripe
  "com.stripe" % "stripe-java" % "3.9.0",
  // enum macro
  "ca.mrvisser" %% "sealerate" % "0.0.5",
  // detect IE
  "org.uaparser" %% "uap-scala" % "0.1.0",
  // rewriting html e-mail
  "org.jsoup" % "jsoup" % "1.10.2",
  // play framework
  filters,
  ws,
  cache,
  // plambda adaptor
  "net.sihil" %% "plambda" % "0.0.4-SNAPSHOT",
  "com.amazonaws" % "aws-java-sdk-s3" % awsVersion,
  component("play-test")
)

publishMavenStyle := false

lazy val root = (project in file(".")).enablePlugins(PlayScala, SbtWeb).settings(
  JsEngineKeys.engineType := JsEngineKeys.EngineType.Node,

  pipelineStages := Seq(autoprefixer, digest),

  packageName in Universal := name.value,
  PlayKeys.externalizeResources := false,
  topLevelDirectory in Universal := None,
  sources in (Compile,doc) := Seq.empty,
  publishArtifact in (Compile, packageDoc) := false
)
