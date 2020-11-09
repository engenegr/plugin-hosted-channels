name := "hc"

version := "0.1"

scalaVersion := "2.13.3"

libraryDependencies += "fr.acinq.eclair" % "eclair-core_2.13" % "0.4.3-SNAPSHOT" from "file:///home/anton/Desktop/hc/src/main/jar/eclair-core_2.13-0.4.3-SNAPSHOT.jar"
libraryDependencies += "fr.acinq.eclair" % "eclair-node_2.13" % "0.4.3-SNAPSHOT" from "file:///home/anton/Desktop/hc/src/main/jar/eclair-node_2.13-0.4.3-SNAPSHOT.jar"

libraryDependencies += "org.scalatest" % "scalatest_2.13" % "3.1.1"

libraryDependencies += "com.softwaremill.quicklens" % "quicklens_2.13" % "1.5.0" // Same as Eclair

libraryDependencies += "org.scodec" % "scodec-core_2.13" % "1.11.7" // Same as Eclair

libraryDependencies += "org.clapper" % "grizzled-slf4j_2.13" % "1.3.4" // Same as Eclair

libraryDependencies += "com.typesafe.akka" % "akka-actor_2.13" % "2.6.10" // Same as Eclair

libraryDependencies += "com.typesafe.akka" % "akka-testkit_2.13" % "2.6.10" // Same as Eclair

libraryDependencies += "com.iheart" % "ficus_2.13" % "1.5.0"

libraryDependencies += "com.typesafe.slick" %% "slick" % "3.3.2"

libraryDependencies += "com.typesafe.slick" %% "slick-hikaricp" % "3.3.2"

libraryDependencies += "org.postgresql" % "postgresql" % "9.4-1206-jdbc42"

libraryDependencies += "fr.acinq" % "bitcoin-lib_2.13" % "0.18" // Same as Eclair

libraryDependencies += "com.google.guava" % "guava" % "24.0-android" // Same as Eclair

enablePlugins(ReproducibleBuildsPlugin)