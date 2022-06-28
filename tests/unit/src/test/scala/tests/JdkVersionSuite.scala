package tests

import scala.meta.AbsolutePath
import scala.meta.internal.metals.JdkVersion

import munit.FunSuite

class JdkVersionSuite extends FunSuite {
  val javaHome: AbsolutePath = AbsolutePath(System.getProperty("java.home"))
  test("jdk-shell-version") {
    assertEquals(
      JdkVersion.fromShell(javaHome),
      JdkVersion.fromReleaseFile(javaHome)
    )
    assertEquals(
      JdkVersion.fromShell(javaHome),
      JdkVersion.parse(System.getProperty("java.version"))
    )
  }
}
