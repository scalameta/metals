package scala.meta.internal.metals

import scala.meta._

object ScalametaSourceCodeTransformer
    extends SourceCodeTransformer[Dialect, Tree] {
  private val availableDialects = {
    import scala.meta.dialects._
    val mainDialects = Seq(
      Dialect.current,
      Scala3,
      Scala213,
      Sbt1,
      Scala3Future,
      Scala212
    )
    val auxilaryDialects = Seq(
      Scala213Source3,
      Scala212Source3,
      Scala31,
      Scala32,
      Scala33,
      Scala211,
      Scala210,
      Sbt0137,
      Sbt0136
    )
    (mainDialects ++ auxilaryDialects)
  }

  override def parse(source: String): Option[(Dialect, Tree)] =
    availableDialects.iterator
      .map { implicit dialect: meta.Dialect =>
        dialect -> parse(source, dialect)
      }
      .collectFirst { case (dialect, Some(tree)) => dialect -> tree }

  override def parse(source: String, context: Dialect): Option[Tree] =
    context(source).parse[Source].toOption

  override def toSourceString(value: Tree, ctx: Dialect): String =
    value.syntax

  override def transformer: ASTTransformer = ScalaMetaTransformer

  private object ScalaMetaTransformer extends Transformer with ASTTransformer {
    override protected type Name = meta.Name
    override protected type TermName = meta.Term.Name
    override protected type TypeName = meta.Type.Name
    override protected type UnclasifiedName = meta.Name.Indeterminate

    override protected def toTermName(name: String): TermName =
      meta.Term.Name(name)
    override protected def toTypeName(name: String): TypeName =
      meta.Type.Name(name)
    override protected def toUnclasifiedName(name: String): UnclasifiedName =
      meta.Name.Indeterminate(name)
    override protected def toSymbol(name: Name): String = name.value

    override def sanitizeSymbols(tree: Tree): Option[Tree] = Option(
      this.apply(tree)
    )

    override def apply(tree: Tree): Tree = {
      tree match {
        case name: Name if isCommonScalaName(name) => name
        case node: Term.Select if isScalaOrJavaSelector(node.toString()) => node
        case node: Type.Select if isScalaOrJavaSelector(node.toString()) => node
        case node: Type.Name => sanitizeTypeName(node)
        case node: Term.Name => sanitizeTermName(node)
        case node: Name.Indeterminate => sanitizeUnclasifiedName(node)
        case lit: Lit.String => Lit.String(sanitizeStringLiteral(lit.value))
        case lit: Lit.Symbol => Lit.Symbol(santitizeScalaSymbol(lit.value))
        case x => super.apply(x)
      }
    }

  }

}
