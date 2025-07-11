package com.evolution.kafka.journal.replicator

import cats.data.{NonEmptyMap as Nem, NonEmptySet as Nes}
import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO, Ref, Resource, Sync}
import cats.syntax.all.*
import com.evolution.kafka.journal.IOSuite.*
import com.evolution.kafka.journal.{KafkaConsumer, KafkaConsumerOf}
import com.evolutiongaming.catshelper.{LogOf, ToTry}
import com.evolutiongaming.skafka
import com.evolutiongaming.skafka.consumer.{ConsumerConfig, ConsumerRecords, RebalanceListener1}
import com.evolutiongaming.skafka.{Offset, OffsetAndMetadata, Partition, Topic, TopicPartition}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.FiniteDuration

class DistributeJobTest extends AsyncFunSuite with Matchers {
  import DistributeJobTest.*

  test("DistributeJob") {
    val topic = "topic"

    def topicPartitionOf(partition: Partition) = TopicPartition(topic, partition)

    implicit val logOf: LogOf[IO] = LogOf.empty[IO]
    implicit val toTry: ToTry[IO] = ToTry.ioToTry
    val consumerConfig = ConsumerConfig()
    val result = for {
      actions <- Actions.of[IO]
      partition0 <- Partition.of[IO](0)
      partition1 <- Partition.of[IO](1)
      partition2 <- Partition.of[IO](2)
      deferred <- Deferred[IO, RebalanceListener1[IO]]
      kafkaConsumerOf = new KafkaConsumerOf[IO] {
        def apply[K, V](
          config: ConsumerConfig,
        )(implicit
          fromBytesK: skafka.FromBytes[IO, K],
          fromBytesV: skafka.FromBytes[IO, V],
        ): Resource[IO, KafkaConsumer[IO, K, V]] = {
          val consumer: KafkaConsumer[IO, K, V] = new KafkaConsumer[IO, K, V] {
            def assign(partitions: Nes[TopicPartition]): IO[Unit] = ().pure[IO]
            def seek(partition: TopicPartition, offset: Offset): IO[Unit] = ().pure[IO]
            def subscribe(topic: Topic, listener: RebalanceListener1[IO]): IO[Unit] = {
              deferred.complete(listener).void
            }
            def poll(timeout: FiniteDuration): IO[ConsumerRecords[K, V]] = {
              IO
                .sleep(timeout)
                .as(ConsumerRecords.empty[K, V])
            }
            def commit(offsets: Nem[TopicPartition, OffsetAndMetadata]): IO[Unit] = ().pure[IO]
            def commitLater(offsets: Nem[TopicPartition, OffsetAndMetadata]): IO[Unit] = ().pure[IO]
            def topics: IO[Set[Topic]] = Set.empty[Topic].pure[IO]
            def partitions(topic: Topic): IO[Set[Partition]] = {
              Set(partition0, partition1, partition2).pure[IO]
            }
            def assignment: IO[Set[TopicPartition]] = Set.empty[TopicPartition].pure[IO]
          }
          consumer.pure[Resource[IO, *]]
        }
      }
      result <- {
        val result = for {
          distributeJobs <- DistributeJob[IO](groupId = "groupId", topic = topic, consumerConfig, kafkaConsumerOf)
          jobOf = (name: String, partition: Partition) => {
            distributeJobs(name) { partitions =>
              if (partitions.getOrElse(partition, false)) {
                Resource.make {
                  actions.add(Action.Allocate(name))
                } { _ =>
                  actions.add(Action.Release(name))
                }.some
              } else {
                none
              }
            }
          }
          _ <- jobOf("a", partition0)
        } yield {
          for {
            listener <- deferred.get
            a <- actions.get
            _ <- IO { a shouldEqual List.empty }

            _ <-
              listener.onPartitionsAssigned(Nes.one(topicPartitionOf(partition1))).run(EmptyRebalanceConsumer).liftTo[IO]
            a <- actions.get
            _ <- IO { a shouldEqual List.empty }

            _ <-
              listener.onPartitionsAssigned(Nes.one(topicPartitionOf(partition0))).run(EmptyRebalanceConsumer).liftTo[IO]
            a <- actions.get
            _ <- IO { a shouldEqual List(Action.Allocate("a")) }

            _ <- jobOf("b", partition1).use { _ =>
              for {
                a <- actions.get
                _ <- IO {
                  a shouldEqual List(Action.Allocate("a"), Action.Allocate("b"))
                }
              } yield {}
            }

            a <- actions.get
            _ <- IO {
              a shouldEqual List(Action.Allocate("a"), Action.Allocate("b"), Action.Release("b"))
            }
            _ <- listener
              .onPartitionsRevoked(Nes.of(topicPartitionOf(partition0), topicPartitionOf(partition1)))
              .run(EmptyRebalanceConsumer)
              .liftTo[IO]
            a <- actions.get
            _ <- IO {
              a shouldEqual List(Action.Allocate("a"), Action.Allocate("b"), Action.Release("b"), Action.Release("a"))
            }
            _ <- jobOf("c", partition2).allocated
            _ <- jobOf("d", Partition.max).allocated

            _ <-
              listener.onPartitionsAssigned(Nes.one(topicPartitionOf(partition2))).run(EmptyRebalanceConsumer).liftTo[IO]
            a <- actions.get
            _ <- IO {
              a shouldEqual List(
                Action.Allocate("a"),
                Action.Allocate("b"),
                Action.Release("b"),
                Action.Release("a"),
                Action.Allocate("c"),
              )
            }
          } yield {}
        }
        result.use { identity }
      }
      a <- actions.get
      _ <- IO {
        a shouldEqual List(
          Action.Allocate("a"),
          Action.Allocate("b"),
          Action.Release("b"),
          Action.Release("a"),
          Action.Allocate("c"),
          Action.Release("c"),
        )
      }
    } yield result
    result.run()
  }
}

object DistributeJobTest {

  sealed trait Action

  object Action {
    final case class Allocate(job: String) extends Action
    final case class Release(job: String) extends Action
  }

  trait Actions[F[_]] {
    def add(action: Action): F[Unit]
    def get: F[List[Action]]
  }

  object Actions {
    def of[F[_]: Sync]: F[Actions[F]] = {
      Ref[F]
        .of(List.empty[Action])
        .map { ref =>
          new Actions[F] {

            def add(action: Action): F[Unit] = ref.update { action :: _ }

            def get: F[List[Action]] = ref.get.map { _.reverse }
          }
        }
    }
  }
}
