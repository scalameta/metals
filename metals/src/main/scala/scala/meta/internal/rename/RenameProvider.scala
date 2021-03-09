package scala.meta.internal.rename

import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Importee
import scala.meta.Tree
import scala.meta.internal.async.ConcurrentQueue
import scala.meta.internal.implementation.ImplementationProvider
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.ReferenceProvider
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SelectTree
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.Synthetic
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LSPEither}
import org.eclipse.lsp4j.{Range => LSPRange}

final class RenameProvider(
    referenceProvider: ReferenceProvider,
    implementationProvider: ImplementationProvider,
    definitionProvider: DefinitionProvider,
    workspace: AbsolutePath,
    client: MetalsLanguageClient,
    buffers: Buffers,
    compilations: Compilations,
    clientConfig: ClientConfiguration,
    trees: Trees
)(implicit executionContext: ExecutionContext) {

  private val awaitingSave = new ConcurrentLinkedQueue[() => Unit]

  def prepareRename(
      params: TextDocumentPositionParams,
      token: CancelToken
  ): Future[Option[LSPRange]] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    compilations.compilationFinished(source).flatMap { _ =>
      definitionProvider.definition(source, params, token).map { definition =>
        val symbolOccurrence =
          definitionProvider.symbolOccurrence(source, params.getPosition)

        for {
          (occurence, _) <- symbolOccurrence
          definitionLocation <- definition.locations.asScala.headOption
          definitionPath = definitionLocation.getUri().toAbsolutePath
          if canRenameSymbol(occurence.symbol, None) &&
            (isWorkspaceSymbol(occurence.symbol, definitionPath) ||
              findRenamedImport(source, occurence.symbol).isDefined)
          range <- occurence.range
        } yield range.toLSP
      }
    }
  }

  def rename(
      params: RenameParams,
      token: CancelToken
  ): Future[WorkspaceEdit] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    compilations.compilationFinished(source).flatMap { _ =>
      definitionProvider.definition(source, params, token).map { definition =>
        val textParams = new TextDocumentPositionParams(
          params.getTextDocument(),
          params.getPosition()
        )

        val symbolOccurrence =
          definitionProvider.symbolOccurrence(source, textParams.getPosition)

        val suggestedName = params.getNewName()
        val newName =
          if (suggestedName.charAt(0) == '`')
            suggestedName.substring(1, suggestedName.length() - 1)
          else suggestedName

        def isNotRenamedSymbol(
            textDocument: TextDocument,
            occ: SymbolOccurrence
        ): Boolean = {
          def realName = occ.symbol.desc.name.value
          def foundName = occ.range.map(rng =>
            rng.inString(textDocument.text).stripPrefix("`").stripSuffix("`")
          )
          occ.symbol.isLocal || foundName.contains(realName)
        }

        val allReferences = for {
          (occurence, semanticDb) <- symbolOccurrence.toIterable
          definitionLoc <- definition.locations.asScala.headOption.toIterable
          definitionPath = definitionLoc.getUri().toAbsolutePath
          if canRenameSymbol(occurence.symbol, Option(newName)) &&
            isWorkspaceSymbol(occurence.symbol, definitionPath) &&
            isNotRenamedSymbol(semanticDb, occurence)
          parentSymbols =
            implementationProvider
              .topMethodParents(occurence.symbol, semanticDb)
          txtParams <- {
            if (parentSymbols.isEmpty) List(textParams)
            else parentSymbols.map(toTextParams)
          }
          isLocal = occurence.symbol.isLocal
          currentReferences =
            referenceProvider
              .references(
                // we can't get definition by name for local symbols
                toReferenceParams(txtParams, includeDeclaration = isLocal),
                canSkipExactMatchCheck = false,
                includeSynthetic
              )
              .locations
          definitionLocation = {
            if (parentSymbols.isEmpty)
              definition.locations.asScala
                .filter(_.getUri().isScalaFilename)
            else parentSymbols
          }
          companionRefs = companionReferences(occurence.symbol)
          implReferences = implementations(
            txtParams,
            !occurence.symbol.desc.isType
          )
          loc <-
            currentReferences ++ implReferences ++ companionRefs ++ definitionLocation
        } yield loc

        def isOccurrence(fn: String => Boolean): Boolean = {
          symbolOccurrence.exists { case (occ, _) =>
            fn(occ.symbol)
          }
        }

        // If we didn't find any references then it might be a renamed symbol `import a.{ B => C }`
        val fallbackOccurences =
          if (allReferences.isEmpty)
            renamedImportOccurrences(source, symbolOccurrence)
          else allReferences

        val allChanges = for {
          (path, locs) <- fallbackOccurences.toList.distinct
            .groupBy(_.getUri().toAbsolutePath)
        } yield {
          val textEdits = for (loc <- locs) yield {
            textEdit(isOccurrence, loc, newName)
          }
          Seq(path -> textEdits.toList)
        }
        val fileChanges = allChanges.flatten.toMap
        val shouldRenameInBackground =
          !clientConfig.isOpenFilesOnRenameProvider || fileChanges.keySet.size >= clientConfig.renameFileThreshold
        val (openedEdits, closedEdits) =
          if (shouldRenameInBackground) {
            if (clientConfig.isOpenFilesOnRenameProvider) {
              client.showMessage(fileThreshold(fileChanges.keySet.size))
            }
            fileChanges.partition { case (path, _) =>
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
  }

  def runSave(): Unit =
    synchronized {
      ConcurrentQueue.pollAll(awaitingSave).foreach(waiting => waiting())
    }

  /**
   * In case of import renames, we can only rename the symbol in file.
   * Global rename will not return any results, so this method will be used as
   * a fallback.
   * @param source path of the current document
   * @param symbolOccurrence occurence of the symbol we are at together with semanticdb
   * @return all locations that were renamed
   */
  private def renamedImportOccurrences(
      source: AbsolutePath,
      symbolOccurrence: Option[(SymbolOccurrence, TextDocument)]
  ): Seq[Location] = {
    lazy val uri = source.toURI.toString()

    def withoutBacktick(str: String) = str.stripPrefix("`").stripSuffix("`")
    def realLocationWithoutBackticks(realName: String, rng: Range): Location = {
      val realRange = if (realName.head == '`' && realName.last == '`') {
        rng.copy(
          startCharacter = rng.getStart.getCharacter + 1,
          endCharacter = rng.getEnd.getCharacter - 1
        )
      } else {
        rng
      }
      new Location(uri, realRange)
    }

    def occurrences(
        semanticDb: TextDocument,
        occurence: SymbolOccurrence,
        renameName: String
    ) = for {
      occ <- semanticDb.occurrences
      rng <- occ.range
      realName = rng.inString(semanticDb.text)
      if occ.symbol == occurence.symbol &&
        withoutBacktick(realName) == withoutBacktick(renameName)
    } yield realLocationWithoutBackticks(realName, rng.toLSP)

    val result = for {
      (occurence, semanticDb) <- symbolOccurrence
      rename <- findRenamedImport(source, occurence.symbol)
      renamedOccurences = occurrences(
        semanticDb,
        occurence,
        rename.rename.value
      )
    } yield renamedOccurences :+ realLocationWithoutBackticks(
      rename.rename.syntax,
      rename.rename.pos.toLSP
    )
    result.getOrElse(Nil)
  }

  private def documentEdits(
      openedEdits: Map[AbsolutePath, List[TextEdit]]
  ): List[LSPEither[TextDocumentEdit, ResourceOperation]] = {
    openedEdits.map { case (file, edits) =>
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

  private def companionReferences(sym: String): Seq[Location] = {
    val results = for {
      companionSymbol <- companion(sym).toIterable
      loc <-
        definitionProvider
          .fromSymbol(companionSymbol)
          .asScala
      if loc.getUri().isScalaFilename
      companionLocs <-
        referenceProvider
          .references(
            toReferenceParams(loc, includeDeclaration = false),
            canSkipExactMatchCheck = false
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
      case _ =>
        None
    }

    termOrType.map(name =>
      Symbols.Global(
        sym.owner,
        name
      )
    )
  }

  private def changeClosedFiles(
      fileEdits: Map[AbsolutePath, List[TextEdit]]
  ) = {
    fileEdits.toArray.par.foreach { case (file, changes) =>
      val text = file.readText
      val newText = TextEdits.applyEdits(text, changes)
      file.writeText(newText)
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
        loc <-
          referenceProvider
            .references(
              locParams,
              canSkipExactMatchCheck = false,
              includeSynthetic
            )
            .locations
      } yield loc
    } else {
      Nil
    }
  }

  private def canRenameSymbol(
      symbol: String,
      newName: Option[String]
  ) = {
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
    (!desc.isMethod || (!colonNotAllowed && !isForbidden))
  }

  private def findRenamedImport(
      source: AbsolutePath,
      symbol: String
  ): Option[Importee.Rename] = {
    def findRename(tree: Tree): Option[Importee.Rename] = {
      tree match {
        case rename: Importee.Rename
            if rename.name.value == symbol.desc.name.value =>
          Some(rename)
        case other =>
          other.children.toIterable.flatMap { child =>
            findRename(child)
          }.headOption
      }
    }

    for {
      tree <- trees.get(source)
      rename <- findRename(tree)
    } yield rename

  }

  private def isWorkspaceSymbol(
      symbol: String,
      definitionPath: AbsolutePath
  ): Boolean = {

    def isFromWorkspace = {
      val isInWorkspace = definitionPath.isWorkspaceSource(workspace)
      if (isInWorkspace && definitionPath.isJava) {
        client.showMessage(javaSymbol(symbol.desc.name.value))
      }
      isInWorkspace
    }

    symbol.isLocal || isFromWorkspace
  }

  private def includeSynthetic(syn: Synthetic) = {
    syn.tree match {
      case SelectTree(_, id) =>
        id.exists(_.symbol.desc.name.toString == "apply")
      case _ => false
    }
  }

  private def textEdit(
      isOccurrence: (String => Boolean) => Boolean,
      loc: Location,
      newName: String
  ): TextEdit = {
    val isApply = isOccurrence(str => str.desc.name.value == "apply")
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
          .symbolOccurrence(locSource, textParams.getPosition)
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
