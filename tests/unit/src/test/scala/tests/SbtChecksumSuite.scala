package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import scala.meta.internal.metals.SbtChecksum

object SbtChecksumSuite extends BaseSuite {
  def checksum(layout: String): Option[String] = {
    val root = FileLayout.fromString(layout)
    val obtained = SbtChecksum.current(root)
    obtained
  }
  def check(name: String, layout: String, expected: Option[String]): Unit = {
    test(name) {
      val root = FileLayout.fromString(layout)
      val obtained = SbtChecksum.current(root)
      (obtained, expected) match {
        case (None, None) => ()
        case (Some(x), Some(y)) =>
          assertNoDiff(x, y)
          Files.write(
            root.resolve("build.sbt").toNIO,
            "\n// this is a comment\n".getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND
          )
          val obtained2 = SbtChecksum.current(root)
          assertEquals(
            obtained2,
            obtained,
            "comments and whitespace did impact checksum"
          )
          Files.write(
            root.resolve("build.sbt").toNIO,
            "\nlazy val anotherProject = x\n".getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND
          )
          val obtained3 = SbtChecksum.current(root)
          assertNotEquals(
            obtained3,
            obtained,
            "significant tokens did not impact checksum"
          )
        case (None, Some(y)) =>
          fail(s"expected checksum $y but did not obtain a checksum")
        case (Some(x), None) =>
          fail(s"expected no checksum but did obtained checksum $x")
      }
    }
  }

  val solo = "62E46F71242E515912BE6814C9356D63"
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

  val project = "08E20CE98E81FAB7388686CCADFC2F64"
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
    val v1 = checksum(
      """
        |/build.sbt
        |lazy val x = 2
        |/project/build.properties
        |sbt.version=1.0
        |""".stripMargin
    ).get
    val v2 = checksum(
      """
        |/build.sbt
        |lazy val x = 2
        |/project/build.properties
        |sbt.version=2.0
        |""".stripMargin
    ).get
    assertNotEquals(v1, solo, "build.properties should affect checksum")
    assertNotEquals(v1, v2, "build.properties should affect checksum")
  }

}
