package scala.meta.internal.metals

import munit.TestOptions
import tests.BaseSuite

class JavaPcOptionsSuite extends BaseSuite {

  private def check(
      name: TestOptions,
      options: List[String],
      expected: List[String],
      runtimeMajor: Int = 21,
  ): Unit = {
    test(name) {
      assertEquals(
        CompilerConfiguration.clampJavaReleaseOptions(options, runtimeMajor),
        expected,
      )
    }
  }

  check(
    "lower-source-and-target",
    List("-source", "25", "-target", "25", "-encoding", "UTF-8"),
    List("-source", "21", "-target", "21", "-encoding", "UTF-8"),
  )
  check("lower-release", List("--release", "25"), List("--release", "21"))
  check(
    "lower-gnu-style-flags",
    List("--source", "25", "--target", "25"),
    List("--source", "21", "--target", "21"),
  )
  check(
    "lower-inline-flags",
    List("--source=25", "--target=17", "--release=24"),
    List("--source=21", "--target=17", "--release=21"),
  )
  check(
    "lower-each-flag-independently",
    List("-source", "17", "-target", "25"),
    List("-source", "17", "-target", "21"),
  )
  check(
    "lower-every-occurrence",
    List("--release", "25", "-source", "17", "--release", "24"),
    List("--release", "21", "-source", "17", "--release", "21"),
  )
  check(
    "keep-older-release",
    List("-source", "17", "-target", "17"),
    List("-source", "17", "-target", "17"),
  )
  check(
    "keep-matching-release",
    List("--release", "21"),
    List("--release", "21"),
  )
  check(
    "keep-paths-containing-a-version",
    List("-processorpath", "/tmp/errorprone-25.jar", "-source", "25"),
    List("-processorpath", "/tmp/errorprone-25.jar", "-source", "21"),
  )
}
