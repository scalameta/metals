package tests

import scala.meta._
import scala.meta.internal.inputs._
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.Trees
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.{semanticdb => s}

/**
 * Assert that every identifier has a definition and every non-identifier has no definition.
 *
 * This test suite runs the full indexing pipeline:
 * - Project source files
 * - Project SemanticDB files
 * - Dependency sources
 * We then run through all Scala files in the input project and query the index at the position of
 * every token, including whitespace, delimiters and comment tokens.
 *
 * To keep the tests readable, we only include the filename of the definition position and leave it
 * to separate test suites to assert that the range positions are accurate.
 */
class DefinitionSuite extends DirectoryExpectSuite("definition") {
  override def testCases(): List[ExpectTestCase] = {
    val index = OnDemandSymbolIndex()
    // Step 1. Index project sources
    input.allFiles.foreach { source =>
      index.addSourceFile(source.file, Some(source.sourceDirectory))
    }
    // Step 2. Index dependency sources
    index.addSourceJar(JdkSources().get)
    input.dependencySources.entries.foreach { jar => index.addSourceJar(jar) }

    def hasKnownIssues(file: InputFile): Boolean = {
      val badlist = List(
        "ForComprehensions.scala" // local symbols in large for comprehensions cause problems
      )
      badlist.exists { filename => file.file.toNIO.endsWith(filename) }
    }
    input.scalaFiles.map { file =>
      ExpectTestCase(
        file,
        { () =>
          val input = file.input
          val tokens = Trees.defaultDialect(input).tokenize.get
          val sb = new StringBuilder
          tokens.foreach(token => {
            sb.append(token.syntax)
            val semanticdbPath = classpath.getSemanticdbPath(file.file)
            val textDocument = classpath.textDocument(file.file).get
            def localDefinition(symbol: Symbol): Option[s.Range] = {
              textDocument.occurrences.collectFirst {
                case occ
                    if occ.symbol == symbol.value && occ.role.isDefinition =>
                  occ.range.get
              }
            }
            def symbol(path: AbsolutePath, range: s.Range): Option[Symbol] = {
              for {
                document <- Semanticdbs.loadTextDocuments(path).documents
                occ <- document.occurrences.find(
                  _.range.contains(range)
                )
              } yield Symbol(occ.symbol)
            }.headOption
            val obtained = symbol(semanticdbPath, token.pos.toRange)
            def filename(path: AbsolutePath): String = {
              val name = path.toNIO.getFileName.toString
              if (name.endsWith(".semanticdb")) name.replace(".scala", "")
              else name
            }
            token match {
              case _: Token.Ident | _: Token.Interpolation.Id =>
                obtained match {
                  case Some(symbol) =>
                    if (symbol.isLocal) {
                      localDefinition(symbol) match {
                        case Some(_) =>
                          Semanticdbs.printSymbol(sb, filename(semanticdbPath))
                        case None =>
                          Semanticdbs.printSymbol(sb, "no local definition")
                      }
                    } else {
                      val definition = index.definition(symbol) match {
                        case Some(defn) =>
                          val fallback =
                            if (defn.querySymbol == defn.definitionSymbol) ""
                            else if (
                              defn.querySymbol.value.stripSuffix(".") ==
                                defn.definitionSymbol.value.stripSuffix("#")
                            ) {
                              // Ignore fallback from companion object to class.
                              ""
                            } else {
                              s" fallback to ${defn.definitionSymbol}"
                            }
                          filename(defn.path) + fallback
                        case None =>
                          if (shouldHaveDefinition(symbol.value)) {
                            if (!hasKnownIssues(file)) {
                              scribe.error(
                                token.pos.formatMessage(
                                  "error",
                                  s"missing definition for $symbol"
                                )
                              )
                            }
                            "<no file>"
                          } else {
                            ""
                          }
                      }
                      if (!symbol.isPackage && !definition.isEmpty) {
                        Semanticdbs.printSymbol(sb, definition)
                      }
                    }
                  case None =>
                    sb.append("/*<no symbol>*/")
                }
              case _ =>
                obtained match {
                  case Some(symbol) =>
                    Semanticdbs.printSymbol(sb, "unexpected: " + symbol)
                  case None => ()
                }
            }
          })
          val obtained = sb.toString()
          obtained
        }
      )
    }
  }

  def shouldHaveDefinition(symbol: String): Boolean = {
    !symbol.isPackage &&
    !symbol.startsWith("scala/Any#") &&
    !symbol.startsWith("scala/Nothing#") &&
    !symbol.startsWith("scala/Null#") &&
    !symbol.startsWith("scala/Singleton#") &&
    !symbol.startsWith("scala/AnyRef#") &&
    !symbol.startsWith("java/lang/Object#")
  }
}
