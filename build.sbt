import com.typesafe.sbt.packager.archetypes.ServerLoader.Systemd

name := "kith-and-kin"

version := "1.0"

scalaVersion := "2.11.8"

val awsVersion = "1.11.86"

libraryDependencies ++= Seq(
  "org.webjars" %% "webjars-play" % "2.5.0",
  "com.gu" %% "play-googleauth" % "0.6.0",
  "com.gu" %% "scanamo" % "0.9.1",
  "com.amazonaws" % "aws-java-sdk-dynamodb" % awsVersion,
  "com.amazonaws" % "aws-java-sdk-ses" % awsVersion,
  filters,
  ws
)

lazy val root = (project in file(".")).enablePlugins(PlayScala, JDebPackaging).settings(
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

  serverLoading in Debian := Systemd
)
