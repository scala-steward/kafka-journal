package com.evolution.kafka.journal

import cats.*
import cats.arrow.FunctionK
import cats.data.NonEmptyList as Nel
import cats.syntax.all.*
import com.evolution.kafka.journal.conversions.{KafkaRead, KafkaWrite}
import com.evolution.kafka.journal.eventual.EventualRead
import com.evolution.kafka.journal.util.StreamHelper.*
import com.evolutiongaming.catshelper.{Log, MeasureDuration, MonadThrowable}
import com.evolutiongaming.skafka.{Bytes as _, *}
import com.evolutiongaming.sstream.Stream
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.*

import scala.concurrent.duration.*

trait Journal[F[_]] {

  def append[A](
    events: Nel[Event[A]],
    metadata: RecordMetadata = RecordMetadata.empty,
    headers: Headers = Headers.empty,
  )(implicit
    kafkaWrite: KafkaWrite[F, A],
  ): F[PartitionOffset]

  def read[A](
    from: SeqNr = SeqNr.min,
  )(implicit
    kafkaRead: KafkaRead[F, A],
    eventualRead: EventualRead[F, A],
  ): Stream[F, EventRecord[A]]

  def pointer: F[Option[SeqNr]]

  /**
   * Deletes events up to provided SeqNr Using default value `DeleteTo.max` is not recommended:
   * `Delete` action is valid for starting new journal and the `DeleteTo.max` would produce dead-en
   * journal - there will be no way to add new events.
   */
  // TODO next major release - remove default value
  def delete(to: DeleteTo = DeleteTo.max): F[Option[PartitionOffset]]

  /**
   * Deletes all data (resets storage, removes all events and all traces of this journal) for the
   * key, consecutive pointer call will return `None`. Mostly used by [[PurgeExpired]].
   */
  def purge: F[Option[PartitionOffset]]
}

object Journal {

  def empty[F[_]: Applicative]: Journal[F] = {
    class Empty
    new Empty with Journal[F] {

      def append[A](
        events: Nel[Event[A]],
        metadata: RecordMetadata,
        headers: Headers,
      )(implicit
        kafkaWrite: KafkaWrite[F, A],
      ): F[PartitionOffset] =
        PartitionOffset.empty.pure[F]

      def read[A](
        from: SeqNr,
      )(implicit
        kafkaRead: KafkaRead[F, A],
        eventualRead: EventualRead[F, A],
      ): Stream[F, EventRecord[A]] = Stream.empty

      def pointer: F[Option[SeqNr]] = none[SeqNr].pure[F]

      def delete(to: DeleteTo): F[Option[PartitionOffset]] = none[PartitionOffset].pure[F]

      def purge: F[Option[PartitionOffset]] = none[PartitionOffset].pure[F]
    }
  }

  private sealed abstract class WithLog

  private sealed abstract class WithLogError

  private sealed abstract class MapK

  private sealed abstract class WithMetrics

