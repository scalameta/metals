package scala.meta.internal.implementation

import java.nio.file.Path

case class ClassLocation(
    symbol: String,
    file: Option[Path]
)
