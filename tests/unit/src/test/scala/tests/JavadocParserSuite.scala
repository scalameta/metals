package tests

import scala.meta.internal.metals.JavadocParser

class JavadocParserSuite extends BaseSuite {

  // --- stripComment tests ---

  test("stripComment: basic single-line") {
    val lines = JavadocParser.stripComment("/** Hello world */")
    assertEquals(lines.map(_.trim).filter(_.nonEmpty), List("Hello world"))
  }

  test("stripComment: multi-line with leading stars") {
    val raw =
      """|/**
         | * Line one
         | * Line two
         | */""".stripMargin
    val lines = JavadocParser.stripComment(raw)
    val nonEmpty = lines.map(_.trim).filter(_.nonEmpty)
    assertEquals(nonEmpty, List("Line one", "Line two"))
  }

  test("stripComment: preserves extra indentation after first space") {
    val raw =
      """|/**
         | *   indented text
         | * normal text
         | */""".stripMargin
    val lines = JavadocParser.stripComment(raw)
    assert(lines.exists(_.contains("  indented text")))
    assert(lines.exists(l => l.trim == "normal text"))
  }

  test("stripComment: star without space") {
    val raw =
      """|/**
         | *no space after star
         | */""".stripMargin
    val lines = JavadocParser.stripComment(raw)
    val nonEmpty = lines.map(_.trim).filter(_.nonEmpty)
    assertEquals(nonEmpty, List("no space after star"))
  }

  // --- extractBody tests ---

  test("extractBody: None input") {
    assertEquals(JavadocParser.extractBody(None), "")
  }

  test("extractBody: empty comment") {
    assertEquals(JavadocParser.extractBody(Some("/**  */")), "")
  }

  test("extractBody: single-line body") {
    val doc = "/** Simple description */"
    val body = JavadocParser.extractBody(Some(doc))
    assertEquals(body, "Simple description")
  }

  test("extractBody: multi-line body") {
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

  test("extractBody: body stops before @param tags") {
    val doc =
      """|/**
         | * The body text.
         | * @param x description of x
         | */""".stripMargin
    val body = JavadocParser.extractBody(Some(doc))
    assertEquals(body, "The body text.")
  }

  test("extractBody: body stops before @return tag") {
    val doc =
      """|/**
         | * Returns something.
         | * @return the thing
         | */""".stripMargin
    val body = JavadocParser.extractBody(Some(doc))
    assertEquals(body, "Returns something.")
  }

  test("extractBody: comment starts with @tag (no body)") {
    val doc =
      """|/**
         | * @param x description
         | */""".stripMargin
    val body = JavadocParser.extractBody(Some(doc))
    assertEquals(body, "")
  }

  test("extractBody: @ in middle of body text is not treated as tag") {
    val doc =
      """|/**
         | * Email me at user@example.com for details.
         | */""".stripMargin
    val body = JavadocParser.extractBody(Some(doc))
    assertEquals(body, "Email me at user@example.com for details.")
  }

  // --- extractParamTags tests ---

  test("extractParamTags: None input") {
    assertEquals(
      JavadocParser.extractParamTags(None),
      Map.empty[String, String],
    )
  }

  test("extractParamTags: no params") {
    val doc =
      """|/**
         | * Just a description.
         | */""".stripMargin
    assertEquals(
      JavadocParser.extractParamTags(Some(doc)),
      Map.empty[String, String],
    )
  }

  test("extractParamTags: single param") {
    val doc =
      """|/**
         | * @param x the value
         | */""".stripMargin
    val tags = JavadocParser.extractParamTags(Some(doc))
    assertEquals(tags, Map("x" -> "x the value"))
  }

  test("extractParamTags: multiple params") {
    val doc =
      """|/**
         | * Description.
         | * @param x first param
         | * @param y second param
         | * @param z third param
         | */""".stripMargin
    val tags = JavadocParser.extractParamTags(Some(doc))
    assertEquals(
      tags,
      Map(
        "x" -> "x first param",
        "y" -> "y second param",
        "z" -> "z third param",
      ),
    )
  }

  test("extractParamTags: multi-line description") {
    val doc =
      """|/**
         | * @param x first line of description
         | *          second line of description
         | * @param y another param
         | */""".stripMargin
    val tags = JavadocParser.extractParamTags(Some(doc))
    assertEquals(
      tags("x"),
      "x first line of description second line of description",
    )
    assertEquals(tags("y"), "y another param")
  }

  test("extractParamTags: mixed tags — only @param extracted") {
    val doc =
      """|/**
         | * Body text.
         | * @param x the x value
         | * @return something
         | * @throws IOException on error
         | * @param y the y value
         | */""".stripMargin
    val tags = JavadocParser.extractParamTags(Some(doc))
    assertEquals(
      tags,
      Map(
        "x" -> "x the x value",
        "y" -> "y the y value",
      ),
    )
  }

  test("extractParamTags: param with no description") {
    val doc =
      """|/**
         | * @param x
         | */""".stripMargin
    val tags = JavadocParser.extractParamTags(Some(doc))
    assertEquals(tags, Map("x" -> "x"))
  }

  // --- toScaladocCompatible tests ---

  test("toScaladocCompatible: rewrites @param <T> to @tparam T") {
    val raw = "/** @param <T> the class of the objects */"
    assertEquals(
      JavadocParser.toScaladocCompatible(raw),
      "/** @tparam T the class of the objects */",
    )
  }

  test("toScaladocCompatible: preserves spacing between @param and <T>") {
    val raw = "/** @param  <T> the class of the objects */"
    assertEquals(
      JavadocParser.toScaladocCompatible(raw),
      "/** @tparam  T the class of the objects */",
    )
  }

  test("toScaladocCompatible: leaves regular @param tags untouched") {
    val raw = "/** @param x the value @param y the other value */"
    assertEquals(
      JavadocParser.toScaladocCompatible(raw),
      raw,
    )
  }

  test("toScaladocCompatible: handles mixed type-param and regular params") {
    val raw =
      """|/**
         | * @param <T> the type param
         | * @param o the regular param
         | */""".stripMargin
    assertEquals(
      JavadocParser.toScaladocCompatible(raw),
      """|/**
         | * @tparam T the type param
         | * @param o the regular param
         | */""".stripMargin,
    )
  }
}
