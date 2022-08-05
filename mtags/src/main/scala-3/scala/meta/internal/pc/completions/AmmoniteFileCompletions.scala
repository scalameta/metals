package scala.meta.internal.pc
package completions

import java.nio.file.Path
import java.{util as ju}

import scala.collection.JavaConverters.*

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.AutoImport
import scala.meta.io.AbsolutePath

import dotty.tools.dotc.ast.tpd.Tree
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.ast.untpd.ImportSelector
import org.eclipse.{lsp4j as l}

object AmmoniteFileCompletions:

  private def translateImportToPath(tree: Tree): String =
    tree match
      case Select(qual, name) =>
        val pathPart = name.toString()
        translateImportToPath(qual) + "/" + {
          if pathPart == "$up" then ".."
          else pathPart
        }
      case Ident(_) =>
        ""
      case _ => ""

  def contribute(
      select: Tree,
      selector: List[ImportSelector],
      editRange: l.Range,
      rawPath: String,
      workspace: Option[Path],
      rawFileName: String,
  ): List[CompletionValue] =

    val fileName = rawFileName
      .split("/")
      .last
      .stripSuffix(".amm.sc.scala")

    val split = rawPath
      .split("\\$file")
      .toList

    val query = selector.collectFirst { case sel: ImportSelector =>
      sel.name.toString.replace(Cursor.value, "")

    }

    def parent =
      val name = "^"

      CompletionValue.FileSystemMember(
        isDirectory = true,
        name,
        editRange,
      )

    (split, workspace) match
      case (_ :: script :: Nil, Some(workspace)) =>
        // drop / or \
        val current = workspace.resolve(script.drop(1))
        val importPath = translateImportToPath(select).drop(1)
        val currentPath = AbsolutePath(
          current.getParent.resolve(importPath)
        )
        val parentTextEdit =
          if query
              .exists(
                _.isEmpty()
              ) && currentPath.parentOpt.isDefined && currentPath.isDirectory
          then List(parent)
          else Nil
        currentPath.list.toList
          .filter(_.filename.stripSuffix(".sc") != fileName)
          .collect {
            case file
                if (file.isDirectory || file.isAmmoniteScript) && query
                  .exists(
                    CompletionFuzzy.matches(_, file.filename)
                  ) =>
              CompletionValue.FileSystemMember(
                isDirectory = file.isDirectory,
                file.filename,
                editRange,
              )
          } ++ parentTextEdit
      case _ =>
        Nil
    end match
  end contribute
end AmmoniteFileCompletions
