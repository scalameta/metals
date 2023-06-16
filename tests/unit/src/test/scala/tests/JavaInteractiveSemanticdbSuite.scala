package tests

import scala.meta.internal.metals.JdkVersion

import munit.FunSuite

class JavaInteractiveSemanticdbSuite extends FunSuite {

  test("parse jdk-version") {
    assertEquals(JdkVersion.parse("17-ea"), Some(JdkVersion(17)))
    assertEquals(JdkVersion.parse("9"), Some(JdkVersion(9)))
    assertEquals(JdkVersion.parse("1.8.0_312"), Some(JdkVersion(8)))
    assertEquals(JdkVersion.parse("11.0.13"), Some(JdkVersion(11)))
  }
}
