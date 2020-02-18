package scala.meta.internal.rename

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.ReferenceProvider
import scala.meta.internal.metals.TextEdits
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.{Symbol => MSymbol}
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.io.AbsolutePath
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.{Range => LSPRange}
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LSPEither}

import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.RenameFile
import java.util.concurrent.ConcurrentLinkedQueue

import scala.meta.internal.async.ConcurrentQueue
import scala.meta.internal.metals.FilePosition
import scala.meta.internal.metals.FilePosition.locationToFilePosition
import scala.meta.internal.semanticdb.SelectTree
import scala.meta.internal.semanticdb.Synthetic
import scala.meta.internal.metals.MetalsServerConfig

final class RenameProvider(
    referenceProvider: ReferenceProvider,
    definitionProvider: DefinitionProvider,
    index: GlobalSymbolIndex,
    workspace: AbsolutePath,
    client: MetalsLanguageClient,
    buffers: Buffers,
    compilations: Compilations,
    metalsConfig: MetalsServerConfig
) {

  private val awaitingSave = new ConcurrentLinkedQueue[() => Unit]

  def prepareRename(params: TextDocumentPositionParams): Option[LSPRange] = {
    if (compilations.currentlyCompiling.nonEmpty) {
      client.showMessage(isCompiling)
      None
    } else {
      val symbolOccurence = definitionProvider.symbolOccurrence(params)
      for {
        (occurence, semanticDb) <- symbolOccurence
        if canRenameSymbol(occurence.symbol, None)
        range <- occurence.range
      } yield range.toLSP
    }
  }

  def rename(params: RenameParams): WorkspaceEdit = {
    def includeSynthetic(syn: Synthetic) = {
      syn.tree match {
        case SelectTree(_, id) =>
          id.exists(_.symbol.desc.name.toString == "apply")
        case _ => false
      }
    }

    if (compilations.currentlyCompiling.nonEmpty) {
      client.showMessage(isCompiling)
      new WorkspaceEdit()
    } else {

      val suggestedName = params.getNewName()
      val newName =
        if (suggestedName.charAt(0) == '`')
          suggestedName.substring(1, suggestedName.length() - 1)
        else suggestedName

      val filePosition = FilePosition(
        params.getTextDocument.getUri.toAbsolutePath,
        params.getPosition
      )

      val symbolOccurrence =
        definitionProvider.symbolOccurrence(params)
      val allReferences = symbolOccurrence match {
        case Some((occurrence, doc))
            if canRenameSymbol(occurrence.symbol, Some(newName)) =>
          referenceProvider.allInheritanceReferences(
            occurrence,
            doc,
            filePosition,
            includeSynthetic,
            canSkipExactMatchCheck = false
          ) ++ companionReferences(
            occurrence.symbol
          )
        case _ =>
          List()
      }

      def isOccurrence(fn: String => Boolean): Boolean = {
        symbolOccurrence.exists {
          case (occ, _) => fn(occ.symbol)
        }
      }

      val allChanges = for {
        (uri, locs) <- allReferences.toList.distinct.groupBy(_.getUri())
      } yield {
        val textEdits = for (loc <- locs) yield {
          textEdit(isOccurrence, loc, newName)
        }
        Seq(uri.toAbsolutePath -> textEdits)
      }

      val fileChanges = allChanges.flatten.toMap
      val shouldRenameInBackground =
        !metalsConfig.openFilesOnRenames ||
          fileChanges.keySet.size >= metalsConfig.renameFileThreshold
      val (openedEdits, closedEdits) =
        if (shouldRenameInBackground) {
          if (metalsConfig.openFilesOnRenames) {
            client.showMessage(fileThreshold(fileChanges.keySet.size))
          }
          fileChanges.partition {
            case (path, _) =>
              buffers.contains(path)
          }
        } else {
          (fileChanges, Map.empty[AbsolutePath, List[TextEdit]])
        }

      awaitingSave.add(() => changeClosedFiles(closedEdits))

      val edits = documentEdits(openedEdits)
      val renames =
        fileRenames(isOccurrence, fileChanges.keySet, newName)
      new WorkspaceEdit((edits ++ renames).asJava)
    }
  }

  def runSave(): Unit = synchronized {
    ConcurrentQueue.pollAll(awaitingSave).foreach(waiting => waiting())
  }

  private def documentEdits(
      openedEdits: Map[AbsolutePath, List[TextEdit]]
  ): List[LSPEither[TextDocumentEdit, ResourceOperation]] = {
    openedEdits.map {
      case (file, edits) =>
        val textId = new VersionedTextDocumentIdentifier()
        textId.setUri(file.toURI.toString())
        val ed = new TextDocumentEdit(textId, edits.asJava)
        LSPEither.forLeft[TextDocumentEdit, ResourceOperation](ed)
    }.toList
  }

  private def fileRenames(
      isOccurrence: (String => Boolean) => Boolean,
      fileChanges: Set[AbsolutePath],
      newName: String
  ): Option[LSPEither[TextDocumentEdit, ResourceOperation]] = {
    fileChanges
      .find { file =>
        isOccurrence { str =>
          str.owner.isPackage &&
          (str.desc.isType || str.desc.isTerm) &&
          file.toURI.toString.endsWith(s"/${str.desc.name.value}.scala")
        }
      }
      .map { file =>
        val uri = file.toURI.toString
        val newFile =
          uri.replaceAll("/[^/]+\\.scala$", s"/$newName.scala")
        LSPEither.forRight[TextDocumentEdit, ResourceOperation](
          new RenameFile(uri, newFile)
        )
      }
  }

  private def changeClosedFiles(
      fileEdits: Map[AbsolutePath, List[TextEdit]]
  ): Unit = {
    fileEdits.toArray.par.foreach {
      case (file, changes) =>
        val text = file.readText
        val newText = TextEdits.applyEdits(text, changes)
        file.writeText(newText)
    }
  }

  private def companionReferences(sym: String): Seq[Location] = {
    val results = for {
      companionSymbol <- companion(sym).toIterable
      loc <- definitionProvider
        .fromSymbol(companionSymbol)
        .asScala
      if loc.getUri.isScalaFilename
      companionLocs <- referenceProvider
        .currentSymbolReferences(
          locationToFilePosition(loc),
          includeDeclaration = false
        )
        .locations :+ loc
    } yield companionLocs
    results.toList
  }

  private def companion(sym: String) = {
    val termOrType = sym.desc match {
      case Descriptor.Type(name) =>
        Some(Descriptor.Term(name))
      case Descriptor.Term(name) =>
        Some(Descriptor.Type(name))
      case other =>
        None
    }

    termOrType.map(name =>
      Symbols.Global(
        sym.owner,
        name
      )
    )
  }

  private def canRenameSymbol(symbol: String, newName: Option[String]) = {
    val desc = symbol.desc
    val name = desc.name.value
    val isForbidden = RenameProvider.forbiddenToModifyMethods.contains(name)
    if (isForbidden) {
      client.showMessage(forbiddenRename(name, newName))
    }
    val colonNotAllowed = name.endsWith(":") && newName.exists(!_.endsWith(":"))
    if (colonNotAllowed) {
      client.showMessage(forbiddenColonRename(name, newName))
    }
    symbolIsLocal(symbol) && (!desc.isMethod || (!colonNotAllowed && !isForbidden))
  }

  private def symbolIsLocal(symbol: String): Boolean = {

    def isFromWorkspace =
      index
        .definition(MSymbol(symbol))
        .exists { definition =>
          val isLocal = definition.path.isWorkspaceSource(workspace)
          if (isLocal && definition.path.isJava) {
            client.showMessage(javaSymbol(symbol.desc.name.value))
          }
          isLocal
        }

    symbol.startsWith("local") || isFromWorkspace
  }

  private def textEdit(
      isOccurrence: (String => Boolean) => Boolean,
      loc: Location,
      newName: String
  ): TextEdit = {
    val isApply = isOccurrence(str => str.desc.name.value == "apply")
    lazy val default = new TextEdit(
      loc.getRange,
      newName
    )
    if (isApply) {
      val isImplicitApply =
        definitionProvider
          .symbolOccurrence(locationToFilePosition(loc))
          .exists(_._1.symbol.desc.name.value != "apply")
      if (isImplicitApply) {
        val range = loc.getRange
        range.setStart(range.getEnd)
        new TextEdit(
          range,
          "." + newName
        )
      } else {
        default
      }
    } else {
      default
    }
  }

  private def fileThreshold(
      files: Int
  ): MessageParams = {
    val message =
      s"""|Renamed symbol is present in over $files files.
          |It will be renamed without opening the files
          |to prevent the editor from becoming unresponsive.""".stripMargin
    new MessageParams(MessageType.Warning, message)
  }

  private def javaSymbol(
      old: String
  ): MessageParams = {
    val message =
      s"""|Definition of $old is contained in a java file.
          |Rename will only work inside of Scala files.""".stripMargin
    new MessageParams(MessageType.Warning, message)
  }

  private def forbiddenRename(
      old: String,
      name: Option[String]
  ): MessageParams = {
    val renamed = name.map(n => s"to $n").getOrElse("")
    val message =
      s"""|Cannot rename from $old to $renamed since it will change the semantics
          |and might break your code""".stripMargin
    new MessageParams(MessageType.Error, message)
  }

  private def isCompiling: MessageParams = {
    val message =
      s"""|Cannot rename while the code is compiling
          |since it could produce incorrect results.""".stripMargin
    new MessageParams(MessageType.Error, message)
  }

  private def forbiddenColonRename(
      old: String,
      name: Option[String]
  ): MessageParams = {
    val renamed = name.map(n => s"to $n").getOrElse("")
    val message =
      s"""|Cannot rename from $old to $renamed since it will change the semantics and
          |and might break your code.
          |Only rename to names ending with `:` is allowed.""".stripMargin
    new MessageParams(MessageType.Error, message)
  }
}

object RenameProvider {
  val forbiddenToModifyMethods: Set[String] =
    Set("equals", "hashCode", "toString", "unapply", "unary_!", "!")

}
