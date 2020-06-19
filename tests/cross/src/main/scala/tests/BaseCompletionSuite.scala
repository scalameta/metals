package tests

import java.nio.file.Paths
import java.util.Collections

import scala.collection.Seq

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.CancelToken

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList

abstract class BaseCompletionSuite extends BasePCSuite {

  def cancelToken: CancelToken = EmptyCancelToken

  private def resolvedCompletions(
      params: CompilerOffsetParams
  ): CompletionList = {
    val result = presentationCompiler.complete(params).get()
    val newItems = result.getItems.asScala.map { item =>
      item.data
        .map { data =>
          presentationCompiler.completionItemResolve(item, data.symbol).get()
        }
        .getOrElse(item)
    }
    result.setItems(newItems.asJava)
    result
  }

  def getItems(
      original: String,
      filename: String = "A.scala"
  ): Seq[CompletionItem] = {
    val (code, offset) = params(original)
    val result = resolvedCompletions(
      CompilerOffsetParams(
        Paths.get(filename).toUri(),
        code,
        offset,
        cancelToken
      )
    )
    result.getItems.asScala.sortBy(item =>
      Option(item.getSortText).getOrElse(item.getLabel())
    )
  }

  def checkItems(
      name: String,
      original: String,
      fn: Seq[CompletionItem] => Unit
  )(implicit loc: Location): Unit = {
    test(name) { fn(getItems(original)) }
  }

  def checkEditLine(
      name: TestOptions,
      template: String,
      original: String,
      expected: String,
      filterText: String = "",
      assertSingleItem: Boolean = true,
      filter: String => Boolean = _ => true,
      command: Option[String] = None,
      compat: Map[String, String] = Map.empty
  )(implicit loc: Location): Unit = {
    val compatTemplate = compat.map {
      case (key, value) =>
        key -> template.replace("___", value)
    }
    checkEdit(
      name = name,
      original = template.replace("___", original),
      expected = template.replace("___", expected),
      filterText = filterText,
      assertSingleItem = assertSingleItem,
      filter = filter,
      command = command,
      compat = compatTemplate
    )
  }

  def checkEdit(
      name: TestOptions,
      original: String,
      expected: String,
      filterText: String = "",
      assertSingleItem: Boolean = true,
      filter: String => Boolean = _ => true,
      command: Option[String] = None,
      compat: Map[String, String] = Map.empty
  )(implicit loc: Location): Unit = {
    test(name) {
      val items = getItems(original).filter(item => filter(item.getLabel))
      if (items.isEmpty) fail("obtained empty completions!")
      if (assertSingleItem && items.length != 1) {
        fail(
          s"expected single completion item, obtained ${items.length} items.\n${items}"
        )
      }
      val item = items.head
      val (code, _) = params(original)
      val obtained = TextEdits.applyEdits(code, item)
      assertNoDiff(
        obtained,
        getExpected(expected, compat, scalaVersion)
      )
      if (filterText.nonEmpty) {
        assertNoDiff(item.getFilterText, filterText, "Invalid filter text")
      }
      assertNoDiff(
        Option(item.getCommand).fold("")(_.getCommand),
        command.getOrElse(""),
        "Invalid command"
      )
    }
  }

  def checkSnippet(
      name: TestOptions,
      original: String,
      expected: String,
      compat: Map[String, String] = Map.empty
  )(implicit loc: Location): Unit = {
    test(name) {
      val items = getItems(original)
      val obtained = items
        .map { item =>
          Option(item.getTextEdit)
            .map(_.getNewText)
            .orElse(Option(item.getInsertText()))
            .getOrElse(item.getLabel)
        }
        .mkString("\n")
      assertNoDiff(
        obtained,
        getExpected(expected, compat, scalaVersion)
      )
    }
  }

  def check(
      name: TestOptions,
      original: String,
      expected: String,
      includeDocs: Boolean = false,
      includeCommitCharacter: Boolean = false,
      compat: Map[String, String] = Map.empty,
      postProcessObtained: String => String = identity,
      stableOrder: Boolean = true,
      postAssert: () => Unit = () => (),
      topLines: Option[Int] = None,
      filterText: String = "",
      includeDetail: Boolean = true,
      filename: String = "A.scala",
      filter: String => Boolean = _ => true,
      enablePackageWrap: Boolean = true
  )(implicit loc: Location): Unit = {
    test(name) {
      val out = new StringBuilder()
      val withPkg =
        if (original.contains("package") || !enablePackageWrap) original
        else s"package ${scala.meta.Term.Name(name.name)}\n$original"
      val baseItems = getItems(withPkg, filename)
      val items = topLines match {
        case Some(top) => baseItems.take(top)
        case None => baseItems
      }
      val filteredItems = items.filter(item => filter(item.getLabel))
      filteredItems.foreach { item =>
        val label = TestCompletions.getFullyQualifiedLabel(item)
        val commitCharacter =
          if (includeCommitCharacter)
            Option(item.getCommitCharacters)
              .getOrElse(Collections.emptyList())
              .asScala
              .mkString(" (commit: '", " ", "')")
          else ""
        val documentation = doc(item.getDocumentation)
        if (includeDocs && documentation.nonEmpty) {
          out.append("> ").append(documentation).append("\n")
        }
        out
          .append(label)
          .append({
            val detailIsDefined = Option(item.getDetail).isDefined
            if (
              includeDetail && detailIsDefined && !item.getLabel
                .contains(item.getDetail)
            ) {
              item.getDetail
            } else {
              ""
            }
          })
          .append(commitCharacter)
          .append("\n")
      }
      assertNoDiff(
        sortLines(
          stableOrder,
          postProcessObtained(trimTrailingSpace(out.toString()))
        ),
        sortLines(
          stableOrder,
          getExpected(expected, compat, scalaVersion)
        )
      )
      postAssert()
      if (filterText.nonEmpty) {
        filteredItems.foreach { item =>
          assertNoDiff(
            item.getFilterText,
            filterText,
            s"Invalid filter text for item:\n$item"
          )
        }
      }
    }
  }

  def trimTrailingSpace(string: String): String = {
    string.linesIterator
      .map(_.replaceFirst("\\s++$", ""))
      .mkString("\n")
  }

  override val compatProcess: Map[String, String => String] = Map(
    "2.13" -> { s =>
      s.replace("equals(obj: Any)", "equals(obj: Object)")
        .replace(
          "singletonList[T](o: T)",
          "singletonList[T <: Object](o: T)"
        )
    }
  )

}
