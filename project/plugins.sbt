// The Typesafe repository
resolvers += Resolver.typesafeRepo("releases")

lazy val root = project.in(file(".")).dependsOn(sbtAutoprefixer)
lazy val sbtAutoprefixer = uri("git://github.com/matthewrennie/sbt-autoprefixer")

// Use the Play sbt plugin for Play projects
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.5.10")

addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-M15")

addSbtPlugin("org.irundaia.sbt" % "sbt-sassify" % "1.4.2")

addSbtPlugin("com.typesafe.sbt" % "sbt-digest" % "1.1.1")