package tests.pc

import java.nio.file.Path
import tests.BaseCompletionSuite

object ShapelessCompletionSuite extends BaseCompletionSuite {
  override def extraClasspath: Seq[Path] =
    thisClasspath.filter(_.getFileName.toString.contains("shapeless"))
  override def beforeAll(): Unit = ()

  check(
    "generic",
    """
      |import shapeless._
      |case class Person(name: String, age: Int)
      |object Person {
      |  val gen = Generic[Person]
      |  gen.to@@
      |}
      |""".stripMargin,
    """|to(t: Person): String :: Int :: HNil
       |toString(): String
       |""".stripMargin
  )
}
