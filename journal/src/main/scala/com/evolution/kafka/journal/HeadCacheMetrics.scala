package com.evolution.kafka.journal

import cats.effect.Resource
import cats.{Applicative, Monad}
import com.evolution.scache.CacheMetrics
import com.evolutiongaming.smetrics.CollectorRegistry

final case class HeadCacheMetrics[F[_]](headCache: HeadCache.Metrics[F], cache: CacheMetrics[F])

object HeadCacheMetrics {

  def empty[F[_]: Applicative]: HeadCacheMetrics[F] = apply(HeadCache.Metrics.empty, CacheMetrics.empty)

  def make[F[_]: Monad](
    registry: CollectorRegistry[F],
    prefix: HeadCache.Metrics.Prefix = HeadCache.Metrics.Prefix.default,
  ): Resource[F, HeadCacheMetrics[F]] = {
    for {
      headCache <- HeadCache.Metrics.make(registry, prefix)
      cache <- CacheMetrics.of(registry, s"${ prefix }_${ CacheMetrics.Prefix.Default }")
    } yield {
      apply(headCache, cache(prefix))
    }
  }

}
