package tests

import scala.meta.internal.metals.NewFileTemplate

class NewFileTemplateSuite extends BaseSuite {

  test("cursor-marker-errors") {
    intercept[IllegalArgumentException] {
      NewFileTemplate("no cursor markers")
    }
    intercept[IllegalArgumentException] {
      NewFileTemplate("many cursor @@ markers @@")
    }
  }

  test("cursor-marker-position") {
    val template =
      s"""|package a
          |
          |case class Foo()
          |""".stripMargin
    val cursorOffsets = 0.to(template.length)
    cursorOffsets.foreach { cursorOffset =>
      val templateWithCursor = template.patch(cursorOffset, "@@", 0)
      val newFileTemplate = NewFileTemplate(templateWithCursor)
      assertEquals(newFileTemplate.cursorPosition.start, cursorOffset)
    }
  }

}
