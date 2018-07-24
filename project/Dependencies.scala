import sbt._

object Dependencies {

  lazy val scalatest = "org.scalatest" %% "scalatest" % "3.0.5" % Test

  lazy val `executor-tools` = "com.evolutiongaming" %% "executor-tools" % "1.0.0"

  lazy val `config-tools` = "com.evolutiongaming" %% "config-tools" % "1.0.2"

  lazy val skafka = "com.evolutiongaming" %% "skafka-impl" % "0.3.4"

  lazy val pubsub = "com.evolutiongaming" %% "pubsub" % "2.0.4"

  lazy val `play-json` = "com.typesafe.play" %% "play-json" % "2.6.9"

  lazy val `scala-tools` = "com.evolutiongaming" %% "scala-tools" % "2.1"

  lazy val nel = "com.evolutiongaming" %% "nel" % "1.2"

  lazy val `commons-io` = "org.apache.commons" % "commons-io" % "1.3.2"

  lazy val `future-helper` = "com.evolutiongaming" %% "future-helper" % "1.0.0"

  lazy val serially = "com.evolutiongaming" %% "serially" % "1.0.3"
  

  object Logback {
    private val version = "1.2.3"
    lazy val core = "ch.qos.logback" % "logback-core" % version
    lazy val classic = "ch.qos.logback" % "logback-classic" % version
  }

  object Slf4j {
    private val version = "1.7.25"
    lazy val api = "org.slf4j" % "slf4j-api" % version
    lazy val `log4j-over-slf4j` = "org.slf4j" % "log4j-over-slf4j" % version
  }

  object Cassandra {
    lazy val driver = "com.datastax.cassandra" % "cassandra-driver-core" % "3.5.0"
    lazy val server = "org.apache.cassandra" % "cassandra-all" % "3.11.2" exclude("commons-logging", "commons-logging")
  }

  object Kafka {
    private val version = "1.1.0"
    val server = "org.apache.kafka" %% "kafka" % version
    val clients = "org.apache.kafka" % "kafka-clients" % version
  }

  object Akka {
    private val version = "2.5.13"
    lazy val persistence = "com.typesafe.akka" %% "akka-persistence" % version
    lazy val tck = "com.typesafe.akka" %% "akka-persistence-tck" % version % Test
    lazy val slf4j = "com.typesafe.akka" %% "akka-slf4j" % version % Test
  }
}