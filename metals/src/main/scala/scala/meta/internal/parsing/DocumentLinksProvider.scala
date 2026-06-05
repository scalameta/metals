package scala.meta.internal.parsing

import java.net.URLDecoder
import java.util

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.eclipse.lsp4j.DocumentLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Provides document links for URLs and Javadoc references in source files.
 *
 * @param buffers the file buffers
 * @param definitionProvider optional definition provider for resolving symbols
 */
final class DocumentLinksProvider(
    buffers: Buffers,
    definitionProvider: Option[DefinitionProvider] = None,
) {

  // Matches http/https URLs anywhere in a line, stopping at quotes and angle brackets
  private val urlRegex = raw"""https?://[^\s"'<>]+""".r

  // Matches {@link Ref} or {@linkplain Ref#method(params) label}
  // The params part can contain spaces, so we match non-paren chars first, then optional parens
  private val javadocLinkTagRegex =
    raw"\{@link(?:plain)?\s+([^\s(}]+(?:\([^)]*\))?)".r

  // Matches @see Ref, @see Ref#method, or @see Ref#method(params)
  // The params part can contain spaces, so we match non-paren chars first, then optional parens
  private val javadocSeeTagRegex = raw"@see\s+([^\s(]+(?:\([^)]*\))?)".r

  def getLinks(
      filePath: AbsolutePath
  ): util.List[DocumentLink] =
    buffers
      .get(filePath)
      .fold[util.List[DocumentLink]](util.Collections.emptyList()) { content =>
        val isJava = filePath.isJava
        val fileUri = filePath.toURI.toString
        content.linesIterator.zipWithIndex
          .flatMap { case (line, lineNo) =>
            linksForLine(line, lineNo, fileUri, isJava)
          }
          .toSeq
          .asJava
      }

  private def linksForLine(
      line: String,
      lineNo: Int,
      fileUri: String,
      isJava: Boolean,
  ): Seq[DocumentLink] = {
    val urlLinks =
      urlRegex.findAllMatchIn(line).map(makeUrlLink(lineNo, _)).toSeq
    val javadocLinks =
      if (isJava) javadocLinksForLine(line, lineNo, fileUri)
      else Seq.empty
    urlLinks ++ javadocLinks
  }

  private def javadocLinksForLine(
      line: String,
      lineNo: Int,
      fileUri: String,
  ): Seq[DocumentLink] = {
    val linkTagLinks = javadocLinkTagRegex
      .findAllMatchIn(line)
      .collect {
        case m if !m.group(1).startsWith("http") =>
          makeJavadocLink(lineNo, m.start(1), m.end(1), m.group(1), fileUri)
      }
      .toSeq

    val seeTagLinks = javadocSeeTagRegex
      .findAllMatchIn(line)
      .collect {
        case m
            if !m.group(1).startsWith("http") &&
              !m.group(1).startsWith("<") =>
          makeJavadocLink(lineNo, m.start(1), m.end(1), m.group(1), fileUri)
      }
      .toSeq

    linkTagLinks ++ seeTagLinks
  }

  private def makeUrlLink(
      lineNo: Int,
      m: scala.util.matching.Regex.Match,
  ): DocumentLink = {
    val url = trimTrailingPunct(m.matched)
    val link = new DocumentLink()
    link.setTarget(url)
    link.setTooltip(
      scala.util.Try(URLDecoder.decode(url, "UTF-8")).getOrElse(url)
    )
    link.setRange(
      new Range(
        new Position(lineNo, m.start),
        new Position(lineNo, m.start + url.length),
      )
    )
    link
  }

  /**
   * Creates a DocumentLink for a Javadoc class/member reference. The
   * {@code target} is intentionally left empty — the actual file URI is
   * resolved lazily in the {@code documentLink/resolve} phase (issue #8303).
   * The {@code data} field carries the information the resolve handler needs:
   * the symbol reference and the URI of the document that contains it.
   */
  private def makeJavadocLink(
      lineNo: Int,
      start: Int,
      end: Int,
      ref: String,
      fileUri: String,
  ): DocumentLink = {
    val data = new JsonObject()
    data.addProperty("symbol", ref)
    data.addProperty("uri", fileUri)
    val link = new DocumentLink()
    link.setTooltip(ref)
    link.setData(data)
    link.setRange(
      new Range(new Position(lineNo, start), new Position(lineNo, end))
    )
    link
  }

  /**
   * Strips trailing punctuation characters that are unlikely to be part of
   * an intentional URL. Handles balanced parentheses/brackets: a closing
   * bracket is only stripped when there is no matching opener in the URL.
   */
  private def trimTrailingPunct(url: String): String = {
    var end = url.length
    var continue = true
    while (continue && end > 0) {
      url.charAt(end - 1) match {
        case '.' | ',' | ';' | ':' | '!' | '?' | '>' | '\'' | '"' =>
          end -= 1
        case ')' if !url.substring(0, end - 1).contains('(') => end -= 1
        case ']' if !url.substring(0, end - 1).contains('[') => end -= 1
        case '}' if !url.substring(0, end - 1).contains('{') => end -= 1
        case _ => continue = false
      }
    }
    url.substring(0, end)
  }

  /**
   * Resolves a DocumentLink by setting its target URI. For URL links, the
   * target is already set. For Javadoc reference links, this method looks up
   * the symbol definition and sets the target to its location.
   */
  def resolve(link: DocumentLink): DocumentLink = {
    Option(link.getTarget) match {
      case Some(_) => link
      case None =>
        resolveJavadocLink(link)
        link
    }
  }

  private val importRegex = raw"import\s+([\w.]+);".r

  /**
   * Finds imports in the file that end with the given simple name.
   * Returns candidate SemanticDB symbols for those imports.
   */
  private def findImportedSymbols(
      simpleName: String,
      path: AbsolutePath,
      symbol: String,
  ): List[String] =
    buffers.get(path).toList.flatMap { content =>
      importRegex
        .findAllMatchIn(content)
        .map(_.group(1))
        .filter(imp => imp.endsWith(s".$simpleName"))
        .map(imp => (imp.replace('.', '/')).replace(simpleName, symbol))
        .toList
    }

  private def resolveJavadocLink(link: DocumentLink): Unit =
    for {
      data <- Option(link.getData).collect { case j: JsonElement =>
        j.getAsJsonObject
      }
      symbol <- Option(data.get("symbol")).map(_.getAsString)
      uri <- Option(data.get("uri")).map(_.getAsString)
    } {
      val path = uri.toAbsolutePath
      val symbolCandidates = javadocRefToSemanticdbSymbols(symbol, uri)

      val simpleName = extractSimpleName(symbol)
      val importedSymbols =
        findImportedSymbols(simpleName, path, symbol).flatMap(
          javadocRefToSemanticdbSymbols(_, uri)
        )
      val allCandidates = symbolCandidates ++ importedSymbols
      val locationsFromDefinition = for {
        provider <- definitionProvider
        locs <- allCandidates
          .map(sym => provider.fromSymbol(sym, Some(path)))
          .find(!_.isEmpty)
      } yield locs

      val locations = locationsFromDefinition.map(_.asScala).getOrElse(Nil)

      locations.foreach { loc =>
        link.setTarget(s"${loc.getUri}#L${loc.getRange.getStart.getLine + 1}")
      }
    }

  /**
   * Extracts the simple name from a Javadoc reference.
   * "java.util.Optional#empty" -> "Optional"
   * "ConnectorTableFunctionHandle" -> "ConnectorTableFunctionHandle"
   * "#myMethod" -> "myMethod"
   */
  private def extractSimpleName(ref: String): String = {
    val cleanRef = ref.replaceAll("\\(.*\\)", "")
    val classAndMember = cleanRef.split("#", 2)
    val classRef = classAndMember(0)
    if (classRef.isEmpty && classAndMember.length > 1) {
      classAndMember(1)
    } else {
      val parts = classRef.split("\\.")
      parts.lastOption.getOrElse(classRef)
    }
  }

  /**
   * Converts a Javadoc-style reference to candidate SemanticDB symbols.
   * Returns multiple candidates for simple class names to handle same-package references.
   *
   * For "ConnectorPageSourceProvider" in package "com.example":
   *   - First tries "com/example/ConnectorPageSourceProvider#" (same package)
   *   - Then falls back to "ConnectorPageSourceProvider#" (unqualified)
   *
   * For fully qualified "java.util.List":
   *   - Returns only "java/util/List#"
   */
  private def javadocRefToSemanticdbSymbols(
      ref: String,
      fileUri: String,
  ): List[String] = {
    val cleanRef = ref.replaceAll("\\(.*\\)", "")
    cleanRef.split("#", 2) match {
      case Array(classRef, memberRef) if classRef.nonEmpty =>
        val classSymbols = classRefToSymbols(classRef, fileUri)
        if (memberRef.nonEmpty)
          classSymbols.map(_ + memberRef + "().")
        else
          classSymbols
      case Array(classRef) =>
        classRefToSymbols(classRef, fileUri)
      case Array("", memberRef) =>
        List(resolveLocalMemberSymbol(memberRef, fileUri))
      case _ =>
        List(ref)
    }
  }

  /**
   * Converts a class reference to candidate SemanticDB symbols.
   * For simple names (no dots), tries same-package first.
   */
  private def classRefToSymbols(
      classRef: String,
      fileUri: String,
  ): List[String] = {
    val isSimpleName = !classRef.contains('.')
    if (isSimpleName) {
      val path = fileUri.toAbsolutePath
      val packagePrefix = buffers.get(path).map(findPackageName).getOrElse("")
      val samePackageSymbol =
        if (packagePrefix.nonEmpty) s"$packagePrefix/$classRef#"
        else s"$classRef#"
      val simpleSymbol = s"$classRef#"
      if (samePackageSymbol != simpleSymbol)
        List(samePackageSymbol, simpleSymbol)
      else
        List(simpleSymbol)
    } else {
      List(classRef.replace('.', '/') + "#")
    }
  }

  /**
   * Resolves a local member reference (e.g., "#myMethod") against the
   * containing class. Uses the file name to derive the class name.
   */
  private def resolveLocalMemberSymbol(
      memberRef: String,
      fileUri: String,
  ): String = {
    val path = fileUri.toAbsolutePath
    val className = path.filename.stripSuffix(".java")
    buffers.get(path) match {
      case Some(content) =>
        val packageName = findPackageName(content)
        val fullClass =
          if (packageName.nonEmpty) s"$packageName/$className"
          else className
        s"$fullClass#$memberRef()."
      case None => memberRef
    }
  }

  private val packageNameRegex = raw"package\s+([\w.]+)".r

  private def findPackageName(content: String): String =
    packageNameRegex
      .findFirstMatchIn(content)
      .map(_.group(1).replace('.', '/'))
      .getOrElse("")
}
