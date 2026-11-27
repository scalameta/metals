package scala.meta.internal.kpc

import scala.jdk.CollectionConverters._

import kotlin.jvm.functions.Function1
import kotlin.sequences.Sequence

/** Adapters between Scala and the Kotlin APIs of the Analysis API. */
private[kpc] object Kt {
  def fn[A, B](f: A => B): Function1[A, B] =
    new Function1[A, B] { def invoke(a: A): B = f(a) }

  def unitFn[A](f: A => Any): Function1[A, kotlin.Unit] =
    fn { a =>
      f(a)
      kotlin.Unit.INSTANCE
    }

  def predicate[A](f: A => Boolean): Function1[A, java.lang.Boolean] =
    fn(a => java.lang.Boolean.valueOf(f(a)))

  def toList[A](seq: Sequence[_ <: A]): List[A] =
    seq.iterator().asScala.toList
}
