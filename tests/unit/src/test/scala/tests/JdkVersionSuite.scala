package tests

import scala.concurrent.ExecutionContext

import scala.meta.AbsolutePath
import scala.meta.internal.metals.JdkVersion

import munit.FunSuite

class JdkVersionSuite extends FunSuite {
  val javaHome: AbsolutePath = AbsolutePath(System.getProperty("java.home"))
  implicit val ctx: ExecutionContext = this.munitExecutionContext
  test("jdk-shell-version") {
    assertEquals(
      JdkVersion.fromShell(javaHome).map(_.major),
      JdkVersion
        .fromReleaseFile(javaHome)
        .orElse(JdkVersion.parse(System.getProperty("java.version")))
        .map(_.major),
    )
  }
}
