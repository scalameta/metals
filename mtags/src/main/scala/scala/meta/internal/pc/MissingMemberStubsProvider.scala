package scala.meta.internal.pc

import scala.meta.pc.OffsetParams
import org.eclipse.lsp4j.TextEdit
import scala.reflect.internal.Flags
import scala.reflect.internal.util.Position

class MissingMemberStubsProvider(
    val compiler: MetalsGlobal,
    params: OffsetParams
) {

  import compiler._

  def memberStubs(): Option[TextEdit] = {
    val unit = addCompilationUnit(
      code = params.text,
      filename = params.filename,
      cursor = Some(params.offset),
      cursorName = ""
    )
    val pos = unit.position(params.offset)

    typedTreeAt(pos)
    findClassDef(pos).flatMap(toTextEdit(_, params.text))
  }

  private def toTextEdit(
      implDef: ImplDef,
      text: String
  ): Option[TextEdit] = {

    val missing = implDef.symbol.info
      .nonPrivateMembersAdmitting(Flags.VBRIDGE)
      .filter({
        case m if m.isAbstractOverride && m.isIncompleteIn(implDef.symbol) =>
          true
        case m if m.isDeferred => true
        case _ => false
      })

    val regrouped = missing.groupBy(_.owner).toList

    val stubs = regrouped
      .flatMap({
        case (_, group) =>
          group.toList
            .sortBy(_.name)
            .flatMap(v => renderMember(v, implDef.symbol.info))
      })

    val bodyIsEmpty = text.charAt(implDef.pos.end - 1) != '}'

    val lineIdentation = {
      val fromClzDef =
        (1 to implDef.pos.start).reverse
          .takeWhile(i => text.charAt(i - 1) != '\n')
          .size
      fromClzDef + 2
    }

    val memberIdent = " " * lineIdentation
    val lastBraceIdent = " " * (lineIdentation - 2)

    val out = new StringBuilder
    if (bodyIsEmpty) out.append(" {")
    out.append("\n")

    val renderedMembers = stubs.map(v => memberIdent + v).mkString("\n")
    out.append(renderedMembers)
    if (bodyIsEmpty) out.append('\n').append(lastBraceIdent).append('}')

    val insertPos =
      if (bodyIsEmpty) {
        Some(implDef.pos.withStart(implDef.pos.end))
      } else {
        lastMemberPos(implDef, text)
      }

    insertPos.map(p => {
      new TextEdit(p.toLSP, out.mkString)
    })

  }

  private def renderMember(m: Symbol, asSeenFrom: Type): Option[String] = {
    if (m.isMethod) {
      val typeInfo = m.infoString(asSeenFrom.memberType(m))
      val str = s"override def ${m.name}$typeInfo = ???"
      Some(str)
    } else {
      None
    }
  }

  private def findClassDef(pos: Position): Option[ImplDef] = {
    def loop(ctx: Context): Option[ImplDef] = {
      if (ctx == NoContext) None
      else {
        val tree = ctx.tree
        tree match {
          case clsdef: ClassDef => Some(clsdef)
          case module: ModuleDef => Some(module)
          case _ => loop(ctx.outer)
        }
      }
    }
    val ctx = doLocateContext(pos)
    loop(ctx)
  }

  private def lastMemberPos(
      implDef: ImplDef,
      text: String
  ): Option[Position] = {
    val range = (implDef.pos.start to implDef.pos.end - 1).reverse
    range
      .dropWhile(i => {
        val c = text.charAt(i - 1)
        c == '\n' || c == ' ' || c == '\t'
      })
      .headOption
      .map(i => Position.offset(implDef.pos.source, i))
  }

}
