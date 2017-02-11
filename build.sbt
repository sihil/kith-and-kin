import com.typesafe.sbt.packager.archetypes.ServerLoader.Systemd

name := "kith-and-kin"

version := "1.0"

scalaVersion := "2.11.8"

val awsVersion = "1.11.86"

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
  // play framework
  filters,
  ws
)

lazy val root = (project in file(".")).enablePlugins(PlayScala, JDebPackaging, SbtWeb).settings(
  JsEngineKeys.engineType := JsEngineKeys.EngineType.Node,

  pipelineStages := Seq(digest),

  packageName in Universal := name.value,
  maintainer := "Simon Hildrew <simon@hildrew.net>",
  packageSummary := "Kith and Kin website",
  packageDescription := """Play app that runs the kith and kin website""",
  debianPackageDependencies := Seq("openjdk-8-jre-headless"),

  javaOptions in Universal ++= Seq(
    "-Dpidfile.path=/dev/null",
    "-J-XX:MaxRAMFraction=2",
    "-J-XX:InitialRAMFraction=2",
    "-J-XX:MaxMetaspaceSize=300m",
    "-J-XX:+PrintGCDetails",
    "-J-XX:+PrintGCDateStamps",
    s"-J-Xloggc:/var/log/${packageName.value}/gc.log"
  ),

  serverLoading in Debian := Systemd,

  sources in (Compile,doc) := Seq.empty,
  publishArtifact in (Compile, packageDoc) := false
)
