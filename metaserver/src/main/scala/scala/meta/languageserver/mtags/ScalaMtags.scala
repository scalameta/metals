package scala.meta.languageserver.mtags

import scala.meta._
import org.langmeta.inputs.Input

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
        def pats(ps: List[Pat], flag: Long): Unit = {
          ps.foreach {
            case Pat.Var(name) => withOwner() { term(name, flag) }
            case _ =>
          }
        }
        tree match {
          case t: Source => continue()
          case t: Template => continue()
          case t: Pkg => pkg(t.ref); continue()
          case t: Pkg.Object => term(t.name, PACKAGEOBJECT); continue()
          case t: Defn.Class => tpe(t.name, CLASS); continue()
          case t: Defn.Trait => tpe(t.name, TRAIT); continue()
          case t: Defn.Object => term(t.name, OBJECT); continue()
          case t: Defn.Type => tpe(t.name, TYPE); stop()
          case t: Decl.Type => tpe(t.name, TYPE); stop()
          case t: Defn.Def => term(t.name, DEF); stop()
          case t: Decl.Def => term(t.name, DEF); stop()
          case t: Defn.Val => pats(t.pats, VAL); stop()
          case t: Decl.Val => pats(t.pats, VAL); stop()
          case t: Defn.Var => pats(t.pats, VAR); stop()
          case t: Decl.Var => pats(t.pats, VAR); stop()
          case _ => stop()
        }
      }
    }
  }
}
