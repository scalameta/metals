package scala.meta.internal.metals

import java.util.Collections._

import ch.epfl.scala.{bsp4j => b}
import com.google.gson.JsonElement
import org.eclipse.lsp4j.Location
import org.eclipse.{lsp4j => l}
import org.eclipse.lsp4j.CodeLens

import scala.concurrent.ExecutionContext
import scala.meta.internal.implementation.ImplementationProvider
import scala.meta.internal.implementation.ImplementationProvider.findSymbol
import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.internal.metals.ClientCommands.StartDebugSession
import scala.meta.internal.metals.ClientCommands.StartRunSession
import scala.meta.internal.metals.CodeLensProvider._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.MethodSignature
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath
import scala.meta.internal.semanticdb.Scala._

trait CodeLensProvider {
  def findLenses(path: AbsolutePath): Seq[l.CodeLens]
  def findParentForMethodOrField(
      ms: SymbolInformation,
      docWithPath: TextDocumentWithPath
  ): Option[Location]
}

final class DebugCodeLensProvider(
    buildTargetClasses: BuildTargetClasses,
    buffers: Buffers,
    buildTargets: BuildTargets,
    semanticdbs: Semanticdbs,
    implementationProvider: ImplementationProvider
)(implicit ec: ExecutionContext)
    extends CodeLensProvider {
  // code lenses will be refreshed after compilation or when workspace gets indexed
  def findLenses(path: AbsolutePath): Seq[l.CodeLens] = {
    val lenses = buildTargets
      .inverseSources(path)
      .map { buildTarget =>
        val classes = buildTargetClasses.classesOf(buildTarget)
        codeLenses(path, buildTarget, classes)
      }

    lenses.getOrElse(Nil)
  }

  private def findClassInfo(
      symbol: String,
      owner: String,
      textDocument: TextDocument
  ): Option[SymbolInformation] = {
    if (owner.nonEmpty) {
      findSymbol(textDocument, owner)
    } else {
      textDocument.symbols.find { sym =>
        sym.signature match {
          case sig: ClassSignature =>
            sig.declarations.exists(_.symlinks.contains(symbol))
          case _ => false
        }
      }
    }
  }

  def checkSignaturesEqual(
      first: SymbolInformation,
      firstMethod: MethodSignature,
      second: SymbolInformation,
      secondMethod: MethodSignature
  ): Boolean = {
    first.symbol != second.symbol &&
    first.displayName == second.displayName &&
    firstMethod.parameterLists == secondMethod.parameterLists
  }

  def findParentForMethodOrField(
      msi: SymbolInformation,
      documentWithPath: TextDocumentWithPath
  ): Option[Location] = {
    val classSymbolInformation =
      findClassInfo(msi.symbol, msi.symbol.owner, documentWithPath.textDocument)
    val methodInfo = msi.signature.asInstanceOf[MethodSignature]

    val q = scala.collection.mutable
      .Queue[(SymbolInformation, TextDocumentWithPath)]()
    classSymbolInformation.foreach(csi => q.enqueue((csi, documentWithPath)))

    while (q.nonEmpty) {
      val (si, docWithPath) = q.dequeue()
      si.signature match {
        case cs: ClassSignature =>
          for (sl <- cs.getDeclarations.symlinks) {
            for {
              mSymbolInformation <- ImplementationProvider.findSymbol(
                docWithPath.textDocument,
                sl
              )
              if mSymbolInformation.isMethod
              methodSignature = mSymbolInformation.signature
                .asInstanceOf[MethodSignature]
              if checkSignaturesEqual(
                msi,
                methodInfo,
                mSymbolInformation,
                methodSignature
              )
              soc <- ImplementationProvider.findDefOccurrence(
                docWithPath.textDocument,
                mSymbolInformation.symbol,
                docWithPath.filePath
              )
            } {
              return Some(
                new Location(
                  docWithPath.filePath.toURI.toString,
                  soc.getRange.toLSP
                )
              )
            }
          }

          val parents = ImplementationProvider.parentsFromSignature(
            si.symbol,
            si.signature,
            None
          )
          for ((parentClassSymbol, _) <- parents) {
            for {
              dc <- implementationProvider.findSemanticDbWithPathForSymbol(
                parentClassSymbol
              )
              _ = ImplementationProvider
                .findSymbol(dc.textDocument, parentClassSymbol)
                .foreach(symbolInfo => q.enqueue((symbolInfo, dc)))
            } yield ()
          }
        case _ =>
      }
    }
    None
  }

  private def codeLenses(
      path: AbsolutePath,
      target: b.BuildTargetIdentifier,
      classes: BuildTargetClasses.Classes
  ): Seq[l.CodeLens] = {
    semanticdbs.textDocument(path).documentIncludingStale match {
      case None =>
        Nil
      case Some(textDocument) =>
        val distance =
          TokenEditDistance.fromBuffer(path, textDocument.text, buffers)

        for {
          occurrence <- textDocument.occurrences
          if occurrence.role.isDefinition
          symbol = occurrence.symbol
          range <- occurrence.range
            .flatMap(r => distance.toRevised(r.toLSP))
            .toList
          runTestCommands = {
            val main = classes.mainClasses
              .get(symbol)
              .map(mainCommand(target, _))
              .getOrElse(Nil)
            val tests = classes.testClasses
              .get(symbol)
              .map(testCommand(target, _))
              .getOrElse(Nil)
            main ++ tests
          }
          parentMethodCommand: Option[l.Command] = {
            ImplementationProvider.findSymbol(textDocument, symbol) match {
              case Some(si) if si.isMethod || si.isField =>
                findParentForMethodOrField(
                  si,
                  TextDocumentWithPath(textDocument, path)
                ).map(loc =>
                  makeGoToParentMethodCommand(
                    loc.getUri,
                    si.displayName,
                    loc.getRange
                  )
                )

              case _ =>
                None
            }
          }
          allCommands = runTestCommands ++ parentMethodCommand
          c <- allCommands.map(new l.CodeLens(range, _, null))
        } yield c
      case _ =>
        Nil
    }
  }
}

