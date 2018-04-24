package scala.meta.metals.sbtserver

import scala.meta.metals.SbtExecParams
import scala.meta.metals.SbtInitializeParams
import scala.meta.metals.SbtInitializeResult
import scala.meta.metals.SettingParams
import scala.meta.metals.SettingResult
import monix.eval.Task
import io.github.lsp4s.jsonrpc.Endpoint
import io.github.lsp4s.jsonrpc.JsonRpcClient
import io.github.lsp4s.jsonrpc.Response

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
    )(implicit client: JsonRpcClient): Task[Unit] = {
      if (commandLine.trim.isEmpty) Task.now(())
      // NOTE(olafur) sbt/exec is a request that never responds
      else super.request(SbtExecParams(commandLine)).map(_ => Unit)
    }
  }
}

object Sbt extends Sbt
