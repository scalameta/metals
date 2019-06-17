package tests

import scala.meta.internal.builds.SbtDigest
import scala.meta.io.AbsolutePath

object SbtDigestSuite extends BaseDigestSuite {

  override def digestCurrent(
      root: AbsolutePath
  ): Option[String] = SbtDigest.current(root)

  checkSame(
    "same-build.sbt",
    """
      |/build.sbt
      |lazy val x = 2
    """.stripMargin,
    """
      |/build.sbt
      |lazy val x = 2
    """.stripMargin
  )

  checkSame(
    "only-sbt-file-at-workspace-level",
    """
      |/build.sbt
      |lazy val x = 2
      |/test.xml
      |<tag>
      |  <inner-tag>test</inner-tag>
      |</tag>
      |/Build.scala
      |package a.b
      |class A
    """.stripMargin,
    """
      |/build.sbt
      |lazy val x = 2
    """.stripMargin
  )

  checkSame(
    "comments-whitespace",
    """
      |/build.sbt
      |lazy val x = 2
    """.stripMargin,
    """
      |/build.sbt
      |lazy val x =
      | 2 // this is two
    """.stripMargin
  )

  checkDiff(
    "significant-tokens",
    """
      |/build.sbt
      |lazy val x = 2
      |lazy val anotherProject = x
    """.stripMargin,
    """
      |/build.sbt
      |lazy val x = 2
    """.stripMargin
  )

  checkDiff(
    "metabuild-not-ignored",
    """
      |/build.sbt
      |lazy val x = 2
    """.stripMargin,
    """
      |/build.sbt
      |lazy val x = 2
      |/project/Build.scala
      |package a.b
      |class A
    """.stripMargin
  )

  checkDiff(
    "meta-metabuild-not-ignored",
    """
      |/build.sbt
      |lazy val x = 2
      |/project/Build.scala
      |package a.b
      |class A
    """.stripMargin,
    """
      |/build.sbt
      |lazy val x = 2
      |/project/Build.scala
      |package a.b
      |class A
      |/project/project/Build2.scala
      |package a.b
      |class A
    """.stripMargin
  )

  checkDiff(
    "build.properties-not-ignored",
    """
      |/build.sbt
      |lazy val x = 2
      |""".stripMargin,
    """
      |/build.sbt
      |lazy val x = 2
      |/project/build.properties
      |sbt.version=2.0
      |""".stripMargin
  )

  checkDiff(
    "build.properties-diff",
    """
      |/build.sbt
      |lazy val x = 2
      |/project/build.properties
      |sbt.version=1.0
      |""".stripMargin,
    """
      |/build.sbt
      |lazy val x = 2
      |/project/build.properties
      |sbt.version=2.0
      |""".stripMargin
  )
}
