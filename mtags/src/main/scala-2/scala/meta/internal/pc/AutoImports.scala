package scala.meta.internal.pc

import scala.reflect.internal.FatalError

import scala.meta.internal.mtags.MtagsEnrichments._

trait AutoImports { this: MetalsGlobal =>

  def doLocateImportContext(
      pos: Position,
      autoImport: Option[AutoImportPosition] = None
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

  def isImportPosition(pos: Position): Boolean =
    findLastVisitedParentTree(pos).exists(_.isInstanceOf[Import])

  def notPackageObject(pkg: PackageDef): Boolean =
    pkg.stats.exists {
      case ModuleDef(_, name, _) => name.toString != "package"
      case _ => true
    }

  def autoImportPosition(
      pos: Position,
      text: String
  ): Option[AutoImportPosition] = {
    findLastVisitedParentTree(pos) match {
      case Some(_: Import) => None
      case _ =>
        def forScalaSource =
          for {
            pkg <- lastVisitedParentTrees.collectFirst {
              case pkg: PackageDef if notPackageObject(pkg) => pkg
            }
            if pkg.symbol != rootMirror.EmptyPackage ||
              pkg.stats.headOption.exists(_.isInstanceOf[Import])
          } yield {
            val lastImportOpt = pkg.stats
              .takeWhile(_.isInstanceOf[Import])
              .lastOption
            val padTop = lastImportOpt.isEmpty
            val lastImportOrPkg = lastImportOpt.getOrElse(pkg.pid)
            new AutoImportPosition(
              pos.source.lineToOffset(lastImportOrPkg.pos.focusEnd.line),
              text,
              padTop
            )
          }

        def forAmmoniteScript =
          for {
            obj <- lastVisitedParentTrees.collectFirst { case mod: ModuleDef =>
              mod
            }
          } yield {
            val lastImportOpt = obj.impl.body.iterator
              .dropWhile {
                case d: DefDef => d.name.decoded == "<init>"
                case _ => false
              }
              .takeWhile(_.isInstanceOf[Import])
              .lastOption
            val lastImportLine = lastImportOpt
              .map(_.pos.focusEnd.line)
              .getOrElse(0) // if no previous import, add the new one at the top
            new AutoImportPosition(
              pos.source.lineToOffset(lastImportLine),
              text,
              padTop = false
            )
          }

        def fileStart = AutoImportPosition(0, 0, padTop = false)

        (if (pos.source.path.endsWith(".sc.scala")) forAmmoniteScript else None)
          .orElse(forScalaSource)
          .orElse(Some(fileStart))
    }
  }

}
