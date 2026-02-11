package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

final class CodeActionProvider(
    compilers: Compilers,
    workspace: AbsolutePath,
    buffers: Buffers,
    buildTargets: BuildTargets,
    scalafixProvider: ScalafixProvider,
    trees: Trees,
    diagnostics: Diagnostics,
    languageClient: MetalsLanguageClient,
    clientConfig: ClientConfiguration,
)(implicit ec: ExecutionContext) {

  private val extractMemberAction =
    new ExtractRenameMember(trees, languageClient, buffers)

  private val allActions: List[CodeAction] = List(
    new ImplementAbstractMembers(compilers),
    new ImportMissingSymbolQuickFix(compilers, buildTargets),
    new SourceAddMissingImports(compilers, buildTargets, diagnostics),
    new CreateNewSymbol(compilers, languageClient),
    new ActionableDiagnostic(),
    new ExplainDiagnostic(
      compilers,
      workspace,
      languageClient,
      clientConfig,
      buildTargets,
    ),
    new StringActions(trees),
    new RemoveInfixRefactor(trees),
    extractMemberAction,
    new SourceOrganizeImports(scalafixProvider, buildTargets, diagnostics),
    new OrganizeImportsQuickFix(scalafixProvider, buildTargets, diagnostics),
    new InsertInferredType(trees, compilers, languageClient),
    new PatternMatchRefactor(trees),
    new RewriteBracesParensCodeAction(trees),
    new ExtractValueCodeAction(trees, buffers),
    new CreateCompanionObjectCodeAction(trees, buffers),
    new ExtractMethodCodeAction(trees, compilers),
    new InlineValueCodeAction(trees, compilers, languageClient),
    new ConvertToNamedArguments(trees, compilers),
    new FlatMapToForComprehensionCodeAction(trees, buffers),
    new FilterMapToCollectCodeAction(trees, compilers),
    new MillifyDependencyCodeAction(buffers),
    new MillifyScalaCliDependencyCodeAction(buffers),
    new ConvertCommentCodeAction(buffers, trees),
    new RemoveInvalidImportQuickFix(trees, buildTargets),
    new SourceRemoveInvalidImports(trees, buildTargets, diagnostics),
    new ConvertToNamedLambdaParameters(trees, compilers),
  )

  def actionsForParams(params: l.CodeActionParams): List[CodeAction] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val supportedCodeActions = compilers.supportedCodeActions(path)
    allActions.filter(_.maybeCodeActionId.forall(supportedCodeActions.contains))
  }

  def codeActions(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {
    val requestedKinds = Option(params.getContext.getOnly).map(_.asScala.toList)

    def isRequestedKind(action: CodeAction): Boolean =
      requestedKinds match {
        case Some(only) =>
          only.exists(requestedKind => action.kind.startsWith(requestedKind))
        case None => true
      }

    val actions = actionsForParams(params).collect {
      case action if isRequestedKind(action) =>
        action.contribute(params, token)
    }

    Future.sequence(actions).map(_.flatten)
  }

  def executeCommands(
      params: l.ExecuteCommandParams,
      token: CancelToken,
  ): Future[Unit] = {
    val running = for {
      action <- allActions
      actionCommand <- action.command
      data <- actionCommand.unapply(params)
    } yield action.handleCommand(data, token)
    Future.sequence(running).map(_ => ())
  }

  /**
   * Resolved command inside a code action lazily.
   */
  def resolveCodeAction(
      resolvedAction: l.CodeAction,
      token: CancelToken,
  ): Future[l.CodeAction] = {
    val resolved = for {
      action <- allActions
    } yield action.resolveCodeAction(resolvedAction, token)

    resolved.collectFirst { case Some(resolved) =>
      resolved
    } match {
      case None => Future.successful(resolvedAction)
      case Some(resolvingAction) => resolvingAction
    }
  }

  val allActionCommandsIds: Set[String] =
    allActions.flatMap(_.command).map(_.id).toSet

}
