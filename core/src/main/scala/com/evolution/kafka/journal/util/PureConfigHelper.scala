package com.evolution.kafka.journal.util

import com.evolution.kafka.journal.FromConfigReaderResult
import pureconfig.ConfigReader

import scala.reflect.ClassTag

object PureConfigHelper {

  implicit class ConfigReaderResultOpsPureConfigHelper[A](val self: ConfigReader.Result[A]) extends AnyVal {

    def liftTo[F[_]: FromConfigReaderResult](
      implicit
      resultValueClassTag: ClassTag[A],
    ): F[A] = FromConfigReaderResult[F].liftToF(self)
  }
}
