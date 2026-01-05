package scala.meta.internal.metals

import java.io.FileWriter

import scala.annotation.tailrec
import scala.util.Try
import scala.util.control.NonFatal
import scala.util.matching.Regex

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.StacktraceAnalyzer._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Location
import org.eclipse.{lsp4j => l}

class StacktraceAnalyzer(
    workspace: AbsolutePath,
    buffers: Buffers,
    definitionProvider: DefinitionProvider,
    icons: Icons,
    commandInHtmlFormat: Option[CommandHTMLFormat],
) {

  def analyzeCommand(
      stacktrace: String
  ): Option[l.ExecuteCommandParams] = {
    analyzeStackTrace(stacktrace)
  }

  def resolveStacktraceLocationCommand(
      stacktraceLine: String
  ): Option[l.Location] = {
    workspaceFileLocationFromLine(stacktraceLine)
  }

  def isStackTraceFile(path: AbsolutePath): Boolean =
    path == workspace.resolve(Directories.stacktrace)

  def stacktraceLenses(path: AbsolutePath): Seq[l.CodeLens] = {
    readStacktraceFile(path)
      .map(stacktraceLenses)
      .getOrElse(Seq.empty)
  }

  private def readStacktraceFile(path: AbsolutePath): Option[List[String]] = {
    buffers.get(path).map(_.split('\n').toList)
  }

  private def setToLineStart(lineNumber: Int, pos: l.Position): Unit = {
    pos.setLine(lineNumber)
    pos.setCharacter(0)
  }

  private def tryGetLineNumberFromStacktrace(line: String): Try[Int] = {
    Try(
      // stacktrace line numbers are 1-based but line numbers in LSP protocol are 0-based.
      // line number from stacktrace will be used in LSP message that's why "-1"
      Integer.valueOf(
        line.substring(line.indexOf(":") + 1, line.indexOf(")"))
      ) - 1
    )
  }

  def stacktraceLenses(content: List[String]): Seq[l.CodeLens] = {
    (for {
      (line, row) <- content.zipWithIndex
      location <- fileLocationFromLine(line)
      range = new l.Range(new l.Position(row, 0), new l.Position(row, 0))
    } yield makeGotoLocationCodeLens(location, range)).toSeq
  }

  def workspaceFileLocationFromLine(line: String): Option[l.Location] = {
    fileLocationFromLine(line).filter { location =>
      val path = location.getUri().toAbsolutePath
      !path.isJarFileSystem && !path.isInReadonlyDirectory(workspace)
    }
  }

  def fileLocationFromLine(line: String): Option[l.Location] = {
    def findLocationForSymbol(s: String): Option[Location] = try {
      definitionProvider.fromSymbol(s, None).asScala.headOption
    } catch {
      case NonFatal(_) => None
    }

    for {
      symbol <- symbolFromLine(line)
      location <- toToplevelSymbol(symbol)
        .collectFirst(scala.Function.unlift(findLocationForSymbol))
    } yield trySetLineFromStacktrace(location, line)

  }

  private def makeGotoLocationCodeLens(
      location: l.Location,
      range: l.Range,
  ): l.CodeLens = {
    val command = ServerCommands.GotoPosition.toLsp(location)
    command.setTitle(s"${icons.findsuper} open")
    new l.CodeLens(
      range,
      command,
      null,
    )
  }

  private def analyzeStackTrace(
      stacktrace: String
  ): Option[l.ExecuteCommandParams] =
    commandInHtmlFormat match {
      case Some(format) => Some(makeHtmlCommandParams(stacktrace, format))
      case None =>
        val path = workspace.resolve(Directories.stacktrace)
        val pathFile = path.toFile
        val pathStr = pathFile.toString

        pathFile.createNewFile()
        val fw = new FileWriter(pathStr)
        try {
          fw.write(s"/*\n$stacktrace\n*/")
        } finally {
          fw.close()
        }
        val fileStartPos = new l.Position(0, 0)
        val range = new l.Range(fileStartPos, fileStartPos)
        val stackTraceLocation = new l.Location(path.toURI.toString(), range)
        Some(makeGotoCommandParams(stackTraceLocation))
    }

  private def makeGotoCommandParams(
      location: Location
  ): l.ExecuteCommandParams = {
    ClientCommands.GotoLocation.toExecuteCommandParams(
      ClientCommands.WindowLocation(
        location.getUri(),
        location.getRange(),
        otherWindow = true,
      )
    )
  }

  private def trySetLineFromStacktrace(
      location: Location,
      line: String,
  ): Location = {
    val lineNumberOpt = tryGetLineNumberFromStacktrace(line)
    lineNumberOpt.foreach { lineNumber =>
      setToLineStart(lineNumber, location.getRange().getStart())
      setToLineStart(lineNumber, location.getRange().getEnd())
    }
    location
  }

  private def symbolFromLine(line: String): Option[String] = Try {
    val trimmed = line.substring(line.indexOf("at ") + 3, line.indexOf("("))
    trimmed match {
      case catEffectsStacktrace(symbol) => symbol
      case _ => trimmed
    }
  }.toOption

  private def makeHtmlCommandParams(
      stacktrace: String,
      format: CommandHTMLFormat,
  ): l.ExecuteCommandParams = {
    def htmlStack(builder: HtmlBuilder): Unit = {
      for (line <- stacktrace.split('\n')) {
        fileLocationFromLine(line) match {
          case Some(location) =>
            builder
              .text("at ")
              .link(
                gotoLocationUsingUri(
                  location.getUri,
                  location.getRange.getStart.getLine,
                  format,
                ),
                line.substring(line.indexOf("at ") + 3),
              )
          case None =>
            builder.raw(line)
        }
        builder.raw("<br>")
      }
    }

    val output = new HtmlBuilder()
      .element("h3")(_.text(s"Stacktrace"))
      .call(htmlStack)
      .render

    ClientCommands.ShowStacktrace.toExecuteCommandParams(output)
  }

  private def gotoLocationUsingUri(
      uri: String,
      line: Int,
      format: CommandHTMLFormat,
  ): String = {
    val pos = new l.Position(line, 0)
    ClientCommands.GotoLocation.toCommandLink(
      ClientCommands.WindowLocation(
        uri,
        new l.Range(pos, pos),
        otherWindow = true,
      ),
      format,
    )
  }
}

