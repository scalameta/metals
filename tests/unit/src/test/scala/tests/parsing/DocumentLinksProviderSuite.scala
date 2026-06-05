package tests.parsing

import java.nio.file.Paths

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.DocumentLinksProvider
import scala.meta.io.AbsolutePath

import com.google.gson.JsonObject
import org.eclipse.lsp4j
import org.eclipse.lsp4j.DocumentLink
import tests.BaseSuite

class DocumentLinksProviderSuite extends BaseSuite {
  import DocumentLinksProviderSuite._
  val workspace: AbsolutePath = AbsolutePath(Paths.get("."))

  test("url-find-all-links-and-report-correct-ranges") {
    val buffers = new Buffers()
    val path = workspace.resolve("Baz.scala")
    val content =
      """|package foo.bar
         |
         |import baz.quux
         |import zzyx.xxx //Here's a link in comment: https://example.com some more text here
         |
         |http://example.com?wat=This%20is%20a%20compilation%20error%20but%20who%20cares
         |
         |/** Here's a link in Javadoc
         |  * @see https://scala-lang.org
         |  */
         |class Baz {}
         |""".stripMargin
    buffers.put(path, content)
    val provider = new DocumentLinksProvider(buffers)
    val obtained = provider.getLinks(path)
    val expected = List(
      documentLink(3, 44, 63, "https://example.com", "https://example.com"),
      documentLink(
        5, 0, 78,
        "http://example.com?wat=This%20is%20a%20compilation%20error%20but%20who%20cares",
        "http://example.com?wat=This is a compilation error but who cares",
      ),
      documentLink(8, 9, 31, "https://scala-lang.org", "https://scala-lang.org"),
    )
    assertEquals(obtained.asScala.toList, expected)
  }

  test("url-malformed-percent-encoding-does-not-crash") {
    val buffers = new Buffers()
    val path = workspace.resolve("Foo.scala")
    val url = "http://example.com/api/%2Z"
    buffers.put(path, url)
    val provider = new DocumentLinksProvider(buffers)
    val links = provider.getLinks(path).asScala.toList

    assertEquals(links.length, 1)
    assertEquals(links.head.getTarget, url)
    assertEquals(links.head.getTooltip, url)
  }

  test("url-trailing-dot-stripped") {
    val buffers = new Buffers()
    val path = workspace.resolve("Foo.scala")
    buffers.put(path, "// See https://metals.scalameta.org.")
    val provider = new DocumentLinksProvider(buffers)
    val links = provider.getLinks(path).asScala.toList

    assertEquals(links.length, 1)
    assertEquals(links.head.getTarget, "https://metals.scalameta.org")
    assertEquals(links.head.getRange.getStart.getCharacter, 7)
    assertEquals(links.head.getRange.getEnd.getCharacter, 35)
  }

  test("url-trailing-paren-stripped-when-unbalanced") {
    val buffers = new Buffers()
    val path = workspace.resolve("Foo.scala")
    buffers.put(path, "(see http://link.com)")
    val provider = new DocumentLinksProvider(buffers)
    val links = provider.getLinks(path).asScala.toList

    assertEquals(links.length, 1)
    assertEquals(links.head.getTarget, "http://link.com")
    assertEquals(links.head.getRange.getStart.getCharacter, 5)
    assertEquals(links.head.getRange.getEnd.getCharacter, 20)
  }

  test("url-balanced-parens-not-stripped") {
    val buffers = new Buffers()
    val path = workspace.resolve("Foo.scala")
    val url = "https://en.wikipedia.org/wiki/Foo_(disambiguation)"
    buffers.put(path, s"// See $url")
    val provider = new DocumentLinksProvider(buffers)
    val links = provider.getLinks(path).asScala.toList

    assertEquals(links.length, 1)
    assertEquals(links.head.getTarget, url)
  }

  test("java-link-tag-range-and-data") {
    val buffers = new Buffers()
    val path = workspace.resolve("App.java")
    val content =
      """|/**
         | * Uses {@link com.example.Foo} to do stuff.
         | */
         |class App {}
         |""".stripMargin
    buffers.put(path, content)
    val provider = new DocumentLinksProvider(buffers)
    val links = provider.getLinks(path).asScala.toList

    assertEquals(links.length, 1)
    val link = links.head
    assertEquals(link.getRange.getStart.getLine, 1)
    assertEquals(link.getRange.getStart.getCharacter, 15)
    assertEquals(link.getRange.getEnd.getCharacter, 30)
    assertEquals(link.getTooltip, "com.example.Foo")
    assertJavadocData(link, "com.example.Foo")
  }

  test("java-link-tag-captures-full-method-ref") {
    val buffers = new Buffers()
    val path = workspace.resolve("App.java")
    val content =
      """|/**
         | * Use {@link java.util.List#size()} for the count.
         | */
         |class App {}
         |""".stripMargin
    buffers.put(path, content)
    val provider = new DocumentLinksProvider(buffers)
    val links = provider.getLinks(path).asScala.toList

    assertEquals(links.length, 1)
    assertEquals(links.head.getRange.getStart.getCharacter, 14)
    assertEquals(links.head.getRange.getEnd.getCharacter, 35)
    assertEquals(links.head.getTooltip, "java.util.List#size()")
  }

