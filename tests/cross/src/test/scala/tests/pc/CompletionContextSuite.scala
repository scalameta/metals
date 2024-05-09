package tests.pc

import scala.meta.pc.CompletionItemPriority

import coursierapi._
import tests.BaseCompletionSuite

class CompletionContextSuite extends BaseCompletionSuite {

  override val completionItemPriority: CompletionItemPriority = {
    case "java/time/Clock#" => -1
    case "scala/concurrent/Future." => -1
    case _ => 0
  }

  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala211
  )

  override def extraDependencies(scalaVersion: String): Seq[Dependency] =
    Seq(
      Dependency.of(
        Module.of(
          "org.typelevel",
          s"cats-effect_${createBinaryVersion(scalaVersion)}"
        ),
        "3.5.3"
      )
    )

  // java.time.Clock should be ranked higher than cats.effect.kernel.Clock
  val clockCompletionResult: List[String] =
    List("Clock - java.time", "Clock - cats.effect.kernel")

  check(
    "context",
    """package context
      |object A {
      |  Cloc@@
      |}""".stripMargin,
    """Clock - java.time
      |Clock - cats.effect.kernel
      |""".stripMargin,
    filter = clockCompletionResult.contains
  )

  // scala.concurrent.Future should be ranked higher than java.util.concurrent.Future
  val futureCompletionResult: List[String] =
    List("Future - scala.concurrent", "Future - java.util.concurrent")

  check(
    "context",
    """package fut
      |object A {
      |  Futur@@
      |}""".stripMargin,
    """Future - scala.concurrent
      |Future - java.util.concurrent
      |""".stripMargin,
    filter = futureCompletionResult.contains
  )
}
