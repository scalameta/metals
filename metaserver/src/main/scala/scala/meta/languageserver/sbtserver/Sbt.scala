package scala.meta.languageserver.sbtserver

import scala.meta.languageserver.SbtExecParams
import scala.meta.languageserver.SbtInitializeParams
import scala.meta.languageserver.SbtInitializeResult
import scala.meta.languageserver.SettingParams
import scala.meta.languageserver.SettingResult
import monix.eval.Task
import monix.execution.Scheduler
import org.langmeta.jsonrpc.Endpoint
import org.langmeta.jsonrpc.JsonRpcClient
import org.langmeta.jsonrpc.Response

trait Sbt {
  object initialize
      extends Endpoint[SbtInitializeParams, SbtInitializeResult]("initialize")
  object setting extends Endpoint[SettingParams, SettingResult]("sbt/setting") {
    def query(setting: String)(
        implicit client: JsonRpcClient
    ): Task[Either[Response.Error, SettingResult]] =
      super.request(SettingParams(setting))

  }
  object exec extends Endpoint[SbtExecParams, Unit]("sbt/exec") {
    def apply(
        commandLine: String
    )(implicit client: JsonRpcClient, s: Scheduler): Task[Unit] = {
      // NOTE(olafur) sbt/exec is a request that never responds
      super.request(SbtExecParams(commandLine)).map(_ => Unit)
    }
  }
}

object Sbt extends Sbt
