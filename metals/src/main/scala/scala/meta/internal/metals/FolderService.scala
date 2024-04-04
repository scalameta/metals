package scala.meta.internal.metals

trait FolderService { self: Folder =>
  def service: MetalsLspService
}
