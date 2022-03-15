package scala.meta.internal.pc.completions

import scala.util.control.NonFatal

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.CompletionFuzzy
import scala.meta.internal.pc.MetalsGlobal
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

trait AmmoniteCompletions { this: MetalsGlobal =>

  class FileSystemMember(
      sym: Symbol,
      val isDirectory: Boolean,
      fileName: String,
      editRange: l.Range
  ) extends TextEditMember(
        filterText = fileName,
        edit = new l.TextEdit(editRange, fileName.stripSuffix(".sc")),
        sym,
        label = Some(fileName)
      )

  case class AmmoniteFileCompletions(
      select: Tree,
      selector: List[ImportSelector],
      pos: Position,
      editRange: l.Range
  ) extends CompletionPosition {

    override def contribute: List[Member] =
      try {
        val filename = pos.source.file.name
          .split("/")
          .last
          .stripSuffix(".amm.sc.scala")

        val split = pos.source.file.name
          .split("\\$file")
          .toList

        val query = selector.collectFirst {
          case sel: ImportSelector if sel.name.toString().contains(CURSOR) =>
            sel.name.toString.replace(CURSOR, "")
        }

        def parent = {
          val name = "^"
          new FileSystemMember(
            select.symbol
              .newErrorSymbol(TermName(name))
              .setInfo(NoType),
            isDirectory = true,
            name,
            editRange
          )
        }

        (split, workspace) match {
          case (_ :: script :: Nil, Some(workspace)) =>
            // drop / or \
            val current = workspace.resolve(script.drop(1))
            val importPath = translateImportToPath(select).drop(1)
            val currentPath = AbsolutePath(
              current.getParent.resolve(importPath)
            )
            val parentTextEdit =
              if (
                query
                  .exists(
                    _.isEmpty()
                  ) && currentPath.parentOpt.isDefined && currentPath.isDirectory
              ) {
                List(parent)
              } else {
                Nil
              }
            currentPath.list.toList
              .filter(_.filename.stripSuffix(".sc") != filename)
              .collect {
                case file
                    if (file.isDirectory || file.isAmmoniteScript) && query
                      .exists(
                        CompletionFuzzy.matches(_, file.filename)
                      ) =>
                  new FileSystemMember(
                    select.symbol
                      .newErrorSymbol(
                        TermName(file.filename.stripSuffix(".sc"))
                      )
                      .setInfo(NoType),
                    isDirectory = file.isDirectory,
                    file.filename,
                    editRange
                  )
              } ++ parentTextEdit
          case _ =>
            Nil
        }
      } catch {
        case NonFatal(e) =>
          e.printStackTrace()
          Nil
      }
  }

  private def translateImportToPath(tree: Tree): String =
    tree match {
      case Select(qual, name) =>
        val pathPart = name.toString()
        translateImportToPath(qual) + "/" + {
          if (pathPart == "$up") {
            ".."
          } else {
            pathPart
          }
        }
      case Ident(_) =>
        ""
      case _ => ""
    }
}
