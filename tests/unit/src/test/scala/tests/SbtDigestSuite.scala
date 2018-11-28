package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import scala.meta.internal.metals.SbtDigest

object SbtDigestSuite extends BaseSuite {
  def digest(layout: String): Option[String] = {
    val root = FileLayout.fromString(layout)
    val obtained = SbtDigest.current(root)
    obtained
  }
  def check(name: String, layout: String, expected: Option[String]): Unit = {
    test(name) {
      val root = FileLayout.fromString(layout)
      val obtained = SbtDigest.current(root)
      (obtained, expected) match {
        case (None, None) => ()
        case (Some(x), Some(y)) =>
          assertNoDiff(x, y)
          Files.write(
            root.resolve("build.sbt").toNIO,
            "\n// this is a comment\n".getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND
          )
          val obtained2 = SbtDigest.current(root)
          assertEquals(
            obtained2,
            obtained,
            "comments and whitespace did impact digest"
          )
          Files.write(
            root.resolve("build.sbt").toNIO,
            "\nlazy val anotherProject = x\n".getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND
          )
          val obtained3 = SbtDigest.current(root)
          assertNotEquals(
            obtained3,
            obtained,
            "significant tokens did not impact digest"
          )
        case (None, Some(y)) =>
          fail(s"expected digest $y but did not obtain a digest")
        case (Some(x), None) =>
          fail(s"expected no digest but did obtained digest $x")
      }
    }
  }

  val solo = "5727878FADFA3FB9EBA4B97CA92440ED"
  check(
    "solo build.sbt",
    """
      |/build.sbt
      |lazy val x = 2
    """.stripMargin,
    Some(solo)
  )

  check(
    "comments and whitespace have no effect",
    """
      |/build.sbt
      |lazy val x =
      | 2 // this is two
    """.stripMargin,
    Some(solo)
  )

  val project = "7BDAC1318CFF8DFE816EDEFF5C06F561"
  check(
    "metabuild",
    """
      |/build.sbt
      |lazy val x = 2
      |/project/Build.scala
      |package a.b
      |class A
    """.stripMargin,
    Some(project)
  )

  // meta-meta builds impact the build itself but unlikely impact build projects.
  check(
    "meta-metabuild is ignored",
    """
      |/build.sbt
      |lazy val x = 2
      |/project/Build.scala
      |package a.b
      |class A
      |/project/project/Build2.scala
      |package a.b
      |class A
    """.stripMargin,
    Some(project)
  )

  test("build.properties") {
    val v1 = digest(
      """
        |/build.sbt
        |lazy val x = 2
        |/project/build.properties
        |sbt.version=1.0
        |""".stripMargin
    ).get
    val v2 = digest(
      """
        |/build.sbt
        |lazy val x = 2
        |/project/build.properties
        |sbt.version=2.0
        |""".stripMargin
    ).get
    assertNotEquals(v1, solo, "build.properties should affect digest")
    assertNotEquals(v1, v2, "build.properties should affect digest")
  }

}
