package scala.meta.internal.metals.eval

object MetalsDebugProtocol {
  final case class LaunchParameters(
      cwd: String,
      mainClass: String,
      buildTarget: String
  )
}
