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
            term(t.name, Kind.METHOD, 0); stop()
          case t: Decl.Def =>
            term(t.name, Kind.METHOD, 0); stop()
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
}
