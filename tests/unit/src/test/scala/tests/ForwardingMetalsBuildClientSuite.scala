package tests

import java.util.concurrent.ConcurrentLinkedQueue

import scala.meta.internal.metals.clients.language.ForwardingMetalsBuildClient
import scala.meta.internal.metals.clients.language.MetalsCreateTerminalParams
import scala.meta.internal.metals.clients.language.MetalsEndTerminalParams
import scala.meta.internal.metals.clients.language.MetalsTerminalOutputParams
import scala.meta.internal.metals.clients.language.NoopLanguageClient

import ch.epfl.scala.bsp4j.PrintParams
import ch.epfl.scala.bsp4j.StatusCode
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.TaskId
import com.google.common.collect.HashBiMap

class ForwardingMetalsBuildClientSuite extends BaseSuite {

  test("terminal-lifecycle") {
    val events = new ConcurrentLinkedQueue[Any]()
    val languageClient = new NoopLanguageClient {
      override def metalsCreateTerminal(
          params: MetalsCreateTerminalParams
      ): Unit = events.add(params)

      override def metalsTerminalOutput(
          params: MetalsTerminalOutputParams
      ): Unit = events.add(params)

      override def metalsEndTerminal(params: MetalsEndTerminalParams): Unit =
        events.add(params)
    }
    val terminals = HashBiMap.create[(String, String), String]()
    val buildClient = new ForwardingMetalsBuildClient(
      languageClient = languageClient,
      diagnostics = null,
      buildTargets = null,
      clientConfig = null,
      statusBar = null,
      time = null,
      didCompile = _ => (),
      onBuildTargetDidCompile = _ => (),
      onBuildTargetDidChangeFunc = _ => (),
      bspErrorHandler = null,
      workDoneProgress = null,
      terminals = terminals,
      moduleStatus = null,
    )
    val originId = "run-origin"
    val taskId = new TaskId("mbt-run")
    val output = new PrintParams(originId, "output\n")
    output.setTask(taskId)

    buildClient.runPrintStdout(output)

    val create = events.poll().asInstanceOf[MetalsCreateTerminalParams]
    val terminalOutput =
      events.poll().asInstanceOf[MetalsTerminalOutputParams]
    assertEquals(create.name, taskId.getId)
    assertEquals(terminalOutput.terminalId, create.terminalId)
    assertEquals(terminalOutput.message, "output\n")
    assertEquals(terminals.get((originId, taskId.getId)), create.terminalId)

    val finish = new TaskFinishParams(taskId, StatusCode.OK)
    finish.setOriginId(originId)
    buildClient.buildTaskFinish(finish)

    val end = events.poll().asInstanceOf[MetalsEndTerminalParams]
    assertEquals(
      end,
      MetalsEndTerminalParams(create.terminalId, success = true),
    )
    assert(terminals.isEmpty)
    assert(events.isEmpty)
  }
}
