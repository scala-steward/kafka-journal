package com.evolution.kafka.journal.circe

import cats.syntax.all.*
import com.evolution.kafka.journal.JournalError
import com.evolutiongaming.catshelper.ApplicativeThrowable
import io.circe.Error

trait FromCirceResult[F[_]] {

  def apply[A](result: Either[Error, A]): F[A]

}

object FromCirceResult {

  def summon[F[_]](
    implicit
    fromCirceResult: FromCirceResult[F],
  ): FromCirceResult[F] = fromCirceResult

  implicit def lift[F[_]: ApplicativeThrowable]: FromCirceResult[F] = new FromCirceResult[F] {

    def apply[A](fa: Either[Error, A]): F[A] = {
      fa.fold(
        a => JournalError(s"FromCirceResult failed: $a", a).raiseError[F, A],
        a => a.pure[F],
      )
    }
  }

}
