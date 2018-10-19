package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import scala.meta.internal.metals.SbtChecksum
import scala.meta.testkit.StringFS

object SbtChecksumSuite extends BaseSuite {
  def check(name: String, layout: String, expected: Option[String]): Unit = {
    test(name) {
      val root = StringFS.fromString(layout)
      val obtained = SbtChecksum.digest(root)
      (obtained, expected) match {
        case (None, None) => ()
        case (Some(x), Some(y)) =>
          assertNoDiff(x, y)
          Files.write(
            root.resolve("build.sbt").toNIO,
            "\n// this is a comment\n".getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND
          )
          val obtained2 = SbtChecksum.digest(root)
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
          val obtained3 = SbtChecksum.digest(root)
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

  val solo = "1C6D9CC0F064B0C6D7E205177F43AFC9"
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

  val project = "EA4CAAD6FEDEFCDA686DE401F4241E72"
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

}
