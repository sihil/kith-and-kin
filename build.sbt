import com.typesafe.sbt.packager.archetypes.ServerLoader.Systemd

name := "kith-and-kin"

version := "1.0"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "org.webjars" %% "webjars-play" % "2.5.0",
  filters
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
