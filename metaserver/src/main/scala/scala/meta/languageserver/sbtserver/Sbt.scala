package scala.meta.languageserver.sbtserver

import scala.meta.languageserver.SbtExecParams
import scala.meta.languageserver.SbtInitializeParams
import scala.meta.languageserver.SbtInitializeResult
import monix.eval.Task
import monix.execution.Scheduler
import org.langmeta.jsonrpc.Endpoint
import org.langmeta.jsonrpc.JsonRpcClient

trait Sbt {
  object initialize
      extends Endpoint[SbtInitializeParams, SbtInitializeResult]("initialize")
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
