package tests

import scala.meta.internal.metals.JavaInteractiveSemanticdb.JdkVersion

import munit.FunSuite

class JavaInteractiveSemanticdbSuite extends FunSuite {

  test("parse jdk-version") {
    assertEquals(JdkVersion.parse("17-ea"), Some(JdkVersion(17, 0)))
    assertEquals(JdkVersion.parse("9"), Some(JdkVersion(1, 9)))
    assertEquals(JdkVersion.parse("1.8.0_312"), Some(JdkVersion(1, 8)))
    assertEquals(JdkVersion.parse("11.0.13"), Some(JdkVersion(11, 0)))
  }
}
