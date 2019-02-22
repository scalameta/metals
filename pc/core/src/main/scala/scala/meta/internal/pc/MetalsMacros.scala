package scala.meta.internal.pc

import scala.tools.nsc.Mode

trait MetalsMacros { this: MetalsGlobal =>

  /**
   * A macro plugin that disables blackbox macro expansion.
   *
   * It's safe to disable blackbox macros because they don't affect typing meaning
   * they cannot change the results from completions/signatureHelp/hover. By disabling
   * blackbox macros we avoid a potentially expensive computation.
   */
  class DisableBlackboxMacrosPlugin extends analyzer.MacroPlugin {
    override def pluginsMacroExpand(
        typer: analyzer.Typer,
        expandee: Tree,
        mode: Mode,
        pt: Type
    ): Option[Tree] = {
      val isBlackbox = analyzer.standardIsBlackbox(expandee.symbol)
      if (isBlackbox) {
        Some(expandee)
      } else {
        None
      }
    }
  }
}
