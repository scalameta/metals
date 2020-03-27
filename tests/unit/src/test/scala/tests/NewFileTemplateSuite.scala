package tests

import munit.ScalaCheckSuite
import scala.meta.internal.metals.NewFileTemplate
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class NewFileTemplateSuite extends BaseSuite with ScalaCheckSuite {

  test("cursor-marker-errors") {
    intercept[IllegalArgumentException] {
      NewFileTemplate("no cursor markers")
    }
    intercept[IllegalArgumentException] {
      NewFileTemplate("many cursor @@ markers @@")
    }
  }

  property("cursor-marker-position") {
    val template =
      s"""|package a
          |
          |case class Foo()
          |""".stripMargin
    val cursorOffsetGen = Gen.chooseNum(0, template.length)
    forAll(cursorOffsetGen) { cursorOffset =>
      val templateWithCursor = template.patch(cursorOffset, "@@", 0)
      val newFileTemplate = NewFileTemplate(templateWithCursor)
      assertEquals(newFileTemplate.cursorPosition.start, cursorOffset)
    }
  }

}
