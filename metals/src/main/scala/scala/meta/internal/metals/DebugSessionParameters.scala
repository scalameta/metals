package scala.meta.internal.metals
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import com.google.gson.JsonElement

case class DebugSessionParameters(
    targets: java.util.List[BuildTargetIdentifier],
    dataKind: String,
    data: JsonElement
)
