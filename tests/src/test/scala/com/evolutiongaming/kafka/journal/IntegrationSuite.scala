package com.evolutiongaming.kafka.journal

import cats.effect._
import cats.implicits._
import cats.temp.par.Par
import com.evolutiongaming.cassandra.StartCassandra
import com.evolutiongaming.catshelper.{FromFuture, FromTry, Log, LogOf, Runtime, ToFuture, ToTry}
import com.evolutiongaming.kafka.StartKafka
import com.evolutiongaming.kafka.journal.replicator.{Replicator, ReplicatorConfig}
import com.evolutiongaming.kafka.journal.util._
import com.evolutiongaming.kafka.journal.IOSuite._
import com.evolutiongaming.scassandra.CassandraClusterOf
import com.evolutiongaming.smetrics.{CollectorRegistry, MeasureDuration}
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext


object IntegrationSuite {

  def startF[F[_] : Concurrent : Timer : Par : FromFuture : ToFuture : ContextShift : LogOf : Runtime : MeasureDuration : FromTry : ToTry](
    cassandraClusterOf: CassandraClusterOf[F]
  ): Resource[F, Unit] = {

    def cassandra(log: Log[F]) = Resource {
      for {
        cassandra <- Sync[F].delay { StartCassandra() }
      } yield {
        val release = Sync[F].delay { cassandra() }.onError { case e =>
          log.error(s"failed to release cassandra with $e", e)
        }
        (().pure[F], release)
      }
    }

    def kafka(log: Log[F]) = Resource {
      for {
        kafka <- Sync[F].delay { StartKafka() }
      } yield {
        val release = Sync[F].delay { kafka() }.onError { case e =>
          log.error(s"failed to release kafka with $e", e)
        }
        (().pure[F], release)
      }
    }

    def replicator(log: Log[F], blocking: ExecutionContext) = {
      implicit val kafkaConsumerOf = KafkaConsumerOf[F](blocking)
      val config = Sync[F].delay {
        val config0 = ConfigFactory.load("replicator.conf")
        val config1 = config0.getConfig("evolutiongaming.kafka-journal.replicator")
        ReplicatorConfig(config1)
      }

      for {
        metrics  <- Replicator.Metrics.of[F](CollectorRegistry.empty[F], "clientId")
        config   <- Resource.liftF(config)
        hostName <- Resource.liftF(HostName.of[F]())
        result   <- Replicator.of[F](config, cassandraClusterOf, hostName, metrics.some)
        result1   = result.onError { case e => log.error(s"failed to release replicator with $e", e) }
        _        <- ResourceOf(Concurrent[F].start(result1))
      } yield {}
    }

    for {
      log      <- Resource.liftF(LogOf[F].apply(IntegrationSuite.getClass))
      _        <- cassandra(log)
      _        <- kafka(log)
      blocking <- Executors.blocking[F]("kafka-journal-blocking")
      _        <- replicator(log, blocking)
    } yield {}
  }

  def startIO(cassandraClusterOf: CassandraClusterOf[IO]): Resource[IO, Unit] = {
    val logOf = LogOf.slfj4[IO]
    for {
      logOf  <- Resource.liftF(logOf)
      result <- {
        implicit val logOf1 = logOf
        startF[IO](cassandraClusterOf)
      }
    } yield result
  }

  private lazy val started: Unit = {
    val (_, release) = startIO(CassandraSuite.cassandraClusterOf).allocated.unsafeRunSync()

    val _ = sys.addShutdownHook { release.unsafeRunSync() }
  }

  def start(): Unit = started
}
