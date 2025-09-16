package tests

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService

import scala.meta.internal.metals.MtagsResolver

class MtagsResolverSuite extends BaseSuite {
  implicit val ex: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  def assertResolve(scalaVersion: String): Unit = {
    val result = new MtagsResolver.Default().resolve(scalaVersion)
    assert(
      result.isDefined,
      s"Should resolve mtags for Scala $scalaVersion",
    )
  }

  test("resolve mtags for Scala 3.4.0") {
    assertResolve("3.4.0")
  }

  test("resolve mtags for Scala 3.4.1") {
    assertResolve("3.4.1")
  }

  test("resolve mtags for Scala 3.4.2") {
    assertResolve("3.4.2")
  }

  test("resolve mtags for Scala 3.4.3") {
    assertResolve("3.4.3")
  }

  test("resolve mtags for Scala 3.5.0") {
    assertResolve("3.5.0")
  }
}
