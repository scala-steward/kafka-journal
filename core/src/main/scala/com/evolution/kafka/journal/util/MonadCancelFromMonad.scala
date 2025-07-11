package com.evolution.kafka.journal.util

import cats.Monad
import cats.effect.MonadCancel

private[journal] trait MonadCancelFromMonad[F[_], E] extends MonadCancel[F, E] {

  def F: Monad[F]

  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = F.flatMap(fa)(f)

  def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = F.tailRecM(a)(f)

  def pure[A](a: A): F[A] = F.pure(a)
}
