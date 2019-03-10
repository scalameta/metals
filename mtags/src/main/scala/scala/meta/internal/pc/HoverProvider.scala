package scala.meta.internal.pc

import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkedString
import scala.collection.JavaConverters._
import scala.meta.pc.OffsetParams

class HoverProvider(val compiler: MetalsGlobal) {
  import compiler._
  val history = new ShortenedNames()
  def hover(params: OffsetParams): Option[Hover] = {
    val unit = addCompilationUnit(
      code = params.text(),
      filename = params.filename(),
      cursor = None
    )
    val pos = unit.position(params.offset())
    val tree = compiler.typedTreeAt(pos)
    tree match {
      // Special case for named arguments like `until(en@@d = 10)`, in which case
      // we fallback to signature help to extract the named argument parameter.
      case Apply(qual, _) if !qual.pos.includes(pos) =>
        val signatureHelp =
          new SignatureHelpProvider(compiler).signatureHelp(params)
        if (signatureHelp.getActiveParameter >= 0 &&
          signatureHelp.getActiveSignature >= 0) {
          val activeParameter = signatureHelp.getSignatures
            .get(signatureHelp.getActiveSignature)
            .getParameters
            .get(signatureHelp.getActiveParameter)
          Some(
            new Hover(
              List[JEither[String, MarkedString]](
                JEither.forRight(
                  new MarkedString("scala", activeParameter.getLabel)
                ),
                JEither.forLeft(
                  activeParameter.getDocumentation.getRight.getValue
                )
              ).asJava
            )
          )
        } else {
          hoverFromTree(tree)
        }
      case _ =>
        hoverFromTree(tree)
    }
  }

  def hoverFromTree(tree: Tree): Option[Hover] = {
    for {
      tpeName <- typeOfTree(tree)
    } yield
      new Hover(
        List(
          JEither.forRight[String, MarkedString](
            new MarkedString("scala", tpeName)
          )
        ).asJava
      )
  }

  private def typeOfTree(t: Tree): Option[String] = {
    val stringOrTree = t match {
      case t: DefDef => Right(metalsToLongString(t.symbol.info, history))
      case t: ValDef if t.tpt != null => Left(t.tpt)
      case t: ValDef if t.rhs != null => Left(t.rhs)
      case x => Left(x)
    }

    stringOrTree match {
      case Right(string) => Some(string)
      case Left(null) => None
      case Left(tree)
          if tree.tpe != null &&
            tree.tpe != NoType &&
            !tree.tpe.isErroneous =>
        Some(metalsToLongString(tree.tpe, history))
      case _ => None
    }

  }
}
