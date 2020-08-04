package scala.meta.internal.metals

import java.io.FileWriter
import java.net.URLEncoder
import java.util.Collections.singletonList

import scala.io.Source
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.Location
import org.eclipse.{lsp4j => l}

class StacktraceAnalyzer(
    workspace: AbsolutePath,
    definitionProvider: DefinitionProvider,
    gotoicon: String,
    commandsInHtmlSupported: Boolean
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
    val lineOpt = readStacktraceFileLine(path, position.getLine)
    lineOpt match {
      case Some(line) =>
        getSymbolLocationFromLine(line).toList.asJava
      case None =>
        java.util.Collections.emptyList()
    }
  }

  private def adjustPosition(lineNumber: Int, pos: l.Position): Unit = {
    pos.setLine(lineNumber)
    pos.setCharacter(0)
  }

  private def tryGetLineNumberFromStacktrace(line: String): Try[Int] = {
    Try(
      // number is 1-based, but editors are 0-based
      Integer.valueOf(
        line.substring(line.indexOf(":") + 1, line.indexOf(")"))
      ) - 1
    )
  }

  private def convert(symbol: String): String = {
    val components =
      symbol.split('.').map(_.replace("$", "").replace("<init>", "init")).toList
    val scope = components.dropRight(1)
    val method = components.last
    scope.mkString("/") ++ "." ++ method ++ "()."
  }

  def stacktraceLenses(content: List[String]): Seq[l.CodeLens] = {
    (for {
      (line, row) <- content.zipWithIndex
      if line.trim.startsWith("at ")
      location <- getSymbolLocationFromLine(line)
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
    if (commandsInHtmlSupported) {
      Some(makeHtmlCommandParams(stacktrace))
    } else {
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
      val location = new l.Location(pathStr, range)
      Some(makeGotoCommandParams(location))
    }
  }

  private def makeGotoCommandParams(
      location: Location
  ): l.ExecuteCommandParams = {
    new l.ExecuteCommandParams(
      ClientCommands.GotoLocation.id,
      List[Object](location, java.lang.Boolean.TRUE).asJava
    )
  }

  private def getSymbolLocationFromLine(line: String): Option[l.Location] = {
    val symbol = getSymbolFromLine(line)
    definitionProvider.fromSymbol(convert(symbol)).asScala.headOption.map {
      location =>
        val lineNumberOpt = tryGetLineNumberFromStacktrace(line)
        lineNumberOpt.foreach { lineNumber =>
          adjustPosition(lineNumber, location.getRange().getStart())
          adjustPosition(lineNumber, location.getRange().getEnd())
        }
        location
    }
  }

  private def getSymbolFromLine(line: String): String = {
    line.substring(line.indexOf("at ") + 3, line.indexOf("("))
  }

  private def makeHtmlCommandParams(
      stacktrace: String
  ): l.ExecuteCommandParams = {
    def htmlStack(builder: HtmlBuilder): Unit = {
      for (line <- stacktrace.split('\n')) {
        if (line.contains("at ")) {
          getSymbolLocationFromLine(line) match {
            case Some(location) =>
              builder
                .text("at ")
                .link(
                  gotoLocationUsingUri(
                    location.getUri,
                    location.getRange.getStart.getLine
                  ),
                  line.substring(line.indexOf("at ") + 3)
                )
            case None =>
              builder.raw(line)
          }
        } else {
          builder.raw(line)
        }
        builder.raw("<br>")
      }
    }

    val output = new HtmlBuilder()
      .element("h3")(_.text(s"Stacktrace"))
      .call(htmlStack)
      .render
    new l.ExecuteCommandParams(
      "metals-show-stacktrace",
      List[Object](output).asJava
    )
  }

  private def gotoLocationUsingUri(uri: String, line: Int): String = {
    val param = s"""["${uri}",${line},true]"""
    s"command:metals.goto-path-uri?${URLEncoder.encode(param)}"
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
