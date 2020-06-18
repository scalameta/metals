package scala.meta.internal.mtags

import scala.meta.internal.mtags
import scala.meta.io.AbsolutePath

/**
 * An index to lookup the definition of global symbols.
 *
 * Only indexes plain Scala and Java source files, no compilation needed.
 */
trait GlobalSymbolIndex {

  /**
   * Lookup the definition of a global symbol. *
   * Returns the path of the file that defines the symbol but does not include
   * the exact position of the definition. Computing the range position of the
   * definition is not handled by this method, it is left for the user and can
   * be done using the mtags module.
   *
   * @param symbol a global SemanticDB symbol. For comprehensive documentation
   *               of how a symbol is formatted consule the specification:
   *               https://scalameta.org/docs/semanticdb/specification.html#symbol
   *
   *  Examples: {{{
   *    "scala/Option#"                 // Option class
   *    "scala/Option."                 // Option companion object
   *    "scala/Option#.get()."          // `get` method for Option
   *    "scala/Predef.String#"          // String type alias in Predef
   *    "java/lang/String#format(+1)."  // Static `format` method for strings
   *    "java/util/Map#Entry#"          // Static inner interface.
   *  }}}
   * @return the definition of the symbol, if any.
   */
  def definition(symbol: mtags.Symbol): Option[SymbolDefinition]

  /**
   * Add an individual Java or Scala source file to the index.
   *
   * @param file the absolute path to the source file, can be a path
   *            on disk or inside of a jar/zip file.
   * @param sourceDirectory the enclosing project source directory if
   *                        file is on disk, used to relativize `file`.
   *                        Can be None if file is inside a zip file
   *                        assuming the file path is already relativized
   *                        by that point.
   * @throws Exception in case of problems processing the source file
   *                   such as tokenization failure due to an unclosed
   *                   literal.
   */
  def addSourceFile(
      file: AbsolutePath,
      sourceDirectory: Option[AbsolutePath]
  ): Unit

  /**
   * Index a jar or zip file containing Scala and Java source files.
   *
   * Published artifacts typically have accompanying sources.jar files that
   * include the project sources.
   *
   * Sources of a published library can be fetched from the command-line with
   * coursier using the --sources flag:
   * {{{
   *   $ coursier fetch com.thoughtworks.qdox:qdox:2.0-M9 --sources
   *   $HOME/.coursier/cache/v1/https/repo1.maven.org/maven2/com/thoughtworks/qdox/qdox/2.0-M9/qdox-2.0-M9-sources.jar.coursier/cache/v1/https/repo1.maven.org/maven2/com/thoughtworks/qdox/qdox/2.0-M9/qdox-2.0-M9-sources.jar
   * }}}
   *
   * Sources can be fetched from an sbt build through the `updateClassifiers` task:
   * {{{
   *   val sourceJars = for {
   *     configurationReport <- updateClassifiers.in(input).value.configurations
   *     moduleReport <- configurationReport.modules
   *     (artifact, file) <- moduleReport.artifacts
   *     if artifact.classifier.contains("sources")
   *   } yield file
   * }}}
   *
   * @param jar the path to a single jar or zip file.
   * @throws Exception in case of problems processing the source file
   *                   such as tokenization failure due to an unclosed
   *                   literal.
   */
  def addSourceJar(jar: AbsolutePath): Unit

  /**
   * The same as `addSourceJar` except for directories */
  def addSourceDirectory(dir: AbsolutePath): Unit

}

case class SymbolDefinition(
    querySymbol: Symbol,
    definitionSymbol: Symbol,
    path: AbsolutePath
)
