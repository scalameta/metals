package scala.meta.languageserver.ctags

import scala.meta._
import org.langmeta.inputs.Input

object ScalaCtags {
  def index(input: Input.VirtualFile): CtagsIndexer = {
    val root: Source = input.parse[Source].get
    new Traverser with CtagsIndexer {
      override def language: String =
        "Scala212" // TODO(olafur) more accurate dialect
      override def indexRoot(): Unit = apply(root)
      override def apply(tree: Tree): Unit = {
        val old = currentOwner
        val next = tree match {
          case t: Source => Continue
          case t: Template => Continue
          case t: Pkg => pkg(t.ref); Continue
          case t: Pkg.Object => term(t.name, PACKAGEOBJECT); Continue
          case t: Defn.Class => tpe(t.name, CLASS); Continue
          case t: Defn.Trait => tpe(t.name, TRAIT); Continue
          case t: Defn.Object => term(t.name, OBJECT); Continue
          case t: Defn.Def => term(t.name, DEF); Stop
          case Defn.Val(_, Pat.Var(name) :: Nil, _, _) => term(name, DEF); Stop
          case Defn.Var(_, Pat.Var(name) :: Nil, _, _) => term(name, DEF); Stop
          case _ => Stop
        }
        next match {
          case Continue => super.apply(tree)
          case Stop => () // do nothing
        }
        currentOwner = old
      }
    }
  }
}
