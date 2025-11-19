package scala.meta.internal.metals.mbt

import scala.meta.inputs.Input
import scala.meta.internal.metals.WorkspaceSymbolInformation
import scala.meta.io.AbsolutePath

case class OnDidChangeSymbolsParams(
    path: AbsolutePath,
    input: Input.VirtualFile,
    symbols: collection.Seq[WorkspaceSymbolInformation],
    references: collection.Seq[String],
    methodSymbols: collection.Seq[WorkspaceSymbolInformation],
)