  test("java-link-tag-local-method-ref") {
    val buffers = new Buffers()
    val path = workspace.resolve("App.java")
    val content =
      """|/**
         | * Uses {@link #myLocalMethod} to do stuff.
         | */
         |class App {}
         |""".stripMargin
    buffers.put(path, content)
    val provider = new DocumentLinksProvider(buffers)
    val links = provider.getLinks(path).asScala.toList

    assertEquals(links.length, 1)
    assertEquals(links.head.getRange.getStart.getCharacter, 15)
    assertEquals(links.head.getRange.getEnd.getCharacter, 29)
    assertEquals(links.head.getTooltip, "#myLocalMethod")
  }

  test("java-see-tag-captures-full-method-ref") {
    val buffers = new Buffers()
    val path = workspace.resolve("App.java")
    val content =
      """|/**
         | * @see java.util.List#add
         | */
         |class App {}
         |""".stripMargin
    buffers.put(path, content)
    val provider = new DocumentLinksProvider(buffers)
    val links = provider.getLinks(path).asScala.toList

    assertEquals(links.length, 1)
    assertEquals(links.head.getRange.getStart.getCharacter, 8)
    assertEquals(links.head.getRange.getEnd.getCharacter, 26)
    assertEquals(links.head.getTooltip, "java.util.List#add")
    assertJavadocData(link = links.head, "java.util.List#add")
  }

  test("java-link-tag-label-ignored") {
    val buffers = new Buffers()
    val path = workspace.resolve("App.java")
    val content =
      """|/**
         | * See {@link java.util.List#add addMethod} for details.
         | */
         |class App {}
         |""".stripMargin
    buffers.put(path, content)
    val provider = new DocumentLinksProvider(buffers)
    val links = provider.getLinks(path).asScala.toList

    assertEquals(links.length, 1)
    assertEquals(links.head.getTooltip, "java.util.List#add")
    assertEquals(links.head.getRange.getStart.getCharacter, 14)
    assertEquals(links.head.getRange.getEnd.getCharacter, 32)
  }

  test("java-see-tag-label-ignored") {
    val buffers = new Buffers()
    val path = workspace.resolve("App.java")
    val content =
      """|/**
         | * @see java.util.List#add addMethod
         | */
         |class App {}
         |""".stripMargin
    buffers.put(path, content)
    val provider = new DocumentLinksProvider(buffers)
    val links = provider.getLinks(path).asScala.toList

    assertEquals(links.length, 1)
    assertEquals(links.head.getTooltip, "java.util.List#add")
    assertEquals(links.head.getRange.getEnd.getCharacter, 26)
  }

  test("java-see-url-not-duplicated") {
    val buffers = new Buffers()
    val path = workspace.resolve("App.java")
    val content =
      """|/**
         | * @see https://example.com/docs
         | */
         |class App {}
         |""".stripMargin
    buffers.put(path, content)
    val provider = new DocumentLinksProvider(buffers)
    val links = provider.getLinks(path).asScala.toList

    assertEquals(links.length, 1)
    assertEquals(links.head.getTarget, "https://example.com/docs")
  }

  test("java-multiple-javadoc-tags") {
    val buffers = new Buffers()
    val path = workspace.resolve("App.java")
    val content =
      """|/**
         | * Uses {@link com.example.Foo} and {@link com.example.Bar}.
         | */
         |class App {}
         |""".stripMargin
    buffers.put(path, content)
    val provider = new DocumentLinksProvider(buffers)
    val links = provider.getLinks(path).asScala.toList

    assertEquals(links.length, 2)
    assertEquals(links(0).getTooltip, "com.example.Foo")
    assertEquals(links(1).getTooltip, "com.example.Bar")
  }
}

object DocumentLinksProviderSuite {

  def documentLink(
      line: Int,
      start: Int,
      end: Int,
      target: String,
      tooltip: String,
  ): DocumentLink = {
    val result = new DocumentLink()
    result.setRange(
      new lsp4j.Range(
        new lsp4j.Position(line, start),
        new lsp4j.Position(line, end),
      )
    )
    result.setTarget(target)
    result.setTooltip(tooltip)
    result
  }

  def assertJavadocData(link: DocumentLink, expectedSymbol: String): Unit = {
    assert(link.getData != null, "data must not be null")
    val obj = link.getData.asInstanceOf[JsonObject]
    assert(
      obj.get("symbol").getAsString == expectedSymbol,
      s"expected symbol '$expectedSymbol' but got '${obj.get("symbol").getAsString}'",
    )
    assert(obj.get("uri").getAsString.nonEmpty, "uri in data must not be empty")
  }
}
