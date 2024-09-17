package tests.pc

import coursierapi.Dependency
import tests.BaseCompletionSuite

class ShadowingCompletionSuite extends BaseCompletionSuite {

  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala211
  )

  override protected def extraDependencies(
      scalaVersion: String
  ): Seq[Dependency] = Seq(
    Dependency.of("io.get-coursier", "interface", "1.0.18")
  )

  check(
    "buffer",
    """package pkg
      |object Main {
      |  val x = ListBuff@@
      |}
      |""".stripMargin,
    """|ListBuffer[A](elems: A*): CC[A]
       |ListBuffer(i: Int): A
       |ListBuffer - scala.collection.mutable
       |""".stripMargin,
    compat = Map(
      "2" -> "ListBuffer - scala.collection.mutable",
      "3" -> """|ListBuffer[A](elems: A*): CC[A]
                |ListBuffer(i: Int): A
                |ListBuffer - scala.collection.mutable
                |ListBuffer - coursierapi.shaded.scala.collection.mutable
                |""".stripMargin
    )
  )
}
