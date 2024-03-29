package scala.meta.internal.telemetry

sealed trait ReporterContext
case class ScalaPresentationCompilerContext(
    scalaVersion: String,
    options: List[String],
    config: PresentationCompilerConfig,
) extends ReporterContext

case class MetalsLspContext(
    metalsVersion: String,
    userConfig: MetalsUserConfiguration,
    serverConfig: MetalsServerConfiguration,
    clientInfo: MetalsClientInfo,
    buildServerConnections: List[BuildServerConnection],
) extends ReporterContext

case class MetalsClientInfo(name: Option[String], version: Option[String])
case class BuildServerConnection(name: String, version: String, isMain: Boolean)
