// The Typesafe repository
resolvers += Resolver.typesafeRepo("releases")

// Use the Play sbt plugin for Play projects
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.5.10")

addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-M15")

addSbtPlugin("com.arpnetworking" % "sbt-typescript" % "0.2.3")

addSbtPlugin("org.irundaia.sbt" % "sbt-sassify" % "1.4.2")