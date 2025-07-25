package org.apache.pekko.persistence.kafka.journal

import cats.effect.Sync
import cats.syntax.all.*
import cats.~>
import com.evolution.serialization.{SerializedMsg, SerializedMsgConverter, SerializedMsgExt}
import org.apache.pekko.actor.ActorSystem

/**
 * Provides ability to convert an object to [[SerializedMsg]] and back.
 *
 * It is a typesafe wrapper over [[SerializedMsgConverter]]
 */
trait SerializedMsgSerializer[F[_]] {

  def toMsg(a: AnyRef): F[SerializedMsg]

  def fromMsg(a: SerializedMsg): F[AnyRef]
}

object SerializedMsgSerializer {

  /**
   * Create [[SerializedMsgSerializer]] from an actor system using [[SerializedMsgExt]]
   */
  def of[F[_]: Sync](actorSystem: ActorSystem): F[SerializedMsgSerializer[F]] = {
    for {
      converter <- Sync[F].delay { SerializedMsgExt(actorSystem) }
    } yield {
      apply(converter)
    }
  }

  def apply[F[_]: Sync](converter: SerializedMsgConverter): SerializedMsgSerializer[F] = {

    new SerializedMsgSerializer[F] {

      def toMsg(a: AnyRef): F[SerializedMsg] = {
        Sync[F].delay { converter.toMsg(a) }
      }

      def fromMsg(a: SerializedMsg): F[AnyRef] = {
        for {
          a <- Sync[F].delay { converter.fromMsg(a) }
          a <- Sync[F].fromTry(a)
        } yield a
      }
    }
  }

  implicit class SerializedMsgSerializerOps[F[_]](val self: SerializedMsgSerializer[F]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): SerializedMsgSerializer[G] = new SerializedMsgSerializer[G] {

      def toMsg(a: AnyRef): G[SerializedMsg] = f(self.toMsg(a))

      def fromMsg(a: SerializedMsg): G[AnyRef] = f(self.fromMsg(a))
    }
  }
}
