package com.evolution.kafka.journal

import cats.effect.Sync
import cats.syntax.all.*
import com.evolutiongaming.catshelper.{Log, LogOf}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LogSource

object LogOfFromPekko {
  // Scala 3 compiler couldn't construct this one implicitly
  private implicit val logSourceFromClass: LogSource[Class[?]] = LogSource.fromClass

  def apply[F[_]: Sync](system: ActorSystem): LogOf[F] = {

    def log[A: LogSource](source: A) = {
      for {
        log <- Sync[F].delay { org.apache.pekko.event.Logging(system, source) }
      } yield {
        LogFromPekko[F](log)
      }
    }

    new LogOf[F] {

      def apply(source: String): F[Log[F]] = log(source)

      def apply(source: Class[?]): F[Log[F]] = log(source)
    }
  }
}
