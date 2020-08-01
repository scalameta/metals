package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._
import org.eclipse.{lsp4j => l}
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.Location
import scala.meta.io.AbsolutePath
import java.io.FileWriter
import scala.io.Source

import java.util.Collections.singletonList
import scala.util.Try

class StacktraceAnalyzer(
    workspace: AbsolutePath,
    definitionProvider: DefinitionProvider,
    gotoicon: String
) {

  def analyzeCommand(
      commandParams: l.ExecuteCommandParams
  ): Option[l.ExecuteCommandParams] = {
    parseJsonParams(commandParams)
      .flatMap(analyzeStackTrace)
  }

  def matches(path: AbsolutePath): Boolean =
    path == workspace.resolve(Directories.stacktrace)

  def highlight(
      path: AbsolutePath,
      lineNumber: Int
  ): java.util.List[l.DocumentHighlight] = {
    val lineOpt = readStacktraceFileLine(path, lineNumber)
    lineOpt match {
      case Some(line) =>
        val from = new l.Position(lineNumber, line.indexOf("at ") + 3)
        val to = new l.Position(lineNumber, line.indexOf("("))
        val range = new l.Range(from, to)
        singletonList(
          new l.DocumentHighlight(range, l.DocumentHighlightKind.Read)
        )
      case None =>
        java.util.Collections.emptyList()
    }
  }

  def stacktraceLenses(path: AbsolutePath): Seq[l.CodeLens] = {
    stacktraceLenses(readStacktraceFile(path))
  }

  private def readStacktraceFileLine(
      path: AbsolutePath,
      lineNumber: Int
  ): Option[String] =
    readStacktraceFile(path).drop(lineNumber).headOption

  private def readStacktraceFile(path: AbsolutePath): List[String] = {
    Source.fromFile(path.toString).getLines().toList
  }

  def definition(
      path: AbsolutePath,
      position: l.Position
  ): java.util.List[Location] = {
    val lineOpt = readStacktraceFileLine(path, position.getLine())
    lineOpt match {
      case Some(line) =>
        val symbol = getSymbolFromLine(line)
        val lineNumber = tryGetLineNumberFromStacktrace(line)
        val result = definitionProvider.fromSymbol(convert(symbol))
        result.forEach { r =>
          lineNumber.foreach { ln =>
            adjustPosition(ln, r.getRange().getStart())
            adjustPosition(ln, r.getRange().getEnd())
          }
        }
        result
      case None =>
        java.util.Collections.emptyList()
    }
  }

  private def adjustPosition(lineNumber: Int, pos: l.Position): Unit = {
    pos.setLine(lineNumber - 1)
    pos.setCharacter(0)
  }

  private def tryGetLineNumberFromStacktrace(line: String): Try[Int] = {
    Try(
      Integer.valueOf(line.substring(line.indexOf(":") + 1, line.indexOf(")")))
    )
  }

  private def convert(symbol: String): String = {
    val components =
      symbol.split('.').map(_.replace("$", "").replace("<init>", "init")).toList
    val scope = components.dropRight(1)
    val method = components.last
    scope.mkString("/") ++ "." ++ method ++ "()."
  }

  private def getSymbolFromLine(line: String): String = {
    line.substring(line.indexOf("at ") + 3, line.indexOf("("))
  }

  def stacktraceLenses(content: List[String]): Seq[l.CodeLens] = {
    (for {
      (line, row) <- content.zipWithIndex
      if line.trim.startsWith("at ")
      symbol = getSymbolFromLine(line)
      location <-
        definitionProvider.fromSymbol(convert(symbol)).asScala.headOption
      lineNumber = tryGetLineNumberFromStacktrace(line)
      _ = lineNumber.foreach { ln =>
        adjustPosition(ln, location.getRange().getStart())
        adjustPosition(ln, location.getRange().getEnd())
      }
      range = new l.Range(new l.Position(row, 0), new l.Position(row, 0))
    } yield makeGotoLocationCodeLens(location, range)).toSeq
  }

  private def makeGotoLocationCodeLens(
      location: l.Location,
      range: l.Range
  ): l.CodeLens = {
    new l.CodeLens(
      range,
      new l.Command(
        s"$gotoicon open",
        ServerCommands.GotoLocationForPosition.id,
        List[Object](location: Object, java.lang.Boolean.TRUE).asJava
      ),
      null
    )
  }

  private def analyzeStackTrace(
      stacktrace: String
  ): Option[l.ExecuteCommandParams] = {
    val path = workspace.resolve(Directories.stacktrace)
    val pathStr = path.toFile.toString

    path.toFile.createNewFile()
    val fw = new FileWriter(pathStr)
    try {
      fw.write(stacktrace)
    } finally {
      fw.close()
    }
    val pos = new l.Position(0, 0)
    val range = new l.Range(pos, pos)
    Some(makeCommandParams(new Location(pathStr, range)))
  }

  private def makeCommandParams(location: Location): l.ExecuteCommandParams = {
    new l.ExecuteCommandParams(
      ClientCommands.GotoLocation.id,
      List[Object](location, java.lang.Boolean.TRUE).asJava
    )
  }

  private def parseJsonParams(
      commandParams: l.ExecuteCommandParams
  ): Option[String] = {
    for {
      args <- Option(commandParams.getArguments)
      argObject <- args.asScala.headOption
      arg = argObject.asInstanceOf[JsonPrimitive]
      if arg.isString()
      stacktrace = arg.getAsString()
    } yield stacktrace
  }
}
