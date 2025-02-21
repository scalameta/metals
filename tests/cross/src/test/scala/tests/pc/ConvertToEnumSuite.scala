package tests.pc

import java.net.URI
import java.util.Optional

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits
import scala.meta.pc.CodeActionId

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.TextEdit
import tests.BaseCodeActionSuite

class ConvertToEnumSuite extends BaseCodeActionSuite {
  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala2
  )

  checkEdit(
    "basic",
    """|sealed trait <<C>>ow
       |object Cow:
       |  class HolsteinFriesian extends Cow
       |  class Highland extends Cow
       |  class BrownSwiss extends Cow
       |""".stripMargin,
    """|enum Cow:
       |  case HolsteinFriesian, Highland, BrownSwiss
       |""".stripMargin
  )

  checkEdit(
    "basic-with-params",
    """|sealed class <<C>>ow[T](val i: Int, j: Int)
       |object Cow:
       |  class HolsteinFriesian extends Cow[1](1, 1)
       |  class Highland extends Cow[2](2, 2)
       |  class BrownSwiss extends Cow[3](3, 3)
       |""".stripMargin,
    """|enum Cow[T](val i: Int, j: Int):
       |  case HolsteinFriesian extends Cow[1](1, 1)
       |  case Highland extends Cow[2](2, 2)
       |  case BrownSwiss extends Cow[3](3, 3)
       |""".stripMargin
  )

  checkEdit(
    "class-with-body",
    """|trait Spotted
       |
       |sealed trait <<C>>ow:
       |  def moo = "Mooo!"
       |
       |object Cow:
       |  def of(name: String) = HolsteinFriesian(name)
       |  case class HolsteinFriesian(name: String) extends Cow, Spotted
       |  class Highland extends Cow
       |  class BrownSwiss extends Cow
       |""".stripMargin,
    """|trait Spotted
       |
       |enum Cow:
       |  def moo = "Mooo!"
       |  case Highland, BrownSwiss
       |  case HolsteinFriesian(name: String) extends Cow, Spotted
       |
       |object Cow:
       |  def of(name: String) = HolsteinFriesian(name)
       |""".stripMargin
  )

  checkEdit(
    "with-indentation",
    """|object O {
       |  sealed class <<C>>ow {
       |    def moo = "Mooo!"
       |    def mooooo = "Mooooooo!"
       |  }
       |  object Cow {
       |    case class HolsteinFriesian(name: String) extends Cow
       |    class Highland extends Cow
       |    class BrownSwiss extends Cow
       |  }
       |}
       |""".stripMargin,
    """|object O {
       |  enum Cow {
       |    def moo = "Mooo!"
       |    def mooooo = "Mooooooo!"
       |    case Highland, BrownSwiss
       |    case HolsteinFriesian(name: String) extends Cow
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "case-objects",
    """|sealed trait <<C>>ow
       |case object HolsteinFriesian extends Cow
       |case object Highland extends Cow
       |case object BrownSwiss extends Cow
       |""".stripMargin,
    """|enum Cow:
       |  case HolsteinFriesian, Highland, BrownSwiss
       |export Cow.*
       |""".stripMargin
  )

  checkEdit(
    "no-companion-object",
    """|sealed trait <<C>>ow
       |class HolsteinFriesian extends Cow
       |class Highland extends Cow
       |class BrownSwiss extends Cow
       |""".stripMargin,
    """|enum Cow:
       |  case HolsteinFriesian, Highland, BrownSwiss
       |export Cow.*
       |""".stripMargin
  )

  def checkError(
      name: TestOptions,
      original: String,
      expectedError: String
  )(implicit loc: munit.Location): Unit = {
    test(name) {
      Try(getConversionToEnum(original)) match {
        case Failure(exception: Throwable) =>
          assertNoDiff(
            exception.getCause().getMessage().replaceAll("\\[.*\\]", ""),
            expectedError
          )
        case Success(_) =>
          fail("Expected an error but got a result")
      }
    }
  }

  def checkEdit(
      name: TestOptions,
      original: String,
      expected: String,
      compat: Map[String, String] = Map.empty
  )(implicit location: Location): Unit =
    test(name) {
      val edits = getConversionToEnum(original)
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, getExpected(expected, compat, scalaVersion))
    }

  def getConversionToEnum(
      original: String,
      filename: String = "file:/A.scala"
  ): List[TextEdit] = {
    val (code, _, offset) = params(original)
    val result = presentationCompiler
      .codeAction[Boolean](
        CompilerOffsetParams(URI.create(filename), code, offset, cancelToken),
        CodeActionId.ConvertToEnum,
        Optional.empty()
      )
      .get()
    result.asScala.toList
  }

}
