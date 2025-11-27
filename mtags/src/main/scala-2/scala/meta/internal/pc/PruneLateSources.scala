package scala.meta.internal.pc

import scala.collection.mutable
import scala.reflect.internal.Flags
import scala.reflect.io.AbstractFile
import scala.tools.nsc.transform.Transform

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A simple Transform component that removes method and field bodies for sources that are
 * loaded from the source path. In the compiler source code, these are called "compiled late",
 * hence the name.
 */
trait PruneLateSources extends Transform {
  import global._

  val phaseName = "metals-prune"
  val runsAfter: List[String] = List("parser")
  // we'd like to run right after parser, but the bm4 plugin is also running right after parser,
  // leading to conflits (we have tests that exercise it)
  override val runsRightAfter: Option[String] = None
  override val runsBefore: List[String] = List("namer")

  private def mkTripleQMark(): Tree =
    gen.mkNullaryCall(definitions.Predef_???, Nil)

  /** Files that were loaded from the source path and that need to be pruned. */
  val loadedFromSource: mutable.HashSet[AbstractFile]

  lazy val logger: Logger = LoggerFactory.getLogger("MetalsGlobal")

  def newTransformer(unit: CompilationUnit): Transformer =
    new PruneLateSourcesTransformer

  class PruneLateSourcesTransformer extends Transformer {
    override def transformUnit(unit: CompilationUnit): Unit = {
      if (loadedFromSource.contains(unit.source.file)) {
        logger.debug(s"Pruning late sources: ${unit.source.file.path}")
        super.transformUnit(unit)
      }
    }

    /** The tree is an empty type tree or is an inferred type tree that replaced a missing type annotation. */
    private def emptyTypeTree(tree: Tree): Boolean = tree match {
      case tt @ TypeTree() => tt.original == null || tt.original.isEmpty
      case _ => tree.isEmpty
    }

    /**
     * A definition can't be removed if:
     * - it has no explicit type
     * - it's abstract/deferred
     */
    private def shouldKeep(tree: ValOrDefDef): Boolean =
      ((emptyTypeTree(tree.tpt))
        || tree.rhs.isEmpty)

    override def transform(tree: Tree): Tree = {
      tree match {
        case dd @ DefDef(mods, name, tparams, vparamss, tpt, rhs)
            if !nme.isConstructorName(name) && !mods.hasFlag(
              Flags.MACRO | Flags.DEFERRED | Flags.CASEACCESSOR
            ) =>
          if (shouldKeep(dd)) {
            logTreatedMember("Keeping", name.toString, tree.pos)
            super.transform(tree)
          } else {
            logTreatedMember("Removing", name.toString, tree.pos)
            treeCopy.DefDef(
              tree,
              mods,
              name,
              tparams,
              vparamss,
              tpt,
              atPos(rhs.pos)(mkTripleQMark())
            )
          }

        case vd @ ValDef(mods, name, tpt, rhs)
            if !mods.hasFlag(
              Flags.DEFAULTPARAM | Flags.DEFERRED | Flags.PARAM | Flags.CASEACCESSOR
            ) =>
          if (shouldKeep(vd)) {
            logTreatedMember("Keeping", name.toString, tree.pos)
            super.transform(tree)
          } else {
            logTreatedMember("Removing", name.toString, tree.pos)
            treeCopy.ValDef(
              tree,
              mods,
              name,
              tpt,
              atPos(rhs.pos)(mkTripleQMark())
            )
          }

        case _ =>
          super.transform(tree)
      }
    }

    private def logTreatedMember(
        prefix: String,
        name: String,
        pos: Position
    ): Unit = {
      if (pos.isDefined) {
        log(s"$prefix $name at ${pos.source.file.name}:${pos.line}")
      }
    }

  }

}
