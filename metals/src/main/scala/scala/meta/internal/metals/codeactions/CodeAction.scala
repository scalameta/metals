package scala.meta.internal.metals.codeactions

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.reflect.ClassTag

import scala.meta.internal.metals.JsonParser
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ParametrizedCommand
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

trait CodeAction {

  /**
   * This should be one of the String constants
   * listed in [[org.eclipse.lsp4j.CodeActionKind]]
   */
  def kind: String

  private def className = this.getClass().getSimpleName()
  protected trait CodeActionResolveData {
    this: Product =>

    /**
     * Name is neccessary to identify what code action is being resolved.
     *
     * Gson will attempt to fill this field during deserialization,
     * but if it's the wrong data the name will be wrong.
     */
    val codeActionName: String = className

    def notNullFields: Boolean = this.productIterator.forall(_ != null)
  }

  protected def parseData[T <: CodeActionResolveData](
      codeAction: l.CodeAction
  )(implicit clsTag: ClassTag[T]): Option[T] = {
    val parser = new JsonParser.Of[T]
    codeAction.getData() match {
      case parser.Jsonized(data)
          if data.codeActionName == className && data.notNullFields =>
        Some(data)
      case _ => None
    }
  }

  /**
   * The CodeActionId for this code action, if applicable. CodeActionId is only
   * used for code actions that require the use of the presentation compiler.
   */
  def maybeCodeActionId: Option[String] = None

  type CommandData
  type ActionCommand = ParametrizedCommand[CommandData]
  def command: Option[ActionCommand] = None

  @nowarn
  def handleCommand(data: CommandData, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Unit] = Future.unit

  def resolveCodeAction(codeAction: l.CodeAction, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Option[Future[l.CodeAction]] = None

  def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]]

  implicit val actionDiagnosticOrdering: Ordering[l.CodeAction] =
    new Ordering[l.CodeAction] {

      override def compare(
          x: l.CodeAction,
          y: l.CodeAction,
      ): Int = {

        (
          x.getDiagnostics().asScala.headOption,
          y.getDiagnostics().asScala.headOption,
        ) match {
          case (Some(diagx), Some(diagy)) =>
            val startx = diagx.getRange().getStart()
            val starty = diagy.getRange().getStart()
            val line = startx.getLine().compare(starty.getLine())
            if (line == 0) {
              startx.getCharacter().compare(starty.getCharacter())
            } else {
              line
            }
          case (Some(_), None) => 1
          case (None, Some(_)) => -1
          case _ => 0
        }
      }
    }
}
