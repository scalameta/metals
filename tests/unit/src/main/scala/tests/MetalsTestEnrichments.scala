package tests

import java.nio.file.Files

import scala.collection.mutable.ArrayBuffer
import scala.{meta => m}

import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.Memory
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.internal.metals.SemanticdbDefinition
import scala.meta.internal.metals.WorkspaceSources
import scala.meta.internal.metals.WorkspaceSymbolInformation
import scala.meta.internal.metals.WorkspaceSymbolProvider
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetCapabilities
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaBuildTarget
import ch.epfl.scala.bsp4j.ScalaPlatform
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import ch.epfl.scala.bsp4j.ScalacOptionsResult
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult
import com.google.gson.Gson
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.{lsp4j => l}

/**
 *  Equivalent to scala.meta.internal.metals.MetalsEnrichments
 *  but only for tests
 */
object MetalsTestEnrichments {

  implicit class XtensionTestAbsolutePath(path: AbsolutePath) {
    def text: String = Files.readAllLines(path.toNIO).asScala.mkString("\n")
  }

  implicit class XtensionTestClasspath(classpath: Classpath) {
    def bytesSize: String = {
      val bytes = classpath.entries.foldLeft(0L) {
        case (a, b) =>
          a + Files.size(b.toNIO)
      }
      Memory.approx(bytes)
    }
  }
  implicit class XtensionTestBuildTargets(wsp: WorkspaceSymbolProvider) {
    def indexWorkspace(): Unit = {
      val files = new WorkspaceSources(wsp.workspace)
      for {
        source <- files.all
        if source.isScalaOrJava
      } {
        val input = source.toInput
        val symbols = ArrayBuffer.empty[WorkspaceSymbolInformation]
        SemanticdbDefinition.foreach(input) {
          case defn @ SemanticdbDefinition(info, _, _) =>
            if (WorkspaceSymbolProvider.isRelevantKind(info.kind)) {
              symbols += defn.toCached
            }
        }
        wsp.didChange(source, symbols)
      }
    }
    def indexLibraries(libraries: Seq[Library]): Unit = {
      JdkSources(None).foreach { zip => wsp.index.addSourceJar(zip) }
      libraries.foreach(
        _.sources.entries.foreach(s => wsp.index.addSourceJar(s))
      )
      val bti = new BuildTargetIdentifier("workspace")
      val buildTarget = new BuildTarget(
        bti,
        Nil.asJava,
        Nil.asJava,
        Nil.asJava,
        new BuildTargetCapabilities(true, true, true)
      )
      val scalaTarget = new ScalaBuildTarget(
        "org.scala-lang",
        BuildInfo.scalaVersion,
        BuildInfo.scalaVersion.split('.').take(2).mkString("."),
        ScalaPlatform.JVM,
        Nil.asJava
      )
      val gson = new Gson
      val data = gson.toJsonTree(scalaTarget)
      buildTarget.setData(data)
      val result = new WorkspaceBuildTargetsResult(List(buildTarget).asJava)
      wsp.buildTargets.addWorkspaceBuildTargets(result)
      val item = new ScalacOptionsItem(
        bti,
        Nil.asJava,
        libraries.flatMap(_.classpath.entries).map(_.toURI.toString).asJava,
        ""
      )
      wsp.buildTargets.addScalacOptions(
        new ScalacOptionsResult(List(item).asJava)
      )
    }
  }
  implicit class XtensionTestLspRange(range: l.Range) {
    def formatMessage(
        severity: String,
        message: String,
        input: m.Input
    ): String = {
      try {
        val start = range.getStart
        val end = range.getEnd
        val pos = m.Position.Range(
          input,
          start.getLine,
          start.getCharacter,
          end.getLine,
          end.getCharacter
        )
        pos.formatMessage(severity, message)
      } catch {
        case e: IllegalArgumentException =>
          val result =
            s"${range.getStart.getLine}:${range.getStart.getCharacter} ${message}"
          scribe.error(result, e)
          result
      }
    }

  }
  implicit class XtensionTestDiagnostic(diag: l.Diagnostic) {
    def formatMessage(input: m.Input): String = {
      diag.getRange.formatMessage(
        diag.getSeverity.toString.toLowerCase(),
        diag.getMessage,
        input
      )
    }
  }
  implicit class XtensionMetaToken(token: m.Token) {
    def isIdentifier: Boolean =
      token match {
        case _: m.Token.Ident | _: m.Token.Interpolation.Id => true
        case _ => false
      }
    def toPositionParams(
        identifier: TextDocumentIdentifier
    ): TextDocumentPositionParams = {
      val range = token.pos.toLSP
      val start = range.getStart
      new TextDocumentPositionParams(identifier, start)
    }

  }

  implicit class XtensionDocumentSymbolOccurrence(info: l.SymbolInformation) {
    def fullPath: String = s"${info.getContainerName}${info.getName}"
    def toSymbolOccurrence: s.SymbolOccurrence = {
      val startRange = info.getLocation.getRange.getStart
      val endRange = info.getLocation.getRange.getEnd
      s.SymbolOccurrence(
        range = Some(
          new s.Range(
            startRange.getLine,
            startRange.getCharacter,
            startRange.getLine,
            startRange.getCharacter
          )
        ),
        // include end line for testing purposes
        symbol =
          s"${info.getContainerName}${info.getName}(${info.getKind}):${endRange.getLine + 1}",
        role = s.SymbolOccurrence.Role.DEFINITION
      )
    }
  }

}
