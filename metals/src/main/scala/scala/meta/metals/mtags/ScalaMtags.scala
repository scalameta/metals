package scala.meta.metals.mtags

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
        def defTerm(name: Name, paramss: Seq[Seq[Term.Param]]) = {
          for {
            params <- paramss
            tpes = params.flatMap(_.decltpe)
            names = tpes.map(getDisambiguator)
          } withOwner() {
            val params = names.mkString("(", ",", ")")
            super.method(name, params, DEF)
          }
          stop()
        }
        tree match {
          case t: Source => continue()
          case t: Template => continue()
          case t: Pkg => pkg(t.ref); continue()
          case t: Pkg.Object =>
            term(t.name, PACKAGEOBJECT);
            term("package", t.name.pos, OBJECT);
            continue()
          case t: Defn.Class =>
            tpe(t.name, CLASS)
            for {
              params <- t.ctor.paramss
              param <- params
            } withOwner() {
              // TODO(olafur) More precise flags, we add VAL here blindly even if
              // it's not a val, it might even be a var!
              super.param(param.name, VAL | PARAM)
            }
            continue()
          case t: Defn.Trait => tpe(t.name, TRAIT); continue()
          case t: Defn.Object => term(t.name, OBJECT); continue()
          case t: Defn.Type => tpe(t.name, TYPE); stop()
          case t: Decl.Type => tpe(t.name, TYPE); stop()
          case t: Defn.Def => defTerm(t.name, t.paramss)
          case t: Decl.Def => defTerm(t.name, t.paramss)
          case t: Defn.Val => pats(t.pats, VAL); stop()
          case t: Decl.Val => pats(t.pats, VAL); stop()
          case t: Defn.Var => pats(t.pats, VAR); stop()
          case t: Decl.Var => pats(t.pats, VAR); stop()
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
      case d: Type.Method => d.paramss.flatten.flatMap(param => param.decltpe).map(getDisambiguator).mkString(",")
      case d: Type.Function => d.params.map(getDisambiguator).mkString(",")
      case d: Type.ImplicitFunction => d.params.map(getDisambiguator).mkString(",")
      case d: Type.Tuple => d.args.map(getDisambiguator).mkString(",")
      case d: Type.ByName => s"=>${getDisambiguator(d.tpe)}"
      case d: Type.Repeated => getDisambiguator(d.tpe)+"*"
      case d: Type.With => "{}"
      case d: Type.Refine => "{}"
      case d => "?"
    }
}
