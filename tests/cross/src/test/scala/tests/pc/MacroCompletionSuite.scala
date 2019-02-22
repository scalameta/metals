package tests.pc

import java.nio.file.Path
import tests.BaseCompletionSuite

object MacroCompletionSuite extends BaseCompletionSuite {
  override def extraClasspath: Seq[Path] = thisClasspath
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
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|to(t: Person): ::[String,::[Int,HNil]]
           |toString(): String
           |""".stripMargin
    )
  )

  check(
    "blackbox",
    """
      |object A {
      |  sourcecode.File.generate.valu@@
      |}
      |""".stripMargin,
    """|value: String
       |""".stripMargin
  )

}
