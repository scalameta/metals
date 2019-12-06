package tests.pc

import tests.BasePCSuite
import scala.concurrent.duration.Duration

class BaseTypeDefinitionSuite extends BasePCSuite {
  val test: (String, String) => (String, String) =
    obtainedAndExpected(params => pc.typeDefinition(params))

  override def beforeAll(): Unit = {
    indexJDK()
    indexScalaLibrary()
  }

  def check(name: String)(
      code: String,
      compat: Map[String, String] = Map(),
      duration: Duration = Duration("3 min")
  ): Unit = {
    test(name) {
      val uri = "Main.scala"
      val (obtained, expected) = test(code, uri)
      assertNoDiff(obtained, getExpected(expected, compat))
    }
  }

}
