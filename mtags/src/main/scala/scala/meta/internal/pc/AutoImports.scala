package scala.meta.internal.pc

import scala.reflect.internal.FatalError

trait AutoImports { this: MetalsGlobal =>

  /**
   * A position to insert new imports
   *
   * @param offset the offset where to place the import.
   * @param indent the indentation at which to place the import.
   * @param padTop whether the import needs to be padded on top
   *               in the case that it is the first one after the pacage def
   */
  case class AutoImportPosition(
      offset: Int,
      indent: Int,
      padTop: Boolean
  ) {
    def this(offset: Int, text: String, padTop: Boolean) =
      this(offset, inferIndent(offset, text), padTop)
  }

  def doLocateImportContext(
      pos: Position,
      autoImport: Option[AutoImportPosition]
  ): Context = {
    try doLocateContext(
      autoImport.fold(pos)(i => pos.focus.withPoint(i.offset))
    )
    catch {
      case _: FatalError =>
        (for {
          unit <- getUnit(pos.source)
          tree <- unit.contexts.headOption
        } yield tree.context).getOrElse(NoContext)
    }
  }

  def autoImportPosition(
      pos: Position,
      text: String
  ): Option[AutoImportPosition] = {
    if (lastVisistedParentTrees.isEmpty) {
      locateTree(pos)
    }
    lastVisistedParentTrees.headOption match {
      case Some(_: Import) => None
      case _ =>
        val enclosingPackage = lastVisistedParentTrees.collectFirst {
          case pkg: PackageDef => pkg
        }
        enclosingPackage match {
          case Some(pkg)
              if pkg.symbol != rootMirror.EmptyPackage ||
                pkg.stats.headOption.exists(_.isInstanceOf[Import]) =>
            val lastImport: (Tree, Boolean) = pkg.stats
              .takeWhile(_.isInstanceOf[Import])
              .lastOption // if there are no imports, return true to pad top of import
              .fold[(Tree, Boolean)]((pkg.pid, true))((tree => (tree, false)))

            Some(
              new AutoImportPosition(
                pos.source.lineToOffset(lastImport._1.pos.focusEnd.line),
                text,
                lastImport._2
              )
            )
          case _ =>
            Some(AutoImportPosition(0, 0, false))
        }
    }
  }

}
