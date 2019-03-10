package scala.meta.internal.pc

import java.util.Optional
import scala.meta.pc.PresentationCompilerConfig

case class PresentationCompilerConfigImpl(
    parameterHintsCommand: Optional[String] = Optional.empty()
) extends PresentationCompilerConfig
