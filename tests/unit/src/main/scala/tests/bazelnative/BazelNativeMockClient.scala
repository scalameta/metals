package tests.bazelnative

import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters._

import scala.meta.internal.builds.bazelnative.BazelNativeBspClient

import ch.epfl.scala.bsp4j._

/**
 * Mock BSP client that collects all BSP notifications for test assertions.
 * Inspired by MockClient from bsp-testkit2.
 */
class BazelNativeMockClient extends BazelNativeBspClient {

  val taskStartNotifications =
    new ConcurrentLinkedQueue[TaskStartParams]()
  val taskFinishNotifications =
    new ConcurrentLinkedQueue[TaskFinishParams]()
  val taskProgressNotifications =
    new ConcurrentLinkedQueue[TaskProgressParams]()
  val publishDiagnosticsNotifications =
    new ConcurrentLinkedQueue[PublishDiagnosticsParams]()
  val logMessageNotifications =
    new ConcurrentLinkedQueue[LogMessageParams]()

  override def onBuildTaskStart(params: TaskStartParams): Unit =
    taskStartNotifications.add(params)

  override def onBuildTaskFinish(params: TaskFinishParams): Unit =
    taskFinishNotifications.add(params)

  override def onBuildTaskProgress(params: TaskProgressParams): Unit =
    taskProgressNotifications.add(params)

  override def onBuildPublishDiagnostics(
      params: PublishDiagnosticsParams
  ): Unit =
    publishDiagnosticsNotifications.add(params)

  override def onBuildLogMessage(params: LogMessageParams): Unit =
    logMessageNotifications.add(params)

  def clearAll(): Unit = {
    taskStartNotifications.clear()
    taskFinishNotifications.clear()
    taskProgressNotifications.clear()
    publishDiagnosticsNotifications.clear()
    logMessageNotifications.clear()
  }

  def clearDiagnostics(): Unit =
    publishDiagnosticsNotifications.clear()

  def allTaskStarts: List[TaskStartParams] =
    taskStartNotifications.asScala.toList

  def allTaskFinishes: List[TaskFinishParams] =
    taskFinishNotifications.asScala.toList

  def allTaskProgress: List[TaskProgressParams] =
    taskProgressNotifications.asScala.toList

  def allDiagnostics: List[PublishDiagnosticsParams] =
    publishDiagnosticsNotifications.asScala.toList
}
