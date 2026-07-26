package scala.meta.internal.metals.scalacli

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

/**
 * Policy for when the fallback Metals service may auto-start Scala CLI for an
 * orphan document.
 *
 * Editor agents (and some LSP clients) can send `textDocument/didOpen` for
 * files outside the configured workspace folder(s). Auto-starting Scala CLI for
 * those paths floods Problems with fake diagnostics and spawns many BSP
 * processes. See https://github.com/scalameta/metals/issues/8736.
 *
 * This is independent of `UserConfiguration.scalaCliEnabled`, which is checked
 * separately when starting Scala CLI.
 */
object ScalaCliAutoStart {

  /**
   * True when `workspaceFolders` is non-empty and `path` is not under any of
   * them.
   */
  def isOutsideWorkspace(
      path: AbsolutePath,
      workspaceFolders: Seq[AbsolutePath],
  ): Boolean =
    workspaceFolders.nonEmpty &&
      !workspaceFolders.exists(folder => path.startWith(folder))

  /**
   * Whether FallbackMetalsLspService should automatically start Scala CLI when
   * `path` is opened.
   *
   * @param path
   *   document path that would be imported via Scala CLI
   * @param workspaceFolders
   *   roots of the LSP workspace folders (Scala and non-Scala). When empty,
   *   keep historical behavior and allow auto-start (standalone session).
   */
  def shouldAutoStart(
      path: AbsolutePath,
      workspaceFolders: Seq[AbsolutePath],
  ): Boolean = {
    if (!path.isScala) false
    else if (workspaceFolders.isEmpty) true
    else !isOutsideWorkspace(path, workspaceFolders)
  }
}
