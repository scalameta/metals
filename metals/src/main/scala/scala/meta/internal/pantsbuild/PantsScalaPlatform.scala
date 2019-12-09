package scala.meta.internal.pantsbuild

import java.nio.file.Path

case class PantsScalaPlatform(
    scalaBinaryVersion: String,
    compilerClasspath: Seq[Path]
)
