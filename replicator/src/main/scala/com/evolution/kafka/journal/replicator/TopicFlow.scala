package com.evolution.kafka.journal.replicator

import cats.data.{NonEmptyList as Nel, NonEmptyMap as Nem, NonEmptySet as Nes}
import cats.syntax.all.*
import cats.{Applicative, ~>}
import com.evolution.kafka.journal.ConsRecord
import com.evolutiongaming.skafka.{Offset, Partition}

private[journal] trait TopicFlow[F[_]] {

  def assign(partitions: Nes[Partition]): F[Unit]

  def apply(records: Nem[Partition, Nel[ConsRecord]]): F[Map[Partition, Offset]]

  def revoke(partitions: Nes[Partition]): F[Unit]

  def lose(partitions: Nes[Partition]): F[Unit]
}

private[journal] object TopicFlow {

  def empty[F[_]: Applicative]: TopicFlow[F] = new TopicFlow[F] {

    def assign(partitions: Nes[Partition]): F[Unit] = ().pure[F]

    def apply(records: Nem[Partition, Nel[ConsRecord]]): F[Map[Partition, Offset]] =
      Map.empty[Partition, Offset].pure[F]

    def revoke(partitions: Nes[Partition]): F[Unit] = ().pure[F]

    def lose(partitions: Nes[Partition]): F[Unit] = ().pure[F]
  }

  implicit class TopicFlowOps[F[_]](val self: TopicFlow[F]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): TopicFlow[G] = new TopicFlow[G] {

      def assign(partitions: Nes[Partition]): G[Unit] = f(self.assign(partitions))

      def apply(records: Nem[Partition, Nel[ConsRecord]]): G[Map[Partition, Offset]] = f(self(records))

      def revoke(partitions: Nes[Partition]): G[Unit] = f(self.revoke(partitions))

      def lose(partitions: Nes[Partition]): G[Unit] = f(self.lose(partitions))
    }
  }
}
