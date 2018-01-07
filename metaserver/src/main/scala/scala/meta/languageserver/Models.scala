package scala.meta.languageserver

import io.circe.Json
import io.circe.generic.JsonCodec
import org.langmeta.io.AbsolutePath

@JsonCodec case class ActiveJson(uri: String)

@JsonCodec case class SbtInitializeParams(
    initializationOptions: Json = Json.obj()
)
@JsonCodec case class SbtInitializeResult(json: Json)

@JsonCodec case class SbtExecParams(commandLine: String)

case class MissingActiveJson(path: AbsolutePath)
    extends Exception(s"sbt-server 1.1.0 is not running, $path does not exist")
