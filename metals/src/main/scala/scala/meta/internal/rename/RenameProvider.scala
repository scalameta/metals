package scala.meta.internal.rename

import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Importee
import scala.meta.Tree
import scala.meta.inputs.Input
import scala.meta.internal.async.ConcurrentQueue
import scala.meta.internal.metals.AdjustRange
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.DefinitionResult
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PositionSyntax.XtensionPositionsScalafix
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.mbt.MbtReferenceProvider
import scala.meta.internal.mtags.DefinitionAlternatives.GlobalSymbol
import scala.meta.internal.mtags.Symbol
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
import scala.meta.tokens.Token.Ident

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.SnippetTextEdit
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LSPEither}
import org.eclipse.lsp4j.{Range => LSPRange}

final class RenameProvider(
    mbtReferenceProvider: MbtReferenceProvider,
    definitionProvider: DefinitionProvider,
    workspace: AbsolutePath,
    client: MetalsLanguageClient,
    buffers: Buffers,
    compilations: Compilations,
    compilers: Compilers,
    clientConfig: ClientConfiguration,
    trees: Trees,
)(implicit executionContext: ExecutionContext, reportContext: ReportContext) {

  private val awaitingSave = new ConcurrentLinkedQueue[() => Future[Unit]]

  def prepareRename(
      params: TextDocumentPositionParams,
      token: CancelToken,
  ): Future[Option[LSPRange]] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    val localPrepareRename =
      compilers.prepareRename(params, token).map(_.asScala)
    localPrepareRename
      .filter(_.nonEmpty)
      .recoverWith { case _ =>
        compilations
          .compilationFinished(source, compileInverseDependencies = true)
          .flatMap { _ =>
            definitionProvider.definition(source, params, token).map {
              definition =>
                val symbolOccurrence: Option[LspOccurrence] =
                  mbtReferenceProvider
                    .enclosingOccurrences(params.getPosition(), source)
                    .headOption
                    .orElse {
                      findRenamedImportOccurrenceAtPosition(
                        source,
                        params.getPosition(),
                      )
                    }
                    .map { occ =>
                      LspOccurrence(occ.symbol, occ.range.map(_.toLsp))
                    }
                    .orElse {
                      occurrenceAtDefinition(
                        source,
                        definition,
                        params.getPosition(),
                      )
                    }
                for {
                  occurence <- symbolOccurrence
                  soughtSymbols = Set(occurence.symbol) ++ companion(
                    occurence.symbol
                  )
                  definitionLocation <- definition.locations.asScala.headOption
                  definitionPath = definitionLocation.getUri().toAbsolutePath
                  if canRenameSymbol(occurence.symbol, None) &&
                    (isWorkspaceSymbol(occurence.symbol, definitionPath) ||
                      findRenamedImportForSymbol(
                        source,
                        soughtSymbols,
                        occurence.symbol.desc.name.value,
                      ).isDefined)
                  range <- occurence.range
                } yield range
            }
          }
      }
  }

  def rename(
      params: RenameParams,
      token: CancelToken,
  ): Future[WorkspaceEdit] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    val localRename = compilers
      .rename(params, token)
      .map(_.asScala.toList)
    localRename
      .filter(_.nonEmpty)
      .map(edits =>
        new WorkspaceEdit(
          documentEdits(Map(source -> edits)).asJava
        )
      )
      .recoverWith { case _ =>
        compilations
          .compilationFinished(source, compileInverseDependencies = true)
          .flatMap { _ =>
            workspaceRename(params, token, source)
          }
      }
  }

  private def workspaceRename(
      params: RenameParams,
      token: CancelToken,
      source: AbsolutePath,
  ): Future[WorkspaceEdit] = {
    val definitionFuture = definitionProvider
      .definition(source, params, token)
    definitionFuture
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
          mbtReferenceProvider
            .enclosingOccurrences(params.getPosition(), source)
            .headOption
            .orElse(
              findRenamedImportOccurrenceAtPosition(
                source,
                params.getPosition(),
              )
            )
            .map { occ => LspOccurrence(occ.symbol, occ.range.map(_.toLsp)) }
            .orElse(
              occurrenceAtDefinition(source, definition, params.getPosition())
            )
            .toSeq
        val suggestedName = params.getNewName()
        val withoutBackticks =
          if (suggestedName.isBackticked)
            suggestedName.stripBackticks
          else suggestedName
        val newName = Identifier.backtickWrap(withoutBackticks)
        def isNotRenamedSymbol(
            text: String,
            occ: LspOccurrence,
        ): Boolean = {
          def realName = occ.symbol.desc.name.value
          def foundName =
            occ.range
              .flatMap { rng =>
                rng.inString(text)
              }
              .map(_.stripBackticks.stripSuffix(","))
          val notRenamed =
            occ.symbol.isLocal || foundName.contains(realName)
          if (!notRenamed)
            scribe.debug(s"Expected $realName, but found $foundName")
          notRenamed
        }

        val allReferences =
          for {
            occurence <- symbolOccurrence.iterator
            text <- buffers.get(source).iterator
            definitionLoc <-
              definition.locations.asScala.headOption.toIterable
            definitionPath = definitionLoc.getUri().toAbsolutePath
            if canRenameSymbol(occurence.symbol, Option(newName)) &&
              isWorkspaceSymbol(occurence.symbol, definitionPath) &&
              isNotRenamedSymbol(text, occurence) || source.isJava
            txtParams <-
              if (definitionTextParams.nonEmpty)
                definitionTextParams
              else List(textParams)
            currentReferences =
              mbtReferenceProvider
                .references(
                  toReferenceParams(
                    txtParams,
                    includeDeclaration = true,
                  ),
                  findRealRange = AdjustRange(findRealRange(newName)),
                  includeSynthetic,
                )
                .map { refs =>
                  scribe.debug(s"Found ${refs.size} basic references")
                  refs.flatMap(_.locations)
                }
            definitionLocation =
              definition.locations.asScala
                .filter(_.getUri().isScalaOrJavaFilename)
                .map(findDefinitionRage)
            companionRefs = companionReferences(
              occurence.symbol,
              source,
              newName,
            )
          } yield Future
            .sequence(
              List(currentReferences, companionRefs)
            )
            .map(
              _.flatten ++ definitionLocation
            )
        Future
          .sequence(allReferences)
          .map(locs =>
            (
              locs.flatten.filterNot(_.getRange().isOffset),
              symbolOccurrence,
              definition,
              newName,
            )
          )
      }
      .map { case (allReferences, symbolOccurrence, definition, newName) =>
        def isOccurrence(fn: String => Boolean): Boolean = {
          symbolOccurrence.exists { occ =>
            fn(occ.symbol)
          }
        }

        // If we didn't find any references then it might be a renamed symbol `import a.{ B => C }`
        val fallbackOccurences =
          if (allReferences.isEmpty)
            renamedImportOccurrences(
              source,
              symbolOccurrence.headOption,
              mbtReferenceProvider.textDocument(source),
            )
          else allReferences

        if (fallbackOccurences.isEmpty) {
          scribe.debug(
            s"Symbol occurence was $symbolOccurrence"
          )
          scribe.debug(s"""|The definition found was:
                           | - path ${definition.definition}
                           | - symbol ${definition.symbol}
                           |""".stripMargin)
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
          !clientConfig
            .isOpenFilesOnRenameProvider() || fileChanges.keySet.size >= clientConfig
            .renameFileThreshold()
        val (openedEdits, closedEdits) =
          if (shouldRenameInBackground) {
            if (clientConfig.isOpenFilesOnRenameProvider()) {
              client.showMessage(
                fileThreshold(fileChanges.keySet.size)
              )
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

  private def findDefinitionRage(location: Location): Location = {
    val adjustedPosition = for {
      source <- location.getUri().toAbsolutePathSafe
      tree <- trees.get(source)
      pos <- location
        .getRange()
        .getStart()
        .toMeta(Input.VirtualFile(source.toString(), tree.text))
      token <- tree.tokens.collectFirst {
        case token: Ident if token.pos.contains(pos) => token
      }
    } yield token.pos.toLsp
    adjustedPosition.map(new Location(location.getUri(), _)).getOrElse(location)
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
      symbolOccurrence: Option[LspOccurrence],
      semanticDb: TextDocument,
  ): Seq[Location] = {
    lazy val uri = source.toURI.toString()

    def withoutBacktick(str: String) = str.stripPrefix("`").stripSuffix("`")
    def occurrences(
        semanticDb: TextDocument,
        occurence: LspOccurrence,
        renameName: String,
        isSymbol: String => Boolean,
    ) = {
      for {
        occ <- semanticDb.occurrences
        rng <- occ.range
        realName <- rng.inString(semanticDb.text)
        if isSymbol(occurence.symbol) &&
          withoutBacktick(realName) == withoutBacktick(renameName)
      } yield new Location(uri, rng.toLsp)
    }
    val result = for {
      occurence <- symbolOccurrence
      soughtSymbols = Set(occurence.symbol) ++ companion(occurence.symbol)
      rename <- findRenamedImportForSymbol(
        source,
        soughtSymbols,
        occurence.symbol.desc.name.value,
      )
      renamedOccurences = occurrences(
        semanticDb,
        occurence,
        rename.rename.value,
        soughtSymbols,
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
      val editsAsEither =
        edits.map(e => LSPEither.forLeft[TextEdit, SnippetTextEdit](e)).asJava
      val ed = new TextDocumentEdit(textId, editsAsEither)
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
          val desc = constructorOwner(str)
          val ext = if (file.isJava) "java" else "scala"
          val name = desc.displayName
          desc.owner.isPackage &&
          (desc.isType || desc.isTerm) &&
          file.toURI.toString.endsWith(s"/$name.$ext")
        }
      }
      .map { file =>
        val uri = file.toURI.toString
        val newFile =
          if (file.isJava)
            uri.replaceAll("/[^/]+\\.java$", s"/$newName.java")
          else
            uri.replaceAll("/[^/]+\\.scala$", s"/$newName.scala")
        LSPEither.forRight[TextDocumentEdit, ResourceOperation](
          new RenameFile(uri, newFile)
        )
      }
  }

  /**
   * Utility to be able to calculate a fallback symbol ooccurrence
   */
  private case class LspOccurrence(symbol: String, range: Option[LSPRange])

  /**
   * In case of macros semanticdb doesn't point correctly at definition.
   */
  private def occurrenceAtDefinition(
      source: AbsolutePath,
      definition: DefinitionResult,
      pos: Position,
  ): Option[LspOccurrence] = {
    val all = for {
      location <- definition.locations.asScala
      if location.getUri().toAbsolutePath.equals(source) && location
        .getRange()
        .encloses(pos)
    } yield LspOccurrence(definition.symbol, Some(location.getRange()))
    all.headOption
  }

  private def constructorOwner(str: String): Symbol = {
    Symbol(str) match {
      case GlobalSymbol(clsSymbol, Descriptor.Method("<init>", _)) =>
        clsSymbol
      case symbol =>
        symbol
    }
  }

  private def companionReferences(
      sym: String,
      source: AbsolutePath,
      newName: String,
  ): Future[Seq[Location]] = {
    val results = for {
      companionSymbol <- companion(sym).toIterable
      loc <-
        definitionProvider
          .fromSymbol(companionSymbol, Some(source))
          .asScala
      // no companion objects in Java files
      if loc.getUri().isScalaFilename
    } yield {
      mbtReferenceProvider
        .references(
          toReferenceParams(loc, includeDeclaration = true),
          findRealRange = AdjustRange(findRealRange(newName)),
          withDefinitionFallback = Some(sym),
        )
        .map(_.flatMap(_.locations :+ loc))
    }
    Future.sequence(results).map(_.flatten.toSeq)
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
  ): Option[SymbolOccurrence] = {
    val renameOpt = trees.findLastEnclosingAt[Importee.Rename](source, pos)
    for {
      rename <- renameOpt
      occ <- mbtReferenceProvider
        .enclosingOccurrences(rename.name.pos.toLsp.getStart(), source)
        .headOption
    } yield (occ.copy(range = Some(rename.rename.pos.toSemanticdb)))
  }

  private def findRenamedImportForSymbol(
      source: AbsolutePath,
      isSymbol: String => Boolean,
      displayName: => String,
  ): Option[Importee.Rename] = {
    // make sure it's not just a rename with the same base name
    def isCorrectSymbolOcccurrence(rename: Importee.Rename) = {
      mbtReferenceProvider
        .enclosingOccurrences(rename.name.pos.toLsp.getStart(), source)
        .headOption
        .exists { occ => isSymbol(occ.symbol) }
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

    val isWorkspace = symbol.isLocal || isFromWorkspace

    if (!isWorkspace) scribe.debug(s"$symbol not found int workspace")
    isWorkspace
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
    lazy val owner = constructorOwner(symbol)
    lazy val nameString = owner.displayName

    val isExplicitVarSetter =
      name.exists(nm => nm.endsWith("_=") || nm.endsWith("_=`"))
    val isBackticked = name.exists(_.isBackticked)
    val commaIncluded = name.exists(_.endsWith(","))

    lazy val symbolName =
      if (!isExplicitVarSetter) nameString.stripSuffix("_=").stripSuffix(",")
      else nameString

    lazy val realName =
      if (isBackticked)
        name.map(_.stripBackticks)
      else name

    lazy val alternativeName =
      if (symbolName == "apply" || symbolName == "unapply")
        Some(symbol.owner).filter(_ != Symbols.None).map(_.desc.name.toString())
      else None
    if (
      symbol.isLocal || realName
        .contains(symbolName) || alternativeName.exists(realName.contains)
    ) {
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

      val withoutComma = if (commaIncluded) {
        withoutBacktick.withEndCharacter(withoutBacktick.endCharacter - 1)
      } else withoutBacktick

      val realRange =
        if (isExplicitVarSetter && !newName.endsWith("_="))
          withoutComma.withEndCharacter(withoutComma.endCharacter - 2)
        else withoutComma
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
        mbtReferenceProvider
          .enclosingOccurrences(textParams.getPosition(), locSource)
          .exists { occ => occ.symbol.desc.name.value != "apply" }
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
