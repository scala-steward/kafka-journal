package com.evolution.kafka.journal.replicator

import cats.data.NonEmptyList as Nel
import cats.effect.*
import cats.syntax.all.*
import com.evolution.kafka.journal.*
import com.evolution.kafka.journal.conversions.{ConsRecordToActionRecord, KafkaRead}
import com.evolution.kafka.journal.eventual.*
import com.evolution.kafka.journal.util.TemporalHelper.*
import com.evolutiongaming.catshelper.ClockHelper.*
import com.evolutiongaming.catshelper.{BracketThrowable, Log}
import com.evolutiongaming.skafka.Offset

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/**
 * Gets a list of per-key records from [[TopicReplicator]], groups them in [[Batch]]es and
 * replicates each batch to Cassandra using [[ReplicatedKeyJournal]].
 */
private[journal] trait ReplicateRecords[F[_]] {

  def apply(records: Nel[ConsRecord], timestamp: Instant): F[Int]
}

private[journal] object ReplicateRecords {

  def apply[F[_]: BracketThrowable: Clock, A](
    consRecordToActionRecord: ConsRecordToActionRecord[F],
    journal: ReplicatedKeyJournal[F],
    metrics: TopicReplicatorMetrics[F],
    kafkaRead: KafkaRead[F, A],
    eventualWrite: EventualWrite[F, A],
    log: Log[F],
  ): ReplicateRecords[F] = { (records: Nel[ConsRecord], timestamp: Instant) =>
    {

      def apply(records: Nel[ActionRecord[Action]]): F[Int] = {
        val record = records.last
        val key = record.action.key
        val partition = record.partitionOffset.partition
        val id = key.id

        def measurements(records: Int): F[TopicReplicatorMetrics.Measurements] = {
          for {
            now <- Clock[F].instant
          } yield {
            val timestamp1 = record.action.timestamp
            TopicReplicatorMetrics.Measurements(
              replicationLatency = now diff timestamp1,
              deliveryLatency = timestamp diff timestamp1,
              records = records,
            )
          }
        }

        def append(offset: Offset, records: Nel[ActionRecord[Action.Append]]): F[Int] = {
          val bytes = records.foldLeft(0L) { case (bytes, record) => bytes + record.action.payload.size }

          def msg(
            events: Nel[EventRecord[EventualPayloadAndType]],
            latency: FiniteDuration,
            expireAfter: Option[ExpireAfter],
          ): String = {
            val seqNrs =
              if (events.tail.isEmpty) s"seqNr: ${ events.head.seqNr }"
              else s"seqNrs: ${ events.head.seqNr }..${ events.last.seqNr }"
            val origin = records.head.action.origin
            val originStr = origin.foldMap { origin => s", origin: $origin" }
            val version = records.last.action.version
            val versionStr = version.fold("none") { _.toString }
            val expireAfterStr = expireAfter.foldMap { expireAfter => s", expireAfter: $expireAfter" }
            s"append in ${ latency.toMillis }ms, id: $id, partition: $partition, offset: $offset, $seqNrs$originStr, version: $versionStr$expireAfterStr"
          }

          def measure(events: Nel[EventRecord[EventualPayloadAndType]], expireAfter: Option[ExpireAfter]): F[Unit] = {
            for {
              measurements <- measurements(records.size)
              version = events.last.version.map(_.value).getOrElse("none")
              expiration = expireAfter.map(_.value.toString).getOrElse("none")
              result <- metrics.append(
                events = events.length,
                bytes = bytes,
                clientVersion = version,
                expiration = expiration,
                measurements = measurements,
              )
              _ <- log.trace(msg(events, measurements.replicationLatency, expireAfter))
            } yield result
          }

          for {
            events <- records.flatTraverse { record =>
              val action = record.action
              val payloadAndType = action.toPayloadAndType
              for {
                events <- kafkaRead(payloadAndType).adaptError {
                  case e =>
                    JournalError(s"ReplicateRecords failed for id: $id, partition: $partition, offset: $offset: $e", e)
                }
                eventualEvents <- events.events.traverse { _.traverse { a => eventualWrite(a) } }
              } yield for {
                event <- eventualEvents
              } yield {
                EventRecord(record, event, events.metadata)
              }
            }
            expireAfter = events.last.metadata.payload.expireAfter
            result <- journal.append(offset, timestamp, expireAfter, events)
            result <- if (result) measure(events, expireAfter).as(events.size) else 0.pure[F]
          } yield result
        }

        def delete(
          offset: Offset,
          deleteTo: DeleteTo,
          origin: Option[Origin],
          version: Option[Version],
        ): F[Int] = {

          def msg(latency: FiniteDuration): String = {
            val originStr = origin.foldMap { origin => s", origin: $origin" }
            val versionStr = version.fold("none") { _.toString }
            s"delete in ${ latency.toMillis }ms, id: $id, offset: $partition:$offset, deleteTo: $deleteTo$originStr, version: $versionStr"
          }

          def measure(): F[Unit] = {
            for {
              measurements <- measurements(1)
              latency = measurements.replicationLatency
              _ <- metrics.delete(measurements)
              result <- log.trace(msg(latency))
            } yield result
          }

          for {
            result <- journal.delete(offset, timestamp, deleteTo, origin)
            result <- if (result) measure().as(1) else 0.pure[F]
          } yield result
        }

        def purge(offset: Offset, origin: Option[Origin], version: Option[Version]): F[Int] = {

          def msg(latency: FiniteDuration): String = {
            val originStr = origin.foldMap { origin => s", origin: $origin" }
            val versionStr = version.fold("none") { _.toString }
            s"purge in ${ latency.toMillis }ms, id: $id, offset: $partition:$offset$originStr, version: $versionStr"
          }

          def measure(): F[Unit] = {
            for {
              measurements <- measurements(1)
              latency = measurements.replicationLatency
              _ <- metrics.purge(measurements)
              result <- log.trace(msg(latency))
            } yield result
          }

          for {
            result <- journal.purge(offset, timestamp)
            result <- if (result) measure().as(1) else 0.pure[F]
          } yield result
        }

        Batch
          .of(records)
          .foldMapM {
            case batch: Batch.Appends => append(batch.offset, batch.records)
            case batch: Batch.Delete => delete(batch.offset, batch.to, batch.origin, batch.version)
            case batch: Batch.Purge => purge(batch.offset, batch.origin, batch.version)
          }
      }

      for {
        records <- records
          .toList
          .traverseFilter { record => consRecordToActionRecord(record) }
        result <- records
          .toNel
          .foldMapM { records => apply(records) }
      } yield result
    }
  }
}
