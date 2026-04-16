name := "DistributedAlgorithms"
version := "0.1.0"
scalaVersion := "3.3.1"

lazy val akkaVersion = "2.8.5"

resolvers += "Akka library repository".at("https://repo.akka.io/_WDATKeuw2KJzDOl0RZrscTCl5Thulje4speeo8XgpEKTRpT/secure")

enablePlugins(Cinnamon)

run / cinnamon := true
test / cinnamon := true

cinnamonLogLevel := "INFO"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor"                  % akkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed"            % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-typed"          % akkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson"  % akkaVersion,
  "ch.qos.logback"    %  "logback-classic"             % "1.4.11",
  "com.typesafe.akka" %% "akka-testkit"                % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-actor-testkit-typed"    % akkaVersion % Test,
  "org.scalatest"     %% "scalatest"                   % "3.2.17"    % Test,
  "com.typesafe"      %  "config"                      % "1.4.2",
  "io.circe"          %% "circe-core"                  % "0.14.6",
  "io.circe"          %% "circe-generic"               % "0.14.6",
  "io.circe"          %% "circe-parser"                % "0.14.6",
  Cinnamon.library.cinnamonAkka,
  Cinnamon.library.cinnamonCHMetrics,
  Cinnamon.library.cinnamonJvmMetricsProducer
)

unmanagedJars in Compile += file(
  "netgamesim/target/scala-3.2.2/netmodelsim.jar"
)

scalacOptions ++= Seq(
  "-encoding", "UTF-8",
  "-deprecation",
  "-feature",
  "-unchecked"
)

ThisBuild / assemblyMergeStrategy := {
  case PathList("META-INF", _*) => MergeStrategy.discard
  case "reference.conf"         => MergeStrategy.concat
  case "module-info.class"      => MergeStrategy.discard
  case _                        => MergeStrategy.first
}