package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}

object ScalacDiagnostic {

  object SymbolNotFound {
    private val regex = """not found: (value|type) (\w+)""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessage() match {
        case regex(_, name) => Some(name)
        case _ => None
      }
  }
  object MissingImplementation {
    // https://github.com/scala/scala/blob/4c0f49c7de6ba48f2b0ae59e64ea94fabd82b4a7/src/compiler/scala/tools/nsc/typechecker/RefChecks.scala#L566
    private val regex = """(?s).*needs to be abstract.*""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessage() match {
        case regex() => Some(d.getMessage())
        case _ => None
      }
  }
  object ObjectCreationImpossible {
    // https://github.com/scala/scala/blob/4c0f49c7de6ba48f2b0ae59e64ea94fabd82b4a7/src/compiler/scala/tools/nsc/typechecker/RefChecks.scala#L564
    private val regex = """(?s).*object creation impossible.*""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessage() match {
        case regex() => Some(d.getMessage())
        case _ => None
      }
  }

  object SymbolNotFoundWithImportSuggestions {
    // https://github.com/lampepfl/dotty/blob/3b741d67f8631487aa553c52e03ac21157e68563/compiler/src/dotty/tools/dotc/typer/ImportSuggestions.scala#L311-L344
    private val regexes = for {
      fix <- List("One of the following imports", "The following import")
      help <- List("fix ", "make progress towards fixing")
    } yield s"""(?s)$fix imports might $help the problem:(.*)""".r
    def unapply(d: l.Diagnostic): Option[List[String]] =
      regexes.collectFirst {
        case regex if regex.findFirstIn(d.getMessage()).isDefined =>
          val suggestedImports = regex
            .findFirstMatchIn(d.getMessage())
            .map(
              _.group(1).linesIterator.filterNot(_.isEmpty()).map(_.trim).toList
            )
          suggestedImports.getOrElse(Nil)
      }
  }
}
