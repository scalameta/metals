package scala.meta.internal.pc.classpath

import scala.reflect.internal.util.BatchSourceFile
import scala.reflect.internal.util.Position
import scala.reflect.io.AbstractFile
import scala.reflect.io.Path
import scala.tools.nsc.Global
import scala.tools.nsc.LogicalPackage
import scala.tools.nsc.ParsedLogicalPackage
import scala.tools.nsc.util.ClassPath

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A compiler component that adds support for parsing Scala and Java source files and finding out
 * the logical package structure of the whole source path.
 *
 * The result maps source roots to a package hierarchy containing source files and other packages.
 */
trait LogicalPackagesProvider extends Global {
  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  lazy val sourceRoots: Seq[BatchSourceFile] =
    allSources(settings.sourcepath.value).map(getSourceFile)

  /**
   * The logical package structure for the compiler sourcepath (the sourcepath is retrieved form the regular
   * Scala compiler Settings).
   */
  lazy val rootPackage: LogicalPackage = parseSourcePath()

  def parseSourcePath(): LogicalPackage = {
    // this is going to be the root package where all top level symbols coming from source files
    // will be added
    val pkg: ParsedLogicalPackage = newPackage()
    logger.debug(s"Parsing ${sourceRoots.size} files.")
    for {
      batchFile <- sourceRoots
    } {
      try {
        val unit = new CompilationUnit(batchFile)
        currentRun.currentUnit = unit

        val unitTree =
          if (batchFile.file.name.endsWith(".scala"))
            new MacroAwareOutlineParser(batchFile).parse()
          else
            new syntaxAnalyzer.JavaUnitParser(unit).parse()

        val traverser = new OutlineTraverser(batchFile.file.absolute.path, pkg)
        traverser(unitTree)
      } catch {
        case e: syntaxAnalyzer.MalformedInput =>
          logger.error(
            Position.formatMessage(
              batchFile.position(e.offset),
              s"malformedInput: ${e.msg}",
              shortenFile = false
            )
          )
      }
    }

    pkg
  }

  private def sourcesIn(
      dir: AbstractFile,
      extension: String*
  ): Seq[AbstractFile] = {
    dir.iterator.toSeq.flatMap { file =>
      if (file.isDirectory) sourcesIn(file, extension: _*)
      else if (extension.exists(ext => file.name.endsWith(s".$ext"))) Seq(file)
      else Seq.empty[AbstractFile]
    }
  }

  /**
   * Return all Scala sources for each individual entry in the sourcepath string
   */
  private def allSources(srcPath: String): Seq[AbstractFile] = {
    val entries = ClassPath.split(srcPath).map(Path(_))
    // getDirectory may return null for non-existent paths, so filter out via Option
    val rootDirs = entries.flatMap(f => Option(AbstractFile.getDirectory(f)))
    val rootFiles = for {
      e <- entries
      f <- Option(AbstractFile.getFile(e))
      if f.name.endsWith(".scala") || f.name.endsWith(".java")
    } yield f
    rootFiles ++ rootDirs.flatMap(dir => sourcesIn(dir, "scala", "java"))
  }

  private def newPackage(): ParsedLogicalPackage =
    new ParsedLogicalPackage("", None)

  private class OutlineTraverser(
      filePath: String,
      rootPackage: ParsedLogicalPackage
  ) extends Traverser {
    private var currentPackage = rootPackage

    def enterPackage(pkg: Tree): ParsedLogicalPackage = pkg match {

      case Select(pre, name) =>
        val p = enterPackage(pre)
        p.enterPackage(name.toString)

      case Ident(name) =>
        if (name == nme.EMPTY_PACKAGE_NAME)
          currentPackage
        else
          currentPackage.enterPackage(name.toString)

      case _ =>
        sys.error(s"Unknown package tree element $pkg")
    }

    def inPackage(pkg: Tree)(body: => Unit): Unit = {
      val oldPackage = currentPackage
      currentPackage = enterPackage(pkg)
      body
      currentPackage = oldPackage
    }

    override def traverse(tree: Tree): Unit = tree match {
      case PackageDef(pkg, body) =>
        inPackage(pkg) { body foreach traverse }

      case ModuleDef(_, name, _) if name == nme.PACKAGEkw =>
        currentPackage.enterSource(filePath)

      case ClassDef(_, _, _, _) | ModuleDef(_, _, _) =>
        currentPackage.enterSource(filePath)

      case _ =>
    }
  }

  private class MacroAwareOutlineParser(source: BatchSourceFile)
      extends syntaxAnalyzer.OutlineParser(source) {
    import scala.tools.nsc.ast.parser.Tokens._

    override def skipBraces[T](body: T): T = {
      accept(LBRACE)
      var openBraces = 1
      // we need to keep track of the last token here because the scanner `prev.token` is not valid
      // except in special conditions (within a `peekAhead` call). Already bitten once by this one.
      var prevToken = in.token
      while (in.token != EOF && openBraces > 0) {
        if (in.token == XMLSTART) {
          if (prevToken == CASE) xmlLiteralPattern() else xmlLiteral()
        } else {
          if (in.token == LBRACE) openBraces += 1
          else if (in.token == RBRACE) openBraces -= 1

          prevToken = in.token
          // The macro keyword is peculiar: it is allowed after `=`
          // otherwise it is treated as an identifier but an error is issued
          // This error is emitted by the *scanner*, so we need to let it know
          // when `macro` is allowed, just like the regular scanner does
          if (in.token == EQUALS)
            in.nextTokenAllow(nme.MACROkw)
          else
            in.nextToken()
        }
      }
      body
    }
  }
}
