package scala.meta.internal.pantsbuild

import java.nio.file.Path

case class PantsLibrary(
    name: String,
    values: collection.Map[String, Path]
) {
  def default: Option[Path] = values.get("default")
  def sources: Option[Path] = values.get("sources")
  def nonSources: Iterable[Path] =
    values.collect {
      case (key, path) if key != "sources" =>
        path
    }
}
