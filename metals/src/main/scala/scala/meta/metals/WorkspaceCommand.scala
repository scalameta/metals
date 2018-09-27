package scala.meta.metals

import enumeratum.Enum
import enumeratum.EnumEntry
import enumeratum.EnumEntry.Uncapitalised

sealed trait WorkspaceCommand extends EnumEntry with Uncapitalised

case object WorkspaceCommand extends Enum[WorkspaceCommand] {

  case object ClearIndexCache extends WorkspaceCommand
  case object ResetPresentationCompiler extends WorkspaceCommand
  case object ScalafixUnusedImports extends WorkspaceCommand
  case object SbtConnect extends WorkspaceCommand
  case object DownloadDebugPayload extends WorkspaceCommand

  val values = findValues

}