object StacktraceAnalyzer {

  /**
   * Match on: 'apply @ a.Main$.<clinit>'' OR 'run$ @ a.Main$.run' and etc.
   * '[\w|\$]+ @ ' matches sequences like 'apply @ ' or 'run$ @ '.
   * Capture group captures relevant part like 'a.Main$.run'.
   */
  final val catEffectsStacktrace: Regex = """[\w|\$]+ @ (.+)""".r

  private def isSymbolicOperator(s: String): Boolean = {
    if (s == null || s.isEmpty) return false

    def isOperatorPart(ch: Char): Boolean =
      (ch: @scala.annotation.switch) match {
        case '~' | '!' | '@' | '#' | '%' | '^' | '*' | '+' | '-' | '<' | '>' |
            '?' | ':' | '=' | '&' | '|' | '/' =>
          true
        case '\\' =>
          true
        case _ =>
          val tpe = Character.getType(ch)
          tpe == Character.MATH_SYMBOL.toInt || tpe == Character.OTHER_SYMBOL.toInt
      }

    @tailrec
    def iter(idx: Int, nonEmpty: Boolean): Boolean = {
      val ch = s(idx)
      if (ch == '_') nonEmpty || idx > 0 && iter(idx - 1, nonEmpty = false)
      else isOperatorPart(ch) && (idx == 0 || iter(idx - 1, nonEmpty = true))
    }

    val len = s.length
    len == 0 || iter(len - 1, nonEmpty = false)
  }

  private def wrapIfSymbolic(name: String): String = {
    if (name == null || name.isEmpty || name.startsWith("`")) name
    else if (isSymbolicOperator(name)) s"`$name`"
    else if (
      name.endsWith("$") && name.length > 1 && isSymbolicOperator(
        name.dropRight(1)
      )
    ) {
      // Handle symbolic operators that end with $ (object form)
      s"`${name.dropRight(1)}`$$"
    } else name
  }

  def toToplevelSymbol(symbolIn: String): List[String] = {
    if (symbolIn == null || symbolIn.isEmpty) {
      return Nil
    }

    val modulePos = symbolIn.indexOf("/")
    val symbolPart =
      if (modulePos > -1) symbolIn.substring(modulePos + 1) else symbolIn

    val splitParts = symbolPart.split('.')
    if (splitParts.isEmpty) {
      return Nil
    }

    val parts = if (splitParts.length == 1) {
      splitParts.map(wrapIfSymbolic)
    } else {
      splitParts.init.map(wrapIfSymbolic)
    }

    val symbol = parts.mkString("/")

    if (symbol.isEmpty) {
      return Nil
    }

    /* Symbol containing `$package$` is a toplevel method and we only need to
     * find any method contained in the same file even if overloaded
     */
    val symbolToFind = if (symbol.contains("$package$")) {
      symbolIn.split("\\$package\\$") match {
        case Array(filePath, symbol) =>
          val re = filePath.replace('.', '/') + "$package" + symbol
          List(re + "().")
        case _ =>
          Nil
      }
    } else if (symbol.contains('$')) {
      // if $ is only at the end we know it is object => append '.'
      // if $ is in the middle we don't know, we will try to treat it as class/trait first
      // but in case nothing is found we will retry as object
      val dollarPos = symbol.indexOf('$')
      val s = symbol.substring(0, dollarPos)
      if (symbol.size - 1 == dollarPos) {
        List(s :+ '.')
      } else {
        List(s :+ '#', s :+ '.')
      }
    } else {
      List(symbol :+ '#')
    }
    symbolToFind.map {
      // empty package needs to be added if no package is present
      case sym if !sym.contains("/") => "_empty_/" + sym
      case sym => sym
    }
  }

}
