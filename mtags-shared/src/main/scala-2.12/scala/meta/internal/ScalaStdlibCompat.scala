package scala.meta.internal

import scala.util.{Either, Left, Right}

object ScalaStdlibCompat {
  implicit class EitherCompat[+A, +B](private val value: Either[A, B])
      extends AnyVal {
    def flatMap[A1 >: A, B1](f: B => Either[A1, B1]): Either[A1, B1] =
      value match {
        case Right(b) => f(b)
        case _ => value.asInstanceOf[Either[A1, B1]]
      }

    def map[B1](f: B => B1): Either[A, B1] = value match {
      case Right(b) => Right(f(b))
      case _ => value.asInstanceOf[Either[A, B1]]
    }
  }
}
