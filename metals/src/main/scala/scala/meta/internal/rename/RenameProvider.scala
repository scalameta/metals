package scala.meta.internal.rename
import scala.meta.internal.metals.ReferenceProvider
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.WorkspaceEdit
import scala.meta.internal.implementation.ImplementationProvider
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import scala.meta.internal.metals.MetalsEnrichments._
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.mtags.{Symbol => MSymbol}
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.io.AbsolutePath
import org.eclipse.lsp4j.Location
import scala.meta.internal.metals.MetalsLanguageClient
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.{Range => LSPRange}
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LSPEither}
import scala.meta.internal.metals.Buffers
import java.net.URI
import java.nio.file.Paths
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.Compilations
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.RenameFile
import java.util.concurrent.ConcurrentLinkedQueue
import scala.meta.internal.async.ConcurrentQueue
import scala.meta.internal.semanticdb.Synthetic
import scala.meta.internal.semanticdb.SelectTree

final class RenameProvider(
    referenceProvider: ReferenceProvider,
    implementationProvider: ImplementationProvider,
    definitionProvider: DefinitionProvider,
    semanticdbs: Semanticdbs,
    index: GlobalSymbolIndex,
    workspace: AbsolutePath,
    client: MetalsLanguageClient,
    buffers: Buffers,
    compilations: Compilations
) {

  private var awaitingSave = new ConcurrentLinkedQueue[() => Unit]

  def prepareRename(params: TextDocumentPositionParams): Option[LSPRange] = {
    if (!compilations.currentlyCompiling.isEmpty) {
      client.showMessage(isCompiling)
      None
    } else {
      val source = params.getTextDocument.getUri.toAbsolutePath
      val symbolOccurence =
        definitionProvider.symbolOccurence(source, params)
      for {
        (occurence, semanticDb) <- symbolOccurence
        if canRenameSymbol(occurence.symbol, None)
        range <- occurence.range
      } yield range.toLSP
    }
  }

  def rename(params: RenameParams): WorkspaceEdit = {
    if (!compilations.currentlyCompiling.isEmpty) {
      client.showMessage(isCompiling)
      new WorkspaceEdit()
    } else {
      val source = params.getTextDocument.getUri.toAbsolutePath
      val textParams = new TextDocumentPositionParams(
        params.getTextDocument(),
        params.getPosition()
      )

      val symbolOccurence =
        definitionProvider.symbolOccurence(source, textParams)

      val suggestedName = params.getNewName()
      val newName =
        if (suggestedName.charAt(0) == '`')
          suggestedName.substring(1, suggestedName.length() - 1)
        else suggestedName

      def includeSynthetic(syn: Synthetic) = {
        syn.tree match {
          case SelectTree(_, id) =>
            id.exists(_.symbol.desc.name.toString == "apply")
          case _ => false
        }
      }

      val allReferences = for {
        (occurence, semanticDb) <- symbolOccurence.toIterable
        if canRenameSymbol(occurence.symbol, Option(newName))
        parentSymbols = implementationProvider
          .topMethodParents(occurence.symbol, semanticDb)
        txtParams <- {
          if (parentSymbols.isEmpty) List(textParams)
          else parentSymbols.map(toTextParams)
        }
        isLocal = occurence.symbol.isLocal
        currentReferences = referenceProvider
          .references(
            // we can't get definition by name for local symbols
            toReferenceParams(txtParams, includeDeclaration = isLocal),
            canSkipExactMatchCheck = false,
            includeSynthetics = includeSynthetic
          )
          .locations
        definitionLocation = {
          if (parentSymbols.isEmpty)
            definitionProvider
              .fromSymbol(occurence.symbol)
              .asScala
              .filter(_.getUri().isScalaFilename)
          else parentSymbols
        }
        companionRefs = companionReferences(occurence.symbol)
        implReferences = implementations(
          txtParams,
          !occurence.symbol.desc.isType
        )
        loc <- currentReferences ++ implReferences ++ companionRefs ++ definitionLocation
      } yield loc

      def isOccurence(fn: String => Boolean): Boolean = {
        symbolOccurence.exists {
          case (occ, _) => fn(occ.symbol)
        }
      }

      val allChanges = for {
        (uri, locs) <- allReferences.toList.distinct.groupBy(_.getUri())
      } yield {
        val textEdits = for (loc <- locs) yield {
          textEdit(isOccurence, loc, newName)
        }
        Seq(uri -> textEdits.toList)
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
        fileRenames(isOccurence, fileChanges.keySet, newName)
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
      isOccurence: (String => Boolean) => Boolean,
      fileChanges: Set[String],
      newName: String
  ): Option[LSPEither[TextDocumentEdit, ResourceOperation]] = {
    fileChanges
      .find { file =>
        isOccurence { str =>
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

  private def companionReferences(sym: String): Seq[Location] = {
    val results = for {
      companionSymbol <- companion(sym).toIterable
      loc <- definitionProvider
        .fromSymbol(companionSymbol)
        .asScala
      if loc.getUri().isScalaFilename
      companionLocs <- referenceProvider
        .references(toReferenceParams(loc, includeDeclaration = false))
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

  private def changeClosedFiles(fileEdits: Map[String, List[TextEdit]]) = {
    fileEdits.toArray.par.foreach {
      case (file, changes) =>
        val path = AbsolutePath(Paths.get(new URI(file)))
        val text = path.readText
        val newText = TextEdits.applyEdits(text, changes)
        path.writeText(newText)
    }
  }

  private def implementations(
      textParams: TextDocumentPositionParams,
      shouldCheckImplementation: Boolean
  ): Seq[Location] = {
    if (shouldCheckImplementation) {
      for {
        implLoc <- implementationProvider.implementations(textParams)
        locParams = toReferenceParams(implLoc, includeDeclaration = true)
        loc <- referenceProvider.references(locParams).locations
      } yield loc
    } else {
      Nil
    }
  }

  private def canRenameSymbol(symbol: String, newName: Option[String]) = {
    val forbiddenMethods = Set("equals", "hashCode", "unapply", "unary_!", "!")
    val desc = symbol.desc
    val name = desc.name.value
    val isForbidden = forbiddenMethods(name)
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
      isOccurence: (String => Boolean) => Boolean,
      loc: Location,
      newName: String
  ): TextEdit = {
    val isApply = isOccurence(str => str.desc.name.value == "apply")
    lazy val default = new TextEdit(
      loc.getRange(),
      newName
    )
    if (isApply) {
      val locSource = loc.getUri.toAbsolutePath
      val textParams = new TextDocumentPositionParams
      textParams.setPosition(loc.getRange().getStart())
      val isImplicitApply =
        definitionProvider
          .symbolOccurence(locSource, textParams)
          .exists(_._1.symbol.desc.name.value != "apply")
      if (isImplicitApply) {
        val range = loc.getRange()
        range.setStart(range.getEnd())
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

  private def toReferenceParams(
      textDoc: TextDocumentIdentifier,
      pos: Position,
      includeDeclaration: Boolean
  ): ReferenceParams = {
    val referenceParams = new ReferenceParams()
    referenceParams.setPosition(pos)
    referenceParams.setTextDocument(textDoc)
    val context = new ReferenceContext()
    context.setIncludeDeclaration(includeDeclaration)
    referenceParams.setContext(context)
    referenceParams
  }

  private def toReferenceParams(
      location: Location,
      includeDeclaration: Boolean
  ): ReferenceParams = {
    val textDoc = new TextDocumentIdentifier()
    textDoc.setUri(location.getUri())
    toReferenceParams(
      textDoc,
      location.getRange().getStart(),
      includeDeclaration
    )
  }

  private def toReferenceParams(
      params: TextDocumentPositionParams,
      includeDeclaration: Boolean
  ): ReferenceParams = {
    toReferenceParams(
      params.getTextDocument(),
      params.getPosition(),
      includeDeclaration
    )
  }

  private def toTextParams(location: Location): TextDocumentPositionParams = {
    new TextDocumentPositionParams(
      new TextDocumentIdentifier(location.getUri()),
      location.getRange().getStart()
    )
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
