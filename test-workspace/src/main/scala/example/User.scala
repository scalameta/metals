package myexample

import scala.concurrent.Future

object A {
  scala.concurrent.Future.traverse(List(scala.concurrent.Future.successful(1)))
  scala.concurrent.Future.traverse(List(scala.concurrent.Future.successful(1)))
}
