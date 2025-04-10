package scala.meta.internal.metals

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Promise

import scala.meta.internal.implementation.ImplementationProvider
import scala.meta.internal.metals.clients.language.DelegatingLanguageClient
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.watcher.FileWatcher
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

trait IndexProviders {
  def languageClient: DelegatingLanguageClient
  def executionContext: ExecutionContextExecutorService
  def tables: Tables
  def statusBar: StatusBar
  def workDoneProgress: WorkDoneProgress
  def timerProvider: TimerProvider
  def indexingPromise: Promise[Unit]
  def buildData(): Seq[Indexer.BuildTool]
  def clientConfig: ClientConfiguration
  def definitionIndex: OnDemandSymbolIndex
  def referencesProvider: ReferenceProvider
  def workspaceSymbols: WorkspaceSymbolProvider
  def buildTargets: BuildTargets
  def semanticDBIndexer: SemanticdbIndexer
  def fileWatcher: FileWatcher
  def focusedDocument: Option[AbsolutePath]
  def focusedDocumentBuildTarget: AtomicReference[BuildTargetIdentifier]
  def buildTargetClasses: BuildTargetClasses
  def userConfig: UserConfiguration
  def sh: ScheduledExecutorService
  def symbolDocs: Docstrings
  def scalaVersionSelector: ScalaVersionSelector
  def sourceMapper: SourceMapper
  def folder: AbsolutePath
  def implementationProvider: ImplementationProvider
  def resetService(): Unit
  def fileChanges: FileChanges
}
