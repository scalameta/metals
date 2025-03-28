package scala.meta.internal.pc

import scala.reflect.internal.FatalError

import scala.meta.internal.mtags.MtagsEnrichments._

trait AutoImports { this: MetalsGlobal =>

  def doLocateImportContext(
      pos: Position,
      autoImport: Option[AutoImportPosition] = None
  ): Context = {
    try
      doLocateContext(
        autoImport.fold(pos)(i => pos.focus.withPoint(i.lastImportIndex))
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

  def insertPosFromImports(
      importToAdd: String,
      imports: Seq[Import]
  ): (Int, String, String) = {

    val longest = imports
      .collect { case imp: Import =>
        val prefix = imp.expr
          .toString()
          .zip(importToAdd)
          .takeWhile { case (x, y) => x == y }
          .map(_._1)

        prefix.mkString
      }
      .maxBy { str =>
        str.length()
      }
    val prefixImports = imports.filter(
      _.expr.toString().startsWith(longest)
    )

    val where = prefixImports.takeWhile { imp =>
      val fullName = imp.expr.toString() + "." + imp.selectors.head.name.decoded
      fullName < importToAdd
    }

    where match {
      case Nil => (prefixImports.head.pos.end, "\n", "")
      case head :: next =>
        (head.pos.end, "\n", "")
    }
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
            val func = (importToAdd: String) => {
              val imports = pkg.stats.collect { case imp: Import =>
                imp
              }
              val lastImportOrPkg = lastImportOpt.getOrElse(pkg.pid)

              val (index, newlineBefore, newlineAfter) =
                if (imports.nonEmpty) insertPosFromImports(importToAdd, imports)
                else {
                  (lastImportOrPkg.pos.end, "\n\n", "")
                }

              ImportPosition(
                index,
                newlineBefore,
                newlineAfter
              )
            }
            AutoImportPosition(
              func,
              None,
              lastImportOpt.map(_.pos.start).getOrElse(0)
            )

          }

        def forScript() = {
          val startScriptOffest =
            ScriptFirstImportPosition.scalaCliScStartOffset(text)

          val scriptModuleDefAndPos =
            startScriptOffest.flatMap { offset =>
              val startPos = pos.withStart(offset).withEnd(offset)
              lastVisitedParentTrees
                .collectFirst {
                  case mod: ModuleDef if mod.pos.overlaps(startPos) => mod
                }
                .map(mod => (mod, offset))
            }

          val moduleDefAndPos = scriptModuleDefAndPos.orElse(
            lastVisitedParentTrees
              .collectFirst { case mod: ModuleDef => mod }
              .map(mod => (mod, 0))
          )
          for {
            (obj, firstImportOffset) <- moduleDefAndPos
          } yield {
            val lastImportOpt = obj.impl.body.iterator
              .dropWhile {
                case d: DefDef => d.name.decoded == "<init>"
                case _ => false
              }
              .takeWhile(_.isInstanceOf[Import])
              .lastOption

            val func = (importToAdd: String) => {
              val imports = obj.impl.body.collect { case imp: Import =>
                imp
              }

              val (index, newlineBefore, newlineAfter) =
                if (imports.nonEmpty) insertPosFromImports(importToAdd, imports)
                else {
                  (firstImportOffset, "\n", "\n")
                }

              ImportPosition(
                index,
                newlineBefore,
                newlineAfter
              )
            }
            AutoImportPosition(
              func,
              Some(text),
              lastImportOpt.map(_.pos.start).getOrElse(0)
            )
          }
        }

        def fileStart = AutoImportPosition(
          _ => ImportPosition(ScriptFirstImportPosition.infer(text), "", "\n"),
          Some(""),
          0
        )
        val path = pos.source.path
        val scriptPos =
          if (path.isScalaCLIGeneratedFile) forScript()
          else None

        scriptPos
          .orElse(forScalaSource)
          .orElse(Some(fileStart))
    }
  }

}
