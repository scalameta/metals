package scala.meta.internal.metals

import java.util

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.xml.Node

import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.{inputs => m}

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.ToolFactory
import org.eclipse.jdt.core.formatter.CodeFormatter
import org.eclipse.jdt.internal.formatter.DefaultCodeFormatterOptions
import org.eclipse.jface.text.BadLocationException
import org.eclipse.jface.text.Document
import org.eclipse.text.edits.MalformedTreeException
import org.eclipse.{lsp4j => l}

final class JavaFormattingProvider(
    buffers: Buffers,
    userConfig: () => UserConfiguration,
    buildTargets: BuildTargets,
)(implicit
    ec: ExecutionContext
) {
  private def decodeProfile(node: Node): Map[String, String] = {
    val settings = for {
      child <- node.child
      id <- child.attribute("id")
      value <- child.attribute("value")
    } yield (id.text, value.text)
    settings.toMap
  }

  private def parseEclipseFormatFile(
      text: String,
      profileName: Option[String],
  ): Try[Map[String, String]] = {
    import scala.xml.XML
    Try {
      val node = XML.loadString(text)
      val profiles = node.child.filter(child => {
        val foundKind = child.attributes
          .get("kind")
          .map(_.text)
          .contains("CodeFormatterProfile")
        val foundProfile = profileName.isEmpty || child.attributes
          .get("name")
          .map(_.text) == profileName

        foundKind && foundProfile
      })
      if (profiles.isEmpty) {
        if (profileName.isEmpty)
          scribe.error("No Java formatting profiles found")
        else
          scribe.error(s"Java formatting profile ${profileName.get} not found")
        defaultSettings
      } else
        decodeProfile(profiles.head)
    }
  }

  private lazy val defaultSettings =
    DefaultCodeFormatterOptions.getEclipseDefaultSettings.getMap.asScala.toMap

  private def loadEclipseFormatConfig: Map[String, String] = {
    userConfig().javaFormatConfig
      .map(javaFormatConfig => {
        val eclipseFormatFile = javaFormatConfig.eclipseFormatConfigPath
        if (eclipseFormatFile.exists) {
          val text = eclipseFormatFile.toInputFromBuffers(buffers).text
          parseEclipseFormatFile(
            text,
            javaFormatConfig.eclipseFormatProfile,
          ) match {
            case Failure(e) =>
              scribe.error(
                s"Failed to parse $eclipseFormatFile. Using default formatting",
                e,
              )
              defaultSettings
            case Success(values) =>
              values
          }
        } else {
          scribe.warn(
            s"$eclipseFormatFile not found.  Using default java formatting"
          )
          defaultSettings
        }
      })
      .getOrElse(defaultSettings)
  }

  def format(
      params: l.DocumentFormattingParams
  ): Future[util.List[l.TextEdit]] = {
    Future {
      val options = params.getOptions
      val path = params.getTextDocument.getUri.toAbsolutePath
      val input = path.toInputFromBuffers(buffers)
      runFormat(path, input, options, fromLSP(input)).asJava
    }
  }

  private def fromLSP(input: Input): Position.Range =
    m.Position.Range(input, 0, input.chars.length)

  def format(
      params: l.DocumentRangeFormattingParams
  ): util.List[l.TextEdit] = {
    val options = params.getOptions
    val range = params.getRange
    val path = params.getTextDocument.getUri.toAbsolutePath
    val input = path.toInputFromBuffers(buffers)
    range.toMeta(input) match {
      case Some(rng) =>
        runFormat(path, input, options, rng).asJava
      case None =>
        scribe.debug(s"range $range was not found in $path")
        Nil.asJava
    }
  }

  def format(): util.List[l.TextEdit] = java.util.Collections.emptyList()

  private def runFormat(
      path: AbsolutePath,
      input: Input,
      formattingOptions: l.FormattingOptions,
      range: m.Position,
  ): List[l.TextEdit] = {
    // if source/target/compliance versions aren't defined by the user then fallback on the build target info
    var options = loadEclipseFormatConfig
    if (
      !options.contains(JavaCore.COMPILER_SOURCE) ||
      !options.contains(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM) ||
      !options.contains(JavaCore.COMPILER_COMPLIANCE)
    ) {

      val version = for {
        targetID <- buildTargets.sourceBuildTargets(path).toList.flatten
        java <- buildTargets.javaTarget(targetID)
        sourceVersion <- java.sourceVersion
        targetVersion <- java.targetVersion
      } yield ((sourceVersion, targetVersion))

      version.headOption
        .foreach(version => {
          val (sourceVersion, targetVersion) = version
          val complianceVersion =
            if (sourceVersion.toDouble > targetVersion.toDouble) sourceVersion
            else targetVersion

          if (!options.contains(JavaCore.COMPILER_SOURCE))
            options += JavaCore.COMPILER_SOURCE -> sourceVersion
          if (!options.contains(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM))
            options += JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM -> targetVersion
          if (!options.contains(JavaCore.COMPILER_COMPLIANCE))
            options += JavaCore.COMPILER_COMPLIANCE -> complianceVersion
        })
    }

    if (!options.contains(JavaCore.FORMATTER_TAB_SIZE))
      Option(formattingOptions.getTabSize).foreach(f =>
        options += JavaCore.FORMATTER_TAB_SIZE -> f.toString
      )
    if (!options.contains(JavaCore.FORMATTER_TAB_CHAR))
      options += JavaCore.FORMATTER_TAB_CHAR -> {
        if (formattingOptions.isInsertSpaces) JavaCore.SPACE else JavaCore.TAB
      }

    val codeFormatter = ToolFactory.createCodeFormatter(options.asJava)

    val kind =
      CodeFormatter.F_INCLUDE_COMMENTS | CodeFormatter.K_COMPILATION_UNIT
    val code = input.text
    val doc = new Document(code)
    val codeOffset = range.start
    val codeLength = range.end - range.start
    val indentationLevel = 0
    val lineSeparator: String = null
    val textEdit = codeFormatter.format(
      kind,
      code,
      codeOffset,
      codeLength,
      indentationLevel,
      lineSeparator,
    )
    val formatted =
      try {
        textEdit.apply(doc)
        doc.get
      } catch {
        case e: MalformedTreeException =>
          scribe.error("Unable to format", e)
          code
        case e: BadLocationException =>
          scribe.error("Unable to format", e)
          code
      }

    val fullDocumentRange = fromLSP(input).toLsp
    if (formatted != code)
      List(new l.TextEdit(fullDocumentRange, formatted))
    else
      Nil
  }
}
