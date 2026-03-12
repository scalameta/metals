package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.clients.language.MetalsQuickPickItem
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.TextDocumentPositionParams

/**
 * Provides navigation from a source class to its corresponding test class
 * and vice versa, based on naming conventions and semanticdb symbols.
 *
 * Uses the semanticdb index to find the enclosing class at the cursor position,
 * then generates candidate test/source symbol names and resolves them via
 * symbol search.
 */
class GotoTestProvider(
    semanticdbs: () => Semanticdbs,
    symbolSearch: MetalsSymbolSearch,
    workspaceSymbols: WorkspaceSymbolProvider,
    languageClient: ConfiguredLanguageClient,
)(implicit ec: ExecutionContext) {

  def goto(
      params: TextDocumentPositionParams
  ): Future[Unit] = Future {
    val uri = params.getTextDocument().getUri()
    val path = uri.toAbsolutePath

    findEnclosingClassSymbol(path, params.getPosition()) match {
      case None =>
        scribe.debug(s"No enclosing class found at position in $uri")
        languageClient.showMessage(
          Messages.noTestClassFound(path.filename)
        )
      case Some(classSymbol) =>
        val className = Symbol(classSymbol).displayName
        val candidateSymbols = findCandidateSymbols(classSymbol)

        val results = (for {
          candidateSymbol <- candidateSymbols
          loc <- symbolSearch
            .definition(candidateSymbol, path.toURI)
            .asScala
        } yield (candidateSymbol, loc)).distinctBy(_._2.getUri())

        val finalResults =
          if (results.nonEmpty) results
          else searchByClassName(candidateSymbols, path)

        finalResults match {
          case Nil =>
            scribe.debug(
              s"No test/source class found for $classSymbol"
            )
            languageClient.showMessage(
              Messages.noTestClassFound(className)
            )
          case (_, location) :: Nil =>
            gotoLocation(location)
          case multiple =>
            showQuickPick(multiple)
        }
    }
  }

  def hasTarget(classSymbol: String, path: AbsolutePath): Boolean = {
    val candidateSymbols = findCandidateSymbols(classSymbol)
    val hasDefinition = candidateSymbols.exists { candidate =>
      !symbolSearch.definition(candidate, path.toURI).isEmpty
    }
    hasDefinition || searchByClassName(candidateSymbols, path).nonEmpty
  }

  private def searchByClassName(
      candidateSymbols: List[String],
      path: AbsolutePath,
  ): List[(String, Location)] = {
    val candidateNames = candidateSymbols.map(Symbol(_).displayName).distinct
    (for {
      name <- candidateNames
      info <- workspaceSymbols.search(name, Some(path))
      if candidateNames.contains(info.getName())
    } yield (info.getName(), info.getLocation())).distinctBy(_._2.getUri())
  }

  private def gotoLocation(location: Location): Unit =
    languageClient.metalsExecuteClientCommand(
      ClientCommands.GotoLocation.toExecuteCommandParams(
        ClientCommands.WindowLocation(
          location.getUri(),
          location.getRange(),
        )
      )
    )

  private def showQuickPick(
      results: List[(String, Location)]
  ): Unit = {
    val items = results.map { case (symbol, loc) =>
      val name = Symbol(symbol).displayName
      MetalsQuickPickItem(loc.getUri(), name, symbol)
    }
    languageClient
      .metalsQuickPick(
        MetalsQuickPickParams(
          items.asJava,
          placeHolder = "Select target class",
        )
      )
      .asScala
      .foreach {
        case Some(result) =>
          results
            .find(_._2.getUri() == result.itemId)
            .foreach { case (_, loc) => gotoLocation(loc) }
        case None => // cancelled
      }
  }

  /**
   * Find the semanticdb symbol of the innermost class/trait/object
   * that encloses the given position.
   */
  private def findEnclosingClassSymbol(
      path: AbsolutePath,
      position: org.eclipse.lsp4j.Position,
  ): Option[String] = {
    for {
      textDocument <- semanticdbs()
        .textDocument(path)
        .documentIncludingStale
      symbol <- findEnclosingClassInDocument(textDocument, position)
    } yield symbol
  }

  private def findEnclosingClassInDocument(
      doc: s.TextDocument,
      position: org.eclipse.lsp4j.Position,
  ): Option[String] = {
    // Find all class/trait/object definition occurrences
    val classDefinitions = doc.occurrences.filter { occ =>
      occ.role.isDefinition && occ.symbol.isType
    }

    // Find the innermost class definition that encloses the position
    // (the one with the most specific/latest start position before cursor)
    val line = position.getLine()
    val char = position.getCharacter()

    classDefinitions
      .filter { occ =>
        occ.range.exists { range =>
          range.startLine < line ||
          (range.startLine == line && range.startCharacter <= char)
        }
      }
      .sortBy { occ =>
        occ.range.map(r => (r.startLine, r.startCharacter)).getOrElse((0, 0))
      }
      .lastOption
      .map(_.symbol)
  }

  /**
   * Given a semanticdb class symbol, produce candidate symbols for navigation.
   *
   * For a source class `a/b/MyClass#`, generates:
   *   - `a/b/MyClassTest#`, `a/b/MyClassSuite#`, etc.
   *
   * For a test class `a/b/MyClassTest#`, generates:
   *   - `a/b/MyClass#`
   *
   * Also generates object variants (`.` suffix) for each candidate.
   */
  private def findCandidateSymbols(classSymbol: String): List[String] = {
    val symbol = Symbol(classSymbol)
    val owner = symbol.owner
    val name = symbol.displayName

    val candidateNames = findCandidateNames(name)

    candidateNames.flatMap { candidateName =>
      List(
        Symbols.Global(owner.value, Descriptor.Type(candidateName)),
        Symbols.Global(owner.value, Descriptor.Term(candidateName)),
      )
    }
  }

  /**
   * Given a class name, produce candidate class names for navigation.
   * If the name ends with a test suffix, strip it to find the source class.
   * Otherwise, append test suffixes to find test classes.
   */
  private def findCandidateNames(name: String): List[String] =
    GotoTestProvider.findCandidateNames(name)
}

object GotoTestProvider {
  val testSuffixes: List[String] =
    List("Test", "LspSuite", "Suite", "Spec", "Tests")
  val testPrefixes: List[String] = List("Test")

  def isTestClass(name: String): Boolean =
    testSuffixes.exists(s => name.endsWith(s) && name.length > s.length) ||
      testPrefixes.exists(p => name.startsWith(p) && name.length > p.length)

  def findCandidateNames(name: String): List[String] = {
    val strippedSuffix = testSuffixes.collectFirst {
      case suffix if name.endsWith(suffix) && name.length > suffix.length =>
        name.stripSuffix(suffix)
    }

    val strippedPrefix = testPrefixes.collectFirst {
      case prefix if name.startsWith(prefix) && name.length > prefix.length =>
        name.stripPrefix(prefix)
    }

    strippedSuffix.orElse(strippedPrefix) match {
      case Some(baseName) =>
        List(baseName)
      case None =>
        testSuffixes.map(suffix => name + suffix) ++
          testPrefixes.map(prefix => prefix + name)
    }
  }
}
