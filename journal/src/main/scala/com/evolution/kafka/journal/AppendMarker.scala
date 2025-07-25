package com.evolution.kafka.journal

import cats.FlatMap
import cats.syntax.all.*
import com.evolutiongaming.catshelper.RandomIdOf

trait AppendMarker[F[_]] {

  def apply(key: Key): F[Marker]
}

object AppendMarker {

  def apply[F[_]: FlatMap: RandomIdOf](
    produce: Produce[F],
  ): AppendMarker[F] = { (key: Key) =>
    {
      for {
        randomId <- RandomIdOf[F].apply
        partitionOffset <- produce.mark(key, randomId)
      } yield {
        Marker(randomId.value, partitionOffset)
      }
    }
  }
}
