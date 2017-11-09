package scala.meta.languageserver

import scala.meta.Defn
import scala.meta.Defn
import scala.meta.Name
import scala.meta.Pat
import scala.meta.Pkg
import scala.meta.Source
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta.Type
import scala.meta.transversers.Traverser
import org.langmeta.inputs.Input
import org.langmeta.inputs.Position
import org.langmeta.semanticdb._

/**
 * Syntactically build a semanticdb containing only global symbol definition.
 *
 * The purpose of this module is to provide "Go to definition" from
 * project sources to dependency sources without indexing classfiles or
 * requiring dependencies to publish semanticdbs alongside their artifacts.
 *
 * One other use-case for this module is to implement "Workspace symbol provider"
 * without any build-tool or compiler integration. Essentially, ctags.
 */
object DefinitionIndexer {
  def index(filename: String, contents: String): Document = {
    val input = Input.VirtualFile(filename, contents)
    val tree = {
      import scala.meta._
      input.parse[Source].get
    }
    val traverser = new DefinitionTraverser
    val (names, symbols) = traverser.index(tree)
    Document(
      input = input,
      language = "scala212",
      names = names,
      messages = Nil,
      symbols = symbols,
      synthetics = Nil
    )
  }

  val root = Symbol("_root_.")
  sealed abstract class Next extends Product with Serializable
  case object Stop extends Next
  case object Continue extends Next
  class DefinitionTraverser extends Traverser {
    def index(tree: Tree): (List[ResolvedName], List[ResolvedSymbol]) = {
      apply(tree)
      names.result() -> symbols.result()
    }
    private val names = List.newBuilder[ResolvedName]
    private val symbols = List.newBuilder[ResolvedSymbol]
    private var currentOwner: _root_.scala.meta.Symbol = root
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
    def addSignature(
        signature: Signature,
        definition: Position,
        flags: Long
    ): Unit = {
      currentOwner = symbol(signature)
      names += ResolvedName(
        definition,
        currentOwner,
        isDefinition = true
      )
      symbols += ResolvedSymbol(
        currentOwner,
        Denotation(flags, signature.name, "", Nil)
      )
    }
    def symbol(signature: Signature): Symbol =
      Symbol.Global(currentOwner, signature)
    def term(name: Term.Name, flags: Long): Unit =
      addSignature(Signature.Term(name.value), name.pos, flags)
    def tpe(name: Type.Name, flags: Long): Unit =
      addSignature(Signature.Type(name.value), name.pos, flags)
    def pkg(ref: Term.Ref): Unit = ref match {
      case Name(name) =>
        currentOwner = symbol(Signature.Term(name))
      case Term.Select(qual: Term.Ref, Name(name)) =>
        pkg(qual)
        currentOwner = symbol(Signature.Term(name))
    }
  }

}
