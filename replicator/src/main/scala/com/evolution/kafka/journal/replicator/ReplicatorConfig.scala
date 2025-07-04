package com.evolution.kafka.journal.replicator

import cats.data.NonEmptyList as Nel
import cats.syntax.all.*
import com.datastax.driver.core.ConsistencyLevel
import com.evolution.kafka.journal.eventual.cassandra.EventualCassandraConfig
import com.evolution.kafka.journal.util.PureConfigHelper.*
import com.evolution.kafka.journal.{FromConfigReaderResult, KafkaConfig}
import com.evolutiongaming.scassandra.{CassandraConfig, QueryConfig}
import com.typesafe.config.Config
import pureconfig.{ConfigCursor, ConfigReader, ConfigSource}

import scala.concurrent.duration.*

final case class ReplicatorConfig(
  topicPrefixes: Nel[String] = Nel.of("journal"),
  topicDiscoveryInterval: FiniteDuration = 3.seconds,
  cacheExpireAfter: FiniteDuration = 1.minute,
  kafka: KafkaConfig = ReplicatorConfig.defaultKafkaConfig,
  cassandra: EventualCassandraConfig = EventualCassandraConfig(
    client = CassandraConfig(
      name = "replicator",
      query = QueryConfig(consistency = ConsistencyLevel.LOCAL_QUORUM, defaultIdempotence = true),
    ),
  ),
  pollTimeout: FiniteDuration = 10.millis,
)

object ReplicatorConfig {

  private val defaultKafkaConfig: KafkaConfig = {
    val config = KafkaConfig("replicator")
    val consumerConfig = config.consumer
    config.copy(
      consumer = consumerConfig.copy(
        partitionAssignmentStrategy = "org.apache.kafka.clients.consumer.CooperativeStickyAssignor",
      ),
    )
  }

  val default: ReplicatorConfig = ReplicatorConfig()

  private implicit val configReaderKafkaConfig: ConfigReader[KafkaConfig] = KafkaConfig.configReader(default.kafka)

  implicit val configReaderReplicatorConfig: ConfigReader[ReplicatorConfig] = { (cursor: ConfigCursor) =>
    {
      cursor
        .asObjectCursor
        .map { cursor => fromConfig(cursor.objValue.toConfig, default) }
    }
  }

  def fromConfig[F[_]: FromConfigReaderResult](config: Config): F[ReplicatorConfig] = {
    ConfigSource
      .fromConfig(config)
      .at("evolutiongaming.kafka-journal.replicator")
      .load[ReplicatorConfig]
      .liftTo[F]
  }

  private def fromConfig(config: Config, default: => ReplicatorConfig): ReplicatorConfig = {

    val source = ConfigSource.fromConfig(config)

    def get[A: ConfigReader](name: String) = source.at(name).load[A]

    val topicPrefixes = {
      val prefixes = for {
        prefixes <- get[List[String]]("topic-prefixes").toOption
        prefixes <- prefixes.toNel
      } yield prefixes
      prefixes getOrElse default.topicPrefixes
    }

    ReplicatorConfig(
      topicPrefixes = topicPrefixes,
      topicDiscoveryInterval = get[FiniteDuration]("topic-discovery-interval") getOrElse default.topicDiscoveryInterval,
      kafka = get[KafkaConfig]("kafka") getOrElse default.kafka,
      cassandra = get[EventualCassandraConfig]("cassandra") getOrElse default.cassandra,
      pollTimeout = get[FiniteDuration]("kafka.consumer.poll-timeout") getOrElse default.pollTimeout,
    )
  }
}
