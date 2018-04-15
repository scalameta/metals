package scala.meta.metals.mtags

import scala.meta._
import org.langmeta.inputs.Input
import scala.meta.internal.semanticdb3.SymbolInformation.Kind
import scala.meta.internal.semanticdb3.Language
import scala.meta.internal.semanticdb3.SymbolInformation.Property

// TODO, emit correct method overload symbols https://github.com/scalameta/metals/issues/282
object ScalaMtags {
  def index(input: Input.VirtualFile): MtagsIndexer = {
    val root: Source = input.parse[Source].get
    new Traverser with MtagsIndexer {
      override def language: Language = Language.SCALA
      override def indexRoot(): Unit = apply(root)
      override def apply(tree: Tree): Unit = withOwner() {
        def continue(): Unit = super.apply(tree)
        def stop(): Unit = ()
        def pats(ps: List[Pat], kind: Kind, properties: Int): Unit = {
          ps.foreach {
            case Pat.Var(name) => withOwner() { term(name, kind, properties) }
            case _ =>
          }
        }
        def defTerm(name: Name, paramss: Seq[Seq[Term.Param]]) = {
          if (paramss.isEmpty)
            super.method(name, "()", Kind.METHOD, 0)
          else
            for {
              params <- paramss
              tpes = params.flatMap(_.decltpe)
              names = tpes.map(getDisambiguator)
            } withOwner() {
              val ps = names.mkString("(", ",", ")")
              super.method(name, ps, Kind.METHOD, 0)
            }
          stop()
        }
        tree match {
          case _: Source => continue()
          case _: Template => continue()
          case t: Pkg => pkg(t.ref); continue()
          case t: Pkg.Object =>
            term(t.name, Kind.PACKAGE_OBJECT, 0);
            term("package", t.name.pos, Kind.OBJECT, 0);
            continue()
          case t: Defn.Class =>
            tpe(t.name, Kind.CLASS, 0)
            for {
              params <- t.ctor.paramss
              param <- params
            } withOwner() {
              super.param(param.name, Kind.PARAMETER, 0)
            }
            continue()
          case t: Defn.Trait =>
            tpe(t.name, Kind.TRAIT, 0); continue()
          case t: Defn.Object =>
            term(t.name, Kind.OBJECT, 0); continue()
          case t: Defn.Type =>
            tpe(t.name, Kind.TYPE, 0); stop()
          case t: Decl.Type =>
            tpe(t.name, Kind.TYPE, 0); stop()
          case t: Defn.Def =>
            defTerm(t.name, t.paramss)
          case t: Decl.Def =>
            defTerm(t.name, t.paramss)
          case t: Defn.Val =>
            pats(t.pats, Kind.METHOD, Property.VAL.value); stop()
          case t: Decl.Val =>
            pats(t.pats, Kind.METHOD, Property.VAL.value); stop()
          case t: Defn.Var =>
            pats(t.pats, Kind.METHOD, Property.VAR.value); stop()
          case t: Decl.Var =>
            pats(t.pats, Kind.METHOD, Property.VAR.value); stop()
          case _ => stop()
        }
      }
    }
  }

  private def getDisambiguator(t: Type): String =
    t match {
      case d: Type.Name => d.value
      case d: Type.Select => d.name.value
      case d: Type.Project => d.name.value
      case d: Type.Singleton => "type"
      case d: Type.Apply => getDisambiguator(d.tpe)
      case d: Type.Existential => getDisambiguator(d.tpe)
      case d: Type.Annotate => getDisambiguator(d.tpe)
      case d: Type.ApplyInfix => getDisambiguator(d.op)
      case d: Type.Lambda => getDisambiguator(d.tpe)
      case d: Type.Method =>
        d.paramss.flatten
          .flatMap(param => param.decltpe)
          .map(getDisambiguator)
          .mkString(",")
      case d: Type.Function => d.params.map(getDisambiguator).mkString(",")
      case d: Type.ImplicitFunction =>
        d.params.map(getDisambiguator).mkString(",")
      case d: Type.Tuple => d.args.map(getDisambiguator).mkString(",")
      case d: Type.ByName => s"=>${getDisambiguator(d.tpe)}"
      case d: Type.Repeated => getDisambiguator(d.tpe) + "*"
      case d: Type.With => "{}"
      case d: Type.Refine => "{}"
      case d => "?"
    }
}
