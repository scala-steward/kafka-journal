package com.evolutiongaming.kafka.journal.util

import cats.Applicative
import cats.effect.{Fiber, Resource}

object ResourceOf {

  def apply[F[_]: Applicative, A](fiber: F[Fiber[F, A]]): Resource[F, Fiber[F, A]] = {
    Resource.make { fiber } { _.cancel }
  }
}
