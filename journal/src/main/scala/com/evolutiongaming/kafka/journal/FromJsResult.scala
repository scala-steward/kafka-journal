package com.evolutiongaming.kafka.journal

import cats.ApplicativeError
import cats.effect.IO
import cats.implicits._
import play.api.libs.json.{JsResult, JsResultException}

import scala.util.Try


trait FromJsResult[F[_]] {

  def apply[A](fa: JsResult[A]): F[A]
}

object FromJsResult {

  def apply[F[_]](implicit F: FromJsResult[F]): FromJsResult[F] = F


  def lift[F[_]](implicit F: ApplicativeError[F, Throwable]): FromJsResult[F] = new FromJsResult[F] {

    def apply[A](fa: JsResult[A]) = {
      fa.fold(a => JournalError("play-json error", JsResultException(a).some).raiseError[F, A], _.pure[F])
    }
  }


  implicit val tryFromAttempt: FromJsResult[Try] = lift

  implicit val ioFromJsResult: FromJsResult[IO] = lift
}
