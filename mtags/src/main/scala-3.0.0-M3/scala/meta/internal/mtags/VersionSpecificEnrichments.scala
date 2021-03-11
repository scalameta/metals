package scala.meta.internal.mtags

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.core.Flags.EmptyFlags
import dotty.tools.dotc.util.NoSourcePosition

trait VersionSpecificEnrichments {

  extension (context: Context) {
    def findRef(name: Name): Type = {
      context.typer.findRef(
        name,
        defn(using context).AnyType,
        EmptyFlags,
        EmptyFlags,
        NoSourcePosition
      )(using context)
    }
  }
}
