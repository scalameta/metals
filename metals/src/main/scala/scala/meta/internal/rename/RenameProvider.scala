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
import scala.meta.internal.metals.ReferenceProvider
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.parsing.Trees
import scala.meta.internal.pc.Identifier
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SelectTree
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.Synthetic
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
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
    trees: Trees,
)(implicit executionContext: ExecutionContext) {

  private val awaitingSave = new ConcurrentLinkedQueue[() => Future[Unit]]

  def prepareRename(
      params: TextDocumentPositionParams,
      token: CancelToken,
  ): Future[Option[LSPRange]] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    compilations.compilationFinished(source).flatMap { _ =>
      definitionProvider.definition(source, params, token).map { definition =>
        val symbolOccurrence =
          definitionProvider
            .symbolOccurrence(source, params.getPosition)
            .orElse(
              findRenamedImportOccurrenceAtPosition(
                source,
                params.getPosition(),
              )
            )
        for {
          (occurence, _) <- symbolOccurrence
          definitionLocation <- definition.locations.asScala.headOption
          definitionPath = definitionLocation.getUri().toAbsolutePath
          if canRenameSymbol(occurence.symbol, None) &&
            (isWorkspaceSymbol(occurence.symbol, definitionPath) ||
              findRenamedImportForSymbol(source, occurence.symbol).isDefined)
          range <- occurence.range
        } yield range.toLsp
      }
    }
  }

  def rename(
      params: RenameParams,
      token: CancelToken,
  ): Future[WorkspaceEdit] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    compilations.compilationFinished(source).flatMap { _ =>
      definitionProvider
        .definition(source, params, token)
        .flatMap { definition =>
          val textParams = new TextDocumentPositionParams(
            params.getTextDocument(),
            params.getPosition(),
          )

          lazy val definitionTextParams =
            definition.locations.asScala.map { l =>
              new TextDocumentPositionParams(
                new TextDocumentIdentifier(l.getUri()),
                l.getRange().getStart(),
              )
            }

          val symbolOccurrence =
            definitionProvider
              .symbolOccurrence(source, textParams.getPosition)
              .orElse(
                findRenamedImportOccurrenceAtPosition(
                  source,
                  params.getPosition(),
                )
              )

          val suggestedName = params.getNewName()
          val withoutBackticks =
            if (suggestedName.isBackticked)
              suggestedName.stripBackticks
            else suggestedName
          val newName = Identifier.backtickWrap(withoutBackticks)

          def isNotRenamedSymbol(
              textDocument: TextDocument,
              occ: SymbolOccurrence,
          ): Boolean = {
            def realName = occ.symbol.desc.name.value
            def foundName =
              occ.range
                .flatMap(rng => rng.inString(textDocument.text))
                .map(_.stripBackticks)
            occ.symbol.isLocal ||
            foundName.contains(realName)
          }

          def shouldCheckImplementation(
              symbol: String,
              path: AbsolutePath,
              textDocument: TextDocument,
          ) =
            !symbol.desc.isType && !(symbol.isLocal && implementationProvider
              .defaultSymbolSearch(path, textDocument)(symbol)
              .exists(info => info.isTrait || info.isClass))

          val allReferences = for {
            (occurence, semanticDb) <- symbolOccurrence.toIterable
            definitionLoc <- definition.locations.asScala.headOption.toIterable
            definitionPath = definitionLoc.getUri().toAbsolutePath
            defSemanticdb <- definition.semanticdb.toIterable
            if canRenameSymbol(occurence.symbol, Option(newName)) &&
              isWorkspaceSymbol(occurence.symbol, definitionPath) &&
              isNotRenamedSymbol(semanticDb, occurence)
            parentSymbols =
              implementationProvider
                .topMethodParents(occurence.symbol, defSemanticdb)
            txtParams <- {
              if (parentSymbols.nonEmpty) parentSymbols.map(toTextParams)
              else if (definitionTextParams.nonEmpty) definitionTextParams
              else List(textParams)
            }
            isJava = definitionPath.isJava
            currentReferences =
              referenceProvider
                .references(
                  /**
                   * isJava - in Java we can include declarations safely and we
                   * also need to include contructors.
                   */
                  toReferenceParams(
                    txtParams,
                    includeDeclaration = isJava,
                  ),
                  findRealRange = findRealRange(newName),
                  includeSynthetic,
                )
                .flatMap(_.locations)
            definitionLocation = {
              if (parentSymbols.isEmpty)
                definition.locations.asScala
                  .filter(_.getUri().isScalaOrJavaFilename)
              else parentSymbols
            }
            companionRefs = companionReferences(
              occurence.symbol,
              source,
              newName,
            )
            implReferences = implementations(
              txtParams,
              shouldCheckImplementation(
                occurence.symbol,
                source,
                semanticDb,
              ),
              newName,
            )
          } yield implReferences.map(implLocs =>
            currentReferences ++ implLocs ++ companionRefs ++ definitionLocation
          )
          Future
            .sequence(allReferences)
            .map(locs => (locs.flatten, symbolOccurrence, definition, newName))
        }
        .map { case (allReferences, symbolOccurrence, definition, newName) =>
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

          if (fallbackOccurences.isEmpty) {
            scribe.debug(s"Symbol occurence was $symbolOccurrence")
            scribe.debug(s"The definition found was $definition")
          }

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

  def runSave(): Future[Unit] = {
    val all = synchronized {
      ConcurrentQueue.pollAll(awaitingSave)
    }
    Future.sequence(all.map(waiting => waiting())).ignoreValue
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
      symbolOccurrence: Option[(SymbolOccurrence, TextDocument)],
  ): Seq[Location] = {
    lazy val uri = source.toURI.toString()

    def withoutBacktick(str: String) = str.stripPrefix("`").stripSuffix("`")
    def occurrences(
        semanticDb: TextDocument,
        occurence: SymbolOccurrence,
        renameName: String,
    ) = for {
      occ <- semanticDb.occurrences
      rng <- occ.range
      realName <- rng.inString(semanticDb.text)
      if occ.symbol == occurence.symbol &&
        withoutBacktick(realName) == withoutBacktick(renameName)
    } yield new Location(uri, rng.toLsp)

    val result = for {
      (occurence, semanticDb) <- symbolOccurrence
      rename <- findRenamedImportForSymbol(source, occurence.symbol)
      renamedOccurences = occurrences(
        semanticDb,
        occurence,
        rename.rename.value,
      )
    } yield renamedOccurences :+ new Location(uri, rename.rename.pos.toLsp)
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
      newName: String,
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

  private def companionReferences(
      sym: String,
      source: AbsolutePath,
      newName: String,
  ): Seq[Location] = {
    val results = for {
      companionSymbol <- companion(sym).toIterable
      loc <-
        definitionProvider
          .fromSymbol(companionSymbol, Some(source))
          .asScala
      // no companion objects in Java files
      if loc.getUri().isScalaFilename
      companionLocs <-
        referenceProvider
          .references(
            toReferenceParams(loc, includeDeclaration = false),
            findRealRange = findRealRange(newName),
          )
          .flatMap(_.locations) :+ loc
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
        name,
      )
    )
  }

  private def changeClosedFiles(
      fileEdits: Map[AbsolutePath, List[TextEdit]]
  ): Future[Unit] = {
    Future
      .sequence(fileEdits.toList.map { case (file, changes) =>
        Future {
          val text = file.readText
          val newText = TextEdits.applyEdits(text, changes)
          file.writeText(newText)
        }
      })
      .ignoreValue
  }

  private def implementations(
      textParams: TextDocumentPositionParams,
      shouldCheckImplementation: Boolean,
      newName: String,
  ): Future[Seq[Location]] = {
    if (shouldCheckImplementation) {
      for {
        implLocs <- implementationProvider.implementations(textParams)
      } yield {
        for {
          implLoc <- implLocs
          locParams = toReferenceParams(implLoc, includeDeclaration = true)
          loc <-
            referenceProvider
              .references(
                locParams,
                findRealRange = findRealRange(newName),
                includeSynthetic,
              )
              .flatMap(_.locations)
        } yield loc
      }
    } else {
      Future.successful(Nil)
    }
  }

  private def canRenameSymbol(
      symbol: String,
      newName: Option[String],
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
    val canRename = (!desc.isMethod || (!colonNotAllowed && !isForbidden))
    if (!canRename)
      scribe.debug(s"Cannot rename $symbol with new name $newName")
    canRename
  }

  private def findRenamedImportOccurrenceAtPosition(
      source: AbsolutePath,
      pos: Position,
  ): Option[(SymbolOccurrence, TextDocument)] = {
    val renameOpt = trees.findLastEnclosingAt[Importee.Rename](source, pos)

    for {
      rename <- renameOpt
      (occ, doc) <- definitionProvider.symbolOccurrence(
        source,
        rename.name.pos.toLsp.getStart(),
      )

    } yield (occ.copy(range = Some(rename.rename.pos.toSemanticdb)), doc)
  }

  private def findRenamedImportForSymbol(
      source: AbsolutePath,
      symbol: String,
  ): Option[Importee.Rename] = {
    lazy val displayName = symbol.desc.name.value
    // make sure it's not just a rename with the same base name
    def isCorrectSymbolOcccurrence(rename: Importee.Rename) = {
      definitionProvider
        .symbolOccurrence(source, rename.name.pos.toLsp.getStart())
        .exists { case (occ, _) => occ.symbol == symbol }
    }
    def findRename(tree: Tree): Option[Importee.Rename] = {
      tree match {
        case rename: Importee.Rename
            if rename.name.value == displayName &&
              isCorrectSymbolOcccurrence(rename) =>
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
      definitionPath: AbsolutePath,
  ): Boolean = {

    def isFromWorkspace = {
      definitionPath.isWorkspaceSource(workspace)
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

  private def findRealRange(newName: String)(
      range: s.Range,
      text: String,
      symbol: String,
  ): Option[s.Range] = {
    val name = range.inString(text)
    val nameString = symbol.desc.name.toString()
    val isExplicitVarSetter =
      name.exists(nm => nm.endsWith("_=") || nm.endsWith("_=`"))
    val isBackticked = name.exists(_.isBackticked)

    val symbolName =
      if (!isExplicitVarSetter) nameString.stripSuffix("_=")
      else nameString
    val realName =
      if (isBackticked)
        name.map(_.stripBackticks)
      else name

    if (symbol.isLocal || realName.contains(symbolName)) {
      /* We don't want to remove anything that is backticked, as we don't
       * know whether it's actuall needed (could be a pattern match). Here
       * we make sure that the backticks are not added twice.
       */
      val withoutBacktick = if (isBackticked && !newName.isBackticked) {
        range
          .withStartCharacter(range.startCharacter + 1)
          .withEndCharacter(range.endCharacter - 1)
      } else {
        range
      }

      val realRange =
        if (isExplicitVarSetter && !newName.endsWith("_="))
          withoutBacktick.withEndCharacter(withoutBacktick.endCharacter - 2)
        else withoutBacktick
      Some(realRange)
    } else {
      scribe.warn(s"Name doesn't match for $symbolName at $range")
      None
    }
  }

  private def textEdit(
      isOccurrence: (String => Boolean) => Boolean,
      loc: Location,
      newName: String,
  ): TextEdit = {
    val isApply = isOccurrence(str => str.desc.name.value == "apply")
    lazy val default = new TextEdit(
      loc.getRange(),
      newName,
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
          "." + newName,
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
      includeDeclaration: Boolean,
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
      includeDeclaration: Boolean,
  ): ReferenceParams = {
    val textDoc = new TextDocumentIdentifier()
    textDoc.setUri(location.getUri())
    toReferenceParams(
      textDoc,
      location.getRange().getStart(),
      includeDeclaration,
    )
  }

  private def toReferenceParams(
      params: TextDocumentPositionParams,
      includeDeclaration: Boolean,
  ): ReferenceParams = {
    toReferenceParams(
      params.getTextDocument(),
      params.getPosition(),
      includeDeclaration,
    )
  }

  private def toTextParams(location: Location): TextDocumentPositionParams = {
    new TextDocumentPositionParams(
      new TextDocumentIdentifier(location.getUri()),
      location.getRange().getStart(),
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

  private def forbiddenRename(
      old: String,
      name: Option[String],
  ): MessageParams = {
    val renamed = name.map(n => s"to $n").getOrElse("")
    val message =
      s"""|Cannot rename from $old to $renamed since it will change the semantics
          |and might break your code""".stripMargin
    new MessageParams(MessageType.Error, message)
  }

  private def forbiddenColonRename(
      old: String,
      name: Option[String],
  ): MessageParams = {
    val renamed = name.map(n => s"to $n").getOrElse("")
    val message =
      s"""|Cannot rename from $old to $renamed since it will change the semantics and
          |and might break your code.
          |Only rename to names ending with `:` is allowed.""".stripMargin
    new MessageParams(MessageType.Error, message)
  }
}
