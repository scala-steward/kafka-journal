package com.evolution.kafka.journal

import cats.syntax.all.*

final case class JournalError(
  msg: String,
  cause: Option[Throwable] = None,
) extends RuntimeException(msg, cause.orNull)

object JournalError {

  def apply(msg: String, cause: Throwable): JournalError = JournalError(msg, cause.some)
}