object CodeLensProvider {
  import scala.meta.internal.metals.JsonParser._

  private val Empty: CodeLensProvider = new CodeLensProvider {
    override def findLenses(path: AbsolutePath): Seq[CodeLens] = Nil
    override def findParentForMethodOrField(
        ms: SymbolInformation,
        docPath: TextDocumentWithPath
    ): Option[Location] = None
  }

  def apply(
      buildTargetClasses: BuildTargetClasses,
      buffers: Buffers,
      buildTargets: BuildTargets,
      compilations: Compilations,
      semanticdbs: Semanticdbs,
      implementationProvider: ImplementationProvider,
      capabilities: ClientExperimentalCapabilities
  )(implicit ec: ExecutionContext): CodeLensProvider = {
    if (!capabilities.debuggingProvider) Empty
    else {
      new DebugCodeLensProvider(
        buildTargetClasses,
        buffers,
        buildTargets,
        semanticdbs,
        implementationProvider
      )
    }
  }

  def testCommand(
      target: b.BuildTargetIdentifier,
      className: String
  ): List[l.Command] = {
    val params = {
      val dataKind = b.DebugSessionParamsDataKind.SCALA_TEST_SUITES
      val data = singletonList(className).toJson
      sessionParams(target, dataKind, data)
    }

    List(
      command("test", StartRunSession, params),
      command("debug test", StartDebugSession, params)
    )
  }

  def mainCommand(
      target: b.BuildTargetIdentifier,
      main: b.ScalaMainClass
  ): List[l.Command] = {
    val params = {
      val dataKind = b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS
      val data = main.toJson
      sessionParams(target, dataKind, data)
    }

    List(
      command("run", StartRunSession, params),
      command("debug", StartDebugSession, params)
    )
  }

  def makeGoToParentMethodCommand(
      uri: String,
      name: String,
      range: l.Range
  ): l.Command = {
    val location = new l.Location(uri, range)
    new l.Command(
      s"Parent ${name}",
      ClientCommands.GotoLocation.id,
      singletonList(location)
    )
  }

  private def sessionParams(
      target: b.BuildTargetIdentifier,
      dataKind: String,
      data: JsonElement
  ): b.DebugSessionParams = {
    new b.DebugSessionParams(List(target).asJava, dataKind, data)
  }

  private def command(
      name: String,
      command: Command,
      params: b.DebugSessionParams
  ): l.Command = {
    new l.Command(name, command.id, singletonList(params))
  }
}
