package scala.meta.internal.rename
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.PositionInFile
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
import java.net.URI
import java.nio.file.Paths
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.RenameFile
import java.util.concurrent.ConcurrentLinkedQueue
import scala.meta.internal.async.ConcurrentQueue
import scala.meta.internal.metals.PositionInFile.locationToPositionInFile
import scala.meta.internal.semanticdb.SelectTree
import scala.meta.internal.semanticdb.Synthetic

final class RenameProvider(
    referenceProvider: ReferenceProvider,
    definitionProvider: DefinitionProvider,
    index: GlobalSymbolIndex,
    workspace: AbsolutePath,
    client: MetalsLanguageClient,
    buffers: Buffers,
    compilations: Compilations
) {

  private val awaitingSave = new ConcurrentLinkedQueue[() => Unit]

  def prepareRename(params: TextDocumentPositionParams): Option[LSPRange] = {
    if (compilations.currentlyCompiling.nonEmpty) {
      client.showMessage(isCompiling)
      None
    } else {
      val positionInFile = PositionInFile(
        params.getTextDocument.getUri.toAbsolutePath,
        params.getPosition
      )
      val symbolOccurence =
        definitionProvider.symbolOccurrence(positionInFile)
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

      val positionInFile = PositionInFile(
        params.getTextDocument.getUri.toAbsolutePath,
        params.getPosition
      )

      val symbolOccurrence =
        definitionProvider.symbolOccurrence(positionInFile)
      val allReferences = symbolOccurrence match {
        case Some((occurrence, doc))
            if canRenameSymbol(occurrence.symbol, Some(params.getNewName)) =>
          referenceProvider.allInheritanceReferences(
            occurrence,
            doc,
            positionInFile,
            includeSynthetic
          ) ++ referenceProvider.companionReferences(
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
          textEdit(isOccurrence, loc, params.getNewName)
        }
        Seq(uri -> textEdits)
      }

      val fileChanges = allChanges.flatten.toMap
      val (openedEdits, closedEdits) = fileChanges.partition {
        case (file, edits) =>
          val path = AbsolutePath(Paths.get(new URI(file)))
          buffers.contains(path)
      }

      awaitingSave.add(() => changeClosedFiles(closedEdits))

      val edits = documentEdits(openedEdits)
      val renames =
        fileRenames(isOccurrence, fileChanges.keySet, params.getNewName)
      new WorkspaceEdit((edits ++ renames).asJava)
    }
  }

  def runSave(): Unit = synchronized {
    ConcurrentQueue.pollAll(awaitingSave).foreach(waiting => waiting())
  }

  private def documentEdits(
      openedEdits: Map[String, List[TextEdit]]
  ): List[LSPEither[TextDocumentEdit, ResourceOperation]] = {
    openedEdits.map {
      case (file, edits) =>
        val textId = new VersionedTextDocumentIdentifier()
        textId.setUri(file)
        val ed = new TextDocumentEdit(textId, edits.asJava)
        LSPEither.forLeft[TextDocumentEdit, ResourceOperation](ed)
    }.toList
  }

  private def fileRenames(
      isOccurrence: (String => Boolean) => Boolean,
      fileChanges: Set[String],
      newName: String
  ): Option[LSPEither[TextDocumentEdit, ResourceOperation]] = {
    fileChanges
      .find { file =>
        isOccurrence { str =>
          str.owner.isPackage &&
          (str.desc.isType || str.desc.isTerm) &&
          file.endsWith(s"/${str.desc.name.value}.scala")
        }
      }
      .map { file =>
        val newFile =
          file.replaceAll("/[^/]+\\.scala$", s"/$newName.scala")
        LSPEither.forRight[TextDocumentEdit, ResourceOperation](
          new RenameFile(file, newFile)
        )
      }
  }

  private def changeClosedFiles(
      fileEdits: Map[String, List[TextEdit]]
  ): Unit = {
    fileEdits.toArray.par.foreach {
      case (file, changes) =>
        val path = AbsolutePath(Paths.get(new URI(file)))
        val text = path.readText
        val newText = TextEdits.applyEdits(text, changes)
        path.writeText(newText)
    }
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
          .symbolOccurrence(locationToPositionInFile(loc))
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
