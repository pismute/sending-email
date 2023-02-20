ThisBuild / organization := "com.example"
ThisBuild / scalaVersion := "2.13.10"

val kafkaVersion = "3.4.0"

lazy val root = (project in file(".")).settings(
  name := "sending-email",
  tpolecatDevModeOptions ~= { opts =>
    opts.filterNot(Set(ScalacOptions.privateWarnUnusedImports))
  },
  resolvers += "confluent" at "https://packages.confluent.io/maven/",
  libraryDependencies ++= Seq(
    compilerPlugin(("org.typelevel" %% "kind-projector" % "0.13.2").cross(CrossVersion.full)),
    // better monadic for compiler plugin as suggested by documentation
    compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
    "org.apache.kafka" %% "kafka-streams-scala" % kafkaVersion,
    "org.apache.kafka" % "kafka-streams-test-utils" % kafkaVersion,
    "io.confluent" % "kafka-streams-avro-serde" % "7.3.1",
    "org.apache.avro" % "avro-ipc" % "1.11.1",

    // "core" module - IO, IOApp, schedulers
    // This pulls in the kernel and std modules automatically.
    "org.typelevel" %% "cats-effect" % "3.4.6",
    "org.typelevel" %% "cats-mtl" % "1.3.0",
    "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test
  ),
  Compile / sourceGenerators += (Compile / avroScalaGenerateSpecific ).taskValue,
  Test / sourceGenerators += (Test / avroScalaGenerateSpecific).taskValue,
  watchSources ++= ((Compile / avroSourceDirectories).value ** "*.avdl").get,
  Compile / avroScalaSpecificCustomTypes := {
    avrohugger.format.SpecificRecord.defaultTypes.copy(
      uuid = avrohugger.types.JavaUuid
    )
  }
)
