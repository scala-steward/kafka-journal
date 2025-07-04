package com.evolution.kafka.journal.replicator

import cats.Monad
import cats.data.NonEmptyList as Nel
import cats.syntax.all.*
import com.evolution.kafka.journal.{ConsRecord, KafkaConsumer}
import com.evolutiongaming.skafka.*
import com.evolutiongaming.skafka.consumer.RebalanceListener1
import com.evolutiongaming.sstream.Stream
import scodec.bits.ByteVector

import scala.concurrent.duration.*

private[journal] trait TopicConsumer[F[_]] {

  def subscribe(listener: RebalanceListener1[F]): F[Unit]

  def poll: Stream[F, Map[Partition, Nel[ConsRecord]]]

  def commit: TopicCommit[F]
}

private[journal] object TopicConsumer {

  def apply[F[_]: Monad](
    topic: Topic,
    pollTimeout: FiniteDuration,
    commit: TopicCommit[F],
    consumer: KafkaConsumer[F, String, ByteVector],
  ): TopicConsumer[F] = {

    val commit1 = commit

    new TopicConsumer[F] {

      def subscribe(listener: RebalanceListener1[F]): F[Unit] = {
        consumer.subscribe(topic, listener)
      }

      val poll: Stream[F, Map[Partition, Nel[ConsRecord]]] = {
        val records = for {
          records <- consumer.poll(pollTimeout)
        } yield for {
          (partition, records) <- records.values
        } yield {
          (partition.partition, records)
        }
        Stream.repeat(records)
      }

      def commit: TopicCommit[F] = commit1
    }
  }
}
