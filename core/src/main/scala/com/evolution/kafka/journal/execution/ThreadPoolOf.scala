package com.evolution.kafka.journal.execution

import cats.effect.{Resource, Sync}
import cats.syntax.all.*

import java.util.concurrent.{SynchronousQueue, ThreadFactory, ThreadPoolExecutor}
import scala.concurrent.duration.*

private[journal] object ThreadPoolOf {

  def apply[F[_]: Sync](
    minSize: Int,
    maxSize: Int,
    threadFactory: ThreadFactory,
    keepAlive: FiniteDuration = 5.minute,
  ): Resource[F, ThreadPoolExecutor] = {

    val result = for {
      result <- Sync[F].delay {
        new ThreadPoolExecutor(
          minSize,
          maxSize,
          keepAlive.length,
          keepAlive.unit,
          new SynchronousQueue[Runnable],
          threadFactory,
        )
      }
    } yield {
      val release = Sync[F].delay { result.shutdown() }
      (result, release)
    }

    Resource(result)
  }
}