  implicit class JournalOps[F[_]](val self: Journal[F]) extends AnyVal {

    def withLog(
      key: Key,
      log: Log[F],
      config: CallTimeThresholds = CallTimeThresholds.default,
    )(implicit
      F: FlatMap[F],
      measureDuration: MeasureDuration[F],
    ): Journal[F] = {

      val functionKId = FunctionK.id[F]

      def logDebugOrWarn(latency: FiniteDuration, threshold: FiniteDuration)(msg: => String) = {
        if (latency >= threshold) log.warn(msg) else log.debug(msg)
      }

      new WithLog with Journal[F] {

        def append[A](
          events: Nel[Event[A]],
          metadata: RecordMetadata,
          headers: Headers,
        )(implicit
          kafkaWrite: KafkaWrite[F, A],
        ): F[PartitionOffset] = {
          for {
            d <- MeasureDuration[F].start
            r <- self.append(events, metadata, headers)
            d <- d
            _ <- logDebugOrWarn(d, config.append) {
              val first = events.head.seqNr
              val last = events.last.seqNr
              val expireAfterStr = metadata.payload.expireAfter.foldMap { expireAfter =>
                s", expireAfter: $expireAfter"
              }
              val seqNr = if (first === last) s"seqNr: $first" else s"seqNrs: $first..$last"
              s"$key append in ${ d.toMillis }ms, $seqNr$expireAfterStr, result: $r"
            }
          } yield r
        }

        def read[A](
          from: SeqNr,
        )(implicit
          kafkaRead: KafkaRead[F, A],
          eventualRead: EventualRead[F, A],
        ): Stream[F, EventRecord[A]] = {
          val logging: F ~> F = new (F ~> F) {
            def apply[B](fa: F[B]): F[B] = {
              for {
                d <- MeasureDuration[F].start
                r <- fa
                d <- d
                _ <- logDebugOrWarn(d, config.read) { s"$key read in ${ d.toMillis }ms, from: $from, result: $r" }
              } yield r
            }
          }
          self.read[A](from).mapK(logging, functionKId)
        }

        def pointer: F[Option[SeqNr]] = {
          for {
            d <- MeasureDuration[F].start
            r <- self.pointer
            d <- d
            _ <- logDebugOrWarn(d, config.pointer) { s"$key pointer in ${ d.toMillis }ms, result: $r" }
          } yield r
        }

        def delete(to: DeleteTo): F[Option[PartitionOffset]] = {
          for {
            d <- MeasureDuration[F].start
            r <- self.delete(to)
            d <- d
            _ <- logDebugOrWarn(d, config.delete) { s"$key delete in ${ d.toMillis }ms, to: $to, result: $r" }
          } yield r
        }

        def purge: F[Option[PartitionOffset]] = {
          for {
            d <- MeasureDuration[F].start
            r <- self.purge
            d <- d
            _ <- logDebugOrWarn(d, config.purge) { s"$key purge in ${ d.toMillis }ms, result: $r" }
          } yield r
        }
      }
    }

    def withLogError(
      key: Key,
      log: Log[F],
    )(implicit
      F: MonadThrowable[F],
      measureDuration: MeasureDuration[F],
    ): Journal[F] = {

      val functionKId = FunctionK.id[F]

      def logError[A](fa: F[A])(f: (Throwable, FiniteDuration) => String) = {
        for {
          d <- MeasureDuration[F].start
          r <- fa.handleErrorWith { error =>
            for {
              d <- d
              _ <- log.error(f(error, d), error)
              r <- error.raiseError[F, A]
            } yield r
          }
        } yield r
      }

      new WithLogError with Journal[F] {

        def append[A](
          events: Nel[Event[A]],
          metadata: RecordMetadata,
          headers: Headers,
        )(implicit
          kafkaWrite: KafkaWrite[F, A],
        ): F[PartitionOffset] = {
          logError {
            self.append(events, metadata, headers)
          } { (error, latency) =>
            s"$key append failed in ${ latency.toMillis }ms, events: $events, error: $error"
          }
        }

        def read[A](
          from: SeqNr,
        )(implicit
          kafkaRead: KafkaRead[F, A],
          eventualRead: EventualRead[F, A],
        ): Stream[F, EventRecord[A]] = {
          val logging: F ~> F = new (F ~> F) {
            def apply[B](fa: F[B]): F[B] = {
              logError(fa) { (error, latency) =>
                s"$key read failed in ${ latency.toMillis }ms, from: $from, error: $error"
              }
            }
          }
          self.read[A](from).mapK(logging, functionKId)
        }

        def pointer: F[Option[SeqNr]] = {
          logError {
            self.pointer
          } { (error, latency) =>
            s"$key pointer failed in ${ latency.toMillis }ms, error: $error"
          }
        }

        def delete(to: DeleteTo): F[Option[PartitionOffset]] = {
          logError {
            self.delete(to)
          } { (error, latency) =>
            s"$key delete failed in ${ latency.toMillis }ms, to: $to, error: $error"
          }
        }

        def purge: F[Option[PartitionOffset]] = {
          logError {
            self.purge
          } { (error, latency) =>
            s"$key purge failed in ${ latency.toMillis }ms, error: $error"
          }
        }
      }
    }

    def withMetrics(
      topic: Topic,
      metrics: JournalMetrics[F],
    )(implicit
      F: MonadThrowable[F],
      measureDuration: MeasureDuration[F],
    ): Journal[F] = {
      val functionKId = FunctionK.id[F]

      def handleError[A](name: String, topic: Topic)(fa: F[A]): F[A] = {
        fa.handleErrorWith { e =>
          for {
            _ <- metrics.failure(topic = topic, name = name)
            a <- e.raiseError[F, A]
          } yield a
        }
      }

      new WithMetrics with Journal[F] {

        def append[A](
          events: Nel[Event[A]],
          metadata: RecordMetadata,
          headers: Headers,
        )(implicit
          kafkaWrite: KafkaWrite[F, A],
        ): F[PartitionOffset] = {
          def append = self.append(events, metadata, headers)
          for {
            d <- MeasureDuration[F].start
            r <- handleError("append", topic) { append }
            d <- d
            _ <- metrics.append(topic = topic, latency = d, events = events.size)
          } yield r
        }

        def read[A](
          from: SeqNr,
        )(implicit
          kafkaRead: KafkaRead[F, A],
          eventualRead: EventualRead[F, A],
        ): Stream[F, EventRecord[A]] = {
          val measure: F ~> F = new (F ~> F) {
            def apply[B](fa: F[B]): F[B] = {
              for {
                d <- MeasureDuration[F].start
                r <- handleError("read", topic) { fa }
                d <- d
                _ <- metrics.read(topic, latency = d)
              } yield r
            }
          }

          for {
            a <- self.read[A](from).mapK(measure, functionKId)
            _ <- metrics.read(topic).toStream
          } yield a
        }

        def pointer: F[Option[SeqNr]] = {
          for {
            d <- MeasureDuration[F].start
            r <- handleError("pointer", topic) { self.pointer }
            d <- d
            _ <- metrics.pointer(topic, d)
          } yield r
        }

        def delete(to: DeleteTo): F[Option[PartitionOffset]] = {
          for {
            d <- MeasureDuration[F].start
            r <- handleError("delete", topic) { self.delete(to) }
            d <- d
            _ <- metrics.delete(topic, d)
          } yield r
        }

        def purge: F[Option[PartitionOffset]] = {
          for {
            d <- MeasureDuration[F].start
            r <- handleError("purge", topic) { self.purge }
            d <- d
            _ <- metrics.purge(topic, d)
          } yield r
        }
      }
    }

    def mapK[G[_]](fg: F ~> G, gf: G ~> F): Journal[G] = {
      new MapK with Journal[G] {

        def append[A](
          events: Nel[Event[A]],
          metadata: RecordMetadata,
          headers: Headers,
        )(implicit
          kafkaWrite: KafkaWrite[G, A],
        ): G[PartitionOffset] = {
          fg(self.append(events, metadata, headers)(kafkaWrite.mapK(gf)))
        }

        def read[A](
          from: SeqNr,
        )(implicit
          kafkaRead: KafkaRead[G, A],
          eventualRead: EventualRead[G, A],
        ): Stream[G, EventRecord[A]] =
          self.read[A](from)(kafkaRead.mapK(gf), eventualRead.mapK(gf)).mapK(fg, gf)

        def pointer: G[Option[SeqNr]] = fg(self.pointer)

        def delete(to: DeleteTo): G[Option[PartitionOffset]] = fg(self.delete(to))

        def purge: G[Option[PartitionOffset]] = fg(self.purge)
      }
    }
  }

