package tests

import scala.meta.internal.metals.JavadocParser

class JavadocParserSuite extends BaseSuite {

  // --- stripComment tests ---

  test("stripComment-basic-single-line") {
    val lines = JavadocParser.stripComment("/** Hello world */")
    assertEquals(lines.map(_.trim).filter(_.nonEmpty), List("Hello world"))
  }

  test("stripComment-multi-line-with-leading-stars") {
    val raw =
      """|/**
         | * Line one
         | * Line two
         | */""".stripMargin
    val lines = JavadocParser.stripComment(raw)
    val nonEmpty = lines.map(_.trim).filter(_.nonEmpty)
    assertEquals(nonEmpty, List("Line one", "Line two"))
  }

  test("stripComment-preserves-extra-indentation-after-first-space") {
    val raw =
      """|/**
         | *   indented text
         | * normal text
         | */""".stripMargin
    val lines = JavadocParser.stripComment(raw)
    assert(lines.exists(_.contains("  indented text")))
    assert(lines.exists(l => l.trim == "normal text"))
  }

  test("stripComment-star-without-space") {
    val raw =
      """|/**
         | *no space after star
         | */""".stripMargin
    val lines = JavadocParser.stripComment(raw)
    val nonEmpty = lines.map(_.trim).filter(_.nonEmpty)
    assertEquals(nonEmpty, List("no space after star"))
  }

  // --- extractBody tests ---

  test("extractBody-none-input") {
    assertEquals(JavadocParser.extractBody(None), "")
  }

  test("extractBody-empty-comment") {
    assertEquals(JavadocParser.extractBody(Some("/**  */")), "")
  }

  test("extractBody-single-line-body") {
    val doc = "/** Simple description */"
    val body = JavadocParser.extractBody(Some(doc))
    assertEquals(body, "Simple description")
  }

  test("extractBody-multi-line-body") {
    val doc =
      """|/**
         | * First line.
         | * Second line.
         | */""".stripMargin
    val body = JavadocParser.extractBody(Some(doc))
    assertNoDiff(
      body,
      """|First line.
         |Second line.""".stripMargin,
    )
  }

  test("extractBody-body-stops-before-param-tags") {
    val doc =
      """|/**
         | * The body text.
         | * @param x the x value
         | */""".stripMargin
    val body = JavadocParser.extractBody(Some(doc))
    assertEquals(body, "The body text.")
  }

  test("extractBody-body-stops-before-return-tag") {
    val doc =
      """|/**
         | * Computes the sum.
         | * @return the result
         | */""".stripMargin
    val body = JavadocParser.extractBody(Some(doc))
    assertEquals(body, "Computes the sum.")
  }

  // --- extractParamTags tests ---

  test("extractParamTags-none-input") {
    assertEquals(
      JavadocParser.extractParamTags(None),
      Map.empty[String, String],
    )
  }

  test("extractParamTags-no-params") {
    val doc =
      """|/**
         | * Just a body.
         | * @return something
         | */""".stripMargin
    assertEquals(
      JavadocParser.extractParamTags(Some(doc)),
      Map.empty[String, String],
    )
  }

  test("extractParamTags-single-param") {
    val doc =
      """|/**
         | * @param name the user name
         | */""".stripMargin
    val tags = JavadocParser.extractParamTags(Some(doc))
    assertEquals(tags, Map("name" -> "name the user name"))
  }

  test("extractParamTags-multiple-params") {
    val doc =
      """|/**
         | * @param x the x coordinate
         | * @param y the y coordinate
         | * @param z the z coordinate
         | */""".stripMargin
    val tags = JavadocParser.extractParamTags(Some(doc))
    assertEquals(tags.size, 3)
    assertEquals(tags("x"), "x the x coordinate")
    assertEquals(tags("y"), "y the y coordinate")
    assertEquals(tags("z"), "z the z coordinate")
  }

  test("extractParamTags-multi-line-description") {
    val doc =
      """|/**
         | * @param name the user name which
         | *        can span multiple lines
         | * @param age the user age
         | */""".stripMargin
    val tags = JavadocParser.extractParamTags(Some(doc))
    assertEquals(tags.size, 2)
    assert(tags("name").contains("can span multiple lines"))
    assertEquals(tags("age"), "age the user age")
  }

  test("extractParamTags-type-param") {
    val doc =
      """|/**
         | * @param <T> the element type
         | * @param list the input list
         | */""".stripMargin
    val tags = JavadocParser.extractParamTags(Some(doc))
    assertEquals(tags.size, 2)
    assertEquals(tags("<T>"), "<T> the element type")
    assertEquals(tags("list"), "list the input list")
  }

  test("extractParamTags-param-without-description") {
    val doc =
      """|/**
         | * @param x
         | */""".stripMargin
    val tags = JavadocParser.extractParamTags(Some(doc))
    assertEquals(tags, Map("x" -> "x"))
  }

  test("extractParamTags-mixed-tags") {
    val doc =
      """|/**
         | * Does something.
         | * @param x the input
         | * @return the output
         | * @throws Exception on error
         | * @param y another input
         | */""".stripMargin
    val tags = JavadocParser.extractParamTags(Some(doc))
    // @return and @throws stop param accumulation, but @param y after them resumes
    assertEquals(tags.size, 2)
    assertEquals(tags("x"), "x the input")
    assertEquals(tags("y"), "y another input")
  }
}
