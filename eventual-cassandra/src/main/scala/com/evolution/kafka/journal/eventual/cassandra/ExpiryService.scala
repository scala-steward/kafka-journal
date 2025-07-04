package com.evolution.kafka.journal.eventual.cassandra

import cats.effect.Sync
import cats.syntax.all.*
import cats.{Monad, ~>}
import com.evolution.kafka.journal.ExpireAfter
import com.evolution.kafka.journal.util.TemporalHelper.*
import com.evolutiongaming.catshelper.{BracketThrowable, Log, LogOf, MeasureDuration}

import java.time.{Instant, LocalDate, ZoneId}
import java.util.TimeZone
import scala.concurrent.duration.FiniteDuration

private[journal] trait ExpiryService[F[_]] {
  import ExpiryService.*

  def expireOn(expireAfter: ExpireAfter, timestamp: Instant): F[ExpireOn]

  def action(expiry: Option[Expiry], expireAfter: Option[ExpireAfter], timestamp: Instant): F[Action]
}

private[journal] object ExpiryService {

  def const[F[_]](expireOn: F[ExpireOn], action: F[Action]): ExpiryService[F] = {

    val expireOn1 = expireOn
    val action1 = action

    new ExpiryService[F] {

      def expireOn(expireAfter: ExpireAfter, timestamp: Instant): F[ExpireOn] = expireOn1

      def action(expiry: Option[Expiry], expireAfter: Option[ExpireAfter], timestamp: Instant): F[Action] = action1
    }
  }

  def of[F[_]: Sync: LogOf: MeasureDuration]: F[ExpiryService[F]] = {
    for {
      zoneId <- Sync[F].delay { TimeZone.getDefault.toZoneId }
      log <- LogOf[F].apply(ExpiryService.getClass)
      _ <- log.debug(s"zoneId: $zoneId")
    } yield {
      apply[F](zoneId).withLog(log)
    }
  }

  def apply[F[_]: BracketThrowable](
    zoneId: ZoneId,
  ): ExpiryService[F] = {

    new ExpiryService[F] {

      def expireOn(expireAfter: ExpireAfter, timestamp: Instant): F[ExpireOn] = {
        val expireOn = timestamp + expireAfter.value
        BracketThrowable[F]
          .catchNonFatal { LocalDate.ofInstant(expireOn, zoneId) }
          .map { a => ExpireOn(a) }
      }

      def action(expiry: Option[Expiry], expireAfter: Option[ExpireAfter], timestamp: Instant): F[Action] = {

        def apply(expiry0: Option[Expiry], expireAfter: ExpireAfter) = {
          expireOn(expireAfter, timestamp).map { expireOn =>
            val expiry = Expiry(expireAfter, expireOn)
            if (expiry0 contains_ expiry) Action.ignore
            else Action.update(expiry)
          }
        }

        (expiry, expireAfter) match {
          case (None, None) => Action.ignore.pure[F]
          case (expiry, Some(expireAfter)) => apply(expiry, expireAfter)
          case (Some(_), None) => Action.remove.pure[F]
        }
      }
    }
  }

  sealed abstract class Action

  object Action {

    def update(expiry: Expiry): Action = Update(expiry)

    def remove: Action = Remove

    def ignore: Action = Ignore

    final case class Update(expiry: Expiry) extends Action

    case object Remove extends Action

    case object Ignore extends Action
  }

  implicit class ExpiryServiceOps[F[_]](val self: ExpiryService[F]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): ExpiryService[G] = new ExpiryService[G] {

      def expireOn(expireAfter: ExpireAfter, timestamp: Instant): G[ExpireOn] = {
        f(self.expireOn(expireAfter, timestamp))
      }

      def action(expiry: Option[Expiry], expireAfter: Option[ExpireAfter], timestamp: Instant): G[Action] = {
        f(self.action(expiry, expireAfter, timestamp))
      }
    }

    def withLog(
      log: Log[F],
    )(implicit
      F: Monad[F],
      measureDuration: MeasureDuration[F],
    ): ExpiryService[F] = new ExpiryService[F] {

      def expireOn(expireAfter: ExpireAfter, timestamp: Instant): F[ExpireOn] = {
        for {
          d <- MeasureDuration[F].start
          r <- self.expireOn(expireAfter, timestamp)
          d <- d
          _ <- log.debug(s"expireOn in ${ d.toMillis }ms, expireAfter: $expireAfter, timestamp: $timestamp, result: $r")
        } yield r
      }

      def action(expiry: Option[Expiry], expireAfter: Option[ExpireAfter], timestamp: Instant): F[Action] = {

        def logDebug(duration: FiniteDuration, action: Action): F[Unit] = {
          (expiry, expireAfter) match {
            case (None, None) => ().pure[F]
            case _ =>
              log.debug {
                val expiryStr = expiry.foldMap { expiry => s", expiry: $expiry" }
                val expireAfterStr = expireAfter.foldMap { expiry => s", expireAfter: $expiry" }
                s"action in ${ duration.toMillis }ms$expiryStr$expireAfterStr, timestamp: $timestamp, result: $action"
              }
          }
        }

        for {
          d <- MeasureDuration[F].start
          r <- self.action(expiry, expireAfter, timestamp)
          d <- d
          _ <- logDebug(d, r)
        } yield r
      }
    }
  }
}