  final case class CallTimeThresholds(
    append: FiniteDuration = 500.millis,
    read: FiniteDuration = 5.seconds,
    pointer: FiniteDuration = 1.second,
    delete: FiniteDuration = 1.second,
    purge: FiniteDuration = 1.second,
  )

  object CallTimeThresholds {

    val default: CallTimeThresholds = CallTimeThresholds()

    implicit val configReaderCallTimeThresholds: ConfigReader[CallTimeThresholds] = deriveReader[CallTimeThresholds]
  }

  /**
   * Configuration for the dynamic pool of Kafka consumers used on recovery in case the data is not
   * yet replicated to Cassandra
   *
   * @param multiplier
   *   defines max pool size = multiplier x number of cores, if a calculated max pool size is below
   *   1, the size of 1 is used to avoid starting with an unusable consumer pool configuration
   * @param idleTimeout
   *   if idle for this time, Kafka consumers are closed
   */
  final case class ConsumerPoolConfig(
    multiplier: Double,
    idleTimeout: FiniteDuration,
  )

  object ConsumerPoolConfig {

    val Default: ConsumerPoolConfig = ConsumerPoolConfig(multiplier = 10, idleTimeout = 1.minute)

    implicit val configReaderConsumerPoolConfig: ConfigReader[ConsumerPoolConfig] = deriveReader
  }

  final case class DataIntegrityConfig(
    /**
     * On recovery, if true, duplicated [[SeqNr]] in events will cause [[JournalError]]
     * `Data integrity violated`
     */
    seqNrUniqueness: Boolean,

    /**
     * On recovery, if true, events with [[RecordId]] different from the one in the current metadata
     * record will be filtered out and logged as an error.
     */
    correlateEventsWithMeta: Boolean,
  )

  object DataIntegrityConfig {

    val Default: DataIntegrityConfig = DataIntegrityConfig(
      seqNrUniqueness = true,
      correlateEventsWithMeta = false,
    )

    implicit val configReaderDataIntegrityConfig: ConfigReader[DataIntegrityConfig] = deriveReader
  }
}
