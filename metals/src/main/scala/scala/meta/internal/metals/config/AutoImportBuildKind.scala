package scala.meta.internal.metals.config

sealed trait AutoImportBuildKind
object AutoImportBuildKind {
  case object Off extends AutoImportBuildKind
  case object Initial extends AutoImportBuildKind
  case object All extends AutoImportBuildKind
}
