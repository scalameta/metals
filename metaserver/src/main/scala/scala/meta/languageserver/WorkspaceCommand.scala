package scala.meta.languageserver

import enumeratum.Enum
import enumeratum.EnumEntry
import enumeratum.EnumEntry.Uncapitalised

sealed trait WorkspaceCommand extends EnumEntry with Uncapitalised

case object WorkspaceCommand extends Enum[WorkspaceCommand] {

  case object ClearIndexCache extends WorkspaceCommand
  case object ResetPresentationCompiler extends WorkspaceCommand

  val values = findValues

}
