package com.evolutiongaming.kafka.journal.replicator

import com.evolutiongaming.config.ConfigHelper._
import com.evolutiongaming.kafka.journal.eventual.cassandra.EventualCassandraConfig
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.skafka.consumer.ConsumerConfig
import com.typesafe.config.Config

import scala.concurrent.duration._

final case class ReplicatorConfig(
  topicPrefixes: Nel[String] = Nel("journal"),
  topicDiscoveryInterval: FiniteDuration = 3.seconds,
  consumer: ConsumerConfig = ConsumerConfig.Default,
  cassandra: EventualCassandraConfig = EventualCassandraConfig.Default)

object ReplicatorConfig {

  val Default: ReplicatorConfig = ReplicatorConfig()


  def apply(config: Config): ReplicatorConfig = {

    def get[T: FromConf](name: String) = config.getOpt[T](name)

    val topicPrefixes = {
      val prefixes = for {
        prefixes <- get[List[String]]("topic-prefixes")
        prefixes <- Nel.opt(prefixes)
      } yield prefixes
      prefixes getOrElse Default.topicPrefixes
    }

    ReplicatorConfig(
      topicPrefixes = topicPrefixes,
      topicDiscoveryInterval = get[FiniteDuration]("topic-discovery-interval") getOrElse Default.topicDiscoveryInterval,
      consumer = get[Config]("kafka.consumer").fold(Default.consumer)(ConsumerConfig.apply),
      cassandra = get[Config]("cassandra").fold(Default.cassandra)(EventualCassandraConfig.apply))
  }
}