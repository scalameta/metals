package scala.meta.languageserver.mtags

import scala.meta._
import org.langmeta.inputs.Input
import scala.meta.internal.semanticdb3.SymbolInformation.Kind
import scala.meta.internal.semanticdb3.SymbolInformation.{Kind => k}

object ScalaMtags {
  def index(input: Input.VirtualFile): MtagsIndexer = {
    val root: Source = input.parse[Source].get
    new Traverser with MtagsIndexer {
      override def language: String =
        "Scala212" // TODO(olafur) more accurate dialect
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
          case t: Source => continue()
          case t: Template => continue()
          case t: Pkg => pkg(t.ref); continue()
          case t: Pkg.Object =>
            term(t.name, k.PACKAGE_OBJECT, 0);
            term("package", t.name.pos, k.OBJECT, 0);
            continue()
          case t: Defn.Class =>
            tpe(t.name, k.CLASS, 0)
            for {
              params <- t.ctor.paramss
              param <- params
            } withOwner() {
              // TODO(olafur) More precise flags, we add VAL here blindly even if
              // it's not a val, it might even be a var!
              // TODO: This is a bug in Scalameta 3.0.0.
              // https://github.com/scalameta/scalameta/pull/1244
              // super.param(param.name, k.PARAM, p.VALPARAM)
              super.param(param.name, k.PARAMETER, 0)
            }
            continue()
          case t: Defn.Trait => tpe(t.name, k.TRAIT, 0); continue()
          case t: Defn.Object => term(t.name, k.OBJECT, 0); continue()
          case t: Defn.Type => tpe(t.name, k.TYPE, 0); stop()
          case t: Decl.Type => tpe(t.name, k.TYPE, 0); stop()
          case t: Defn.Def => term(t.name, k.DEF, 0); stop()
          case t: Decl.Def => term(t.name, k.DEF, 0); stop()
          case t: Defn.Val => pats(t.pats, k.VAL, 0); stop()
          case t: Decl.Val => pats(t.pats, k.VAL, 0); stop()
          case t: Defn.Var => pats(t.pats, k.VAR, 0); stop()
          case t: Decl.Var => pats(t.pats, k.VAR, 0); stop()
          case _ => stop()
        }
      }
    }
  }
}
