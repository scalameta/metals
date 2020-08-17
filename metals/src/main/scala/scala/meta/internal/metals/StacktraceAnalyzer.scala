package scala.meta.internal.metals

import java.io.FileWriter
import java.net.URLEncoder

import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.Location
import org.eclipse.{lsp4j => l}

class StacktraceAnalyzer(
    workspace: AbsolutePath,
    buffers: Buffers,
    definitionProvider: DefinitionProvider,
    icons: Icons,
    commandsInHtmlSupported: Boolean
) {

  def analyzeCommand(
      commandParams: l.ExecuteCommandParams
  ): Option[l.ExecuteCommandParams] = {
    parseJsonParams(commandParams)
      .flatMap(analyzeStackTrace)
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

  private def adjustPosition(lineNumber: Int, pos: l.Position): Unit = {
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
        s"${icons.findsuper} open",
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
      val pathFile = path.toFile
      val pathStr = pathFile.toString

      pathFile.createNewFile()
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
