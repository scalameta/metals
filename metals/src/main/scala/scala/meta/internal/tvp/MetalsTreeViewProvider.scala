package scala.meta.internal.tvp

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import scala.meta.io.AbsolutePath
import scala.meta.internal.mtags.OnDemandSymbolIndex
import java.util.concurrent.ScheduledExecutorService
import scala.collection.concurrent.TrieMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.ExecuteCommandParams
import scala.meta.internal.metals._
import scala.meta.internal.metals.MetalsEnrichments._

class MetalsTreeViewProvider(
    workspace: () => AbsolutePath,
    languageClient: MetalsLanguageClient,
    buildTargets: BuildTargets,
    buildClient: () => ForwardingMetalsBuildClient,
    definitionIndex: OnDemandSymbolIndex,
    sh: ScheduledExecutorService,
    statistics: StatisticsConfig,
    doCompile: BuildTargetIdentifier => Unit
) extends TreeViewProvider {
  val Build = "build"
  val Compile = "compile"
  val Help = "help"
  val ticks = TrieMap.empty[String, ScheduledFuture[_]]
  private val isVisible = TrieMap.empty[String, Boolean].withDefaultValue(false)
  private val isCollapsed = TrieMap.empty[BuildTargetIdentifier, Boolean]
  private val pendingProjectUpdates =
    ConcurrentHashSet.empty[BuildTargetIdentifier]
  val classpath = new ClasspathSymbols(
    isStatisticsEnabled = statistics.isTreeView
  )
  val libraries = new ClasspathTreeView[AbsolutePath, AbsolutePath](
    definitionIndex,
    Build,
    "libraries",
    "Libraries",
    identity,
    _.toURI.toString(),
    _.toAbsolutePath,
    _.filename,
    _.toString,
    () => buildTargets.allWorkspaceJars,
    (path, symbol) => classpath.symbols(path, symbol)
  )
  val projects = new ClasspathTreeView[ScalaTarget, BuildTargetIdentifier](
    definitionIndex,
    Build,
    "projects",
    "Projects",
    _.info.getId(),
    _.getUri(),
    uri => new BuildTargetIdentifier(uri),
    _.info.getDisplayName(),
    _.info.getBaseDirectory, { () =>
      buildTargets.all.filter(
        target => buildTargets.buildTargetSources(target.info.getId()).nonEmpty
      )
    }, { (id, symbol) =>
      doCompile(id)
      buildTargets.scalacOptions(id) match {
        case None =>
          Nil.iterator
        case Some(info) =>
          classpath.symbols(info.getClassDirectory().toAbsolutePath, symbol)
      }
    }
  )

  private def flushPendingProjectUpdates(): Unit = {
    val toUpdate = pendingProjectUpdates.asScala.iterator
      .filter { id =>
        !isCollapsed.getOrElse(id, true) &&
        isVisible(Build)
      }
      .flatMap(buildTargets.scalaTarget)
      .toArray
    if (toUpdate.nonEmpty) {
      val nodes = toUpdate.map { target =>
        projects
          .toViewNode(target)
          .copy(collapseState = MetalsTreeItemCollapseState.expanded)
      }
      languageClient.metalsTreeViewDidChange(
        TreeViewDidChangeParams(nodes)
      )
    }
  }

  override def onBuildTargetDidCompile(id: BuildTargetIdentifier): Unit = {
    if (isCollapsed.contains(id)) {
      pendingProjectUpdates.add(id)
      flushPendingProjectUpdates()
    } else {
      () // do nothing if the user never expanded the tree view node.
    }
  }

  override def onCollapseDidChange(
      params: TreeViewNodeCollapseDidChangeParams
  ): Unit = {
    if (projects.matches(params.nodeUri)) {
      val uri = projects.fromUri(params.nodeUri)
      if (uri.isRoot) {
        isCollapsed(uri.value) = params.collapsed
      }
    }
  }

  override def onVisibilityDidChange(
      params: TreeViewVisibilityDidChangeParams
  ): Unit = {
    isVisible(params.viewId) = params.visible
    if (params.visible) {
      params.viewId match {
        case Compile =>
          ticks(params.viewId) = sh.scheduleAtFixedRate(
            () => buildClient().tickBuildTreeView(),
            1,
            1,
            TimeUnit.SECONDS
          )
        case Build =>
          flushPendingProjectUpdates()
        case _ =>
      }
    } else {
      ticks.remove(params.viewId).foreach(_.cancel(false))
    }
  }

  override def parent(
      params: TreeViewParentParams
  ): TreeViewParentResult = {
    TreeViewParentResult(
      params.viewId match {
        case Build =>
          val uri = params.nodeUri
          if (libraries.matches(uri)) {
            libraries.parent(uri).orNull
          } else if (projects.matches(uri)) {
            libraries.parent(uri).orNull
          } else {
            null
          }
        case _ =>
          null
      }
    )
  }
  def echoCommand(command: Command): TreeViewNode =
    TreeViewNode(
      viewId = "help",
      nodeUri = s"help:${command.id}",
      label = command.title,
      command = MetalsCommand(
        command.title,
        ClientCommands.EchoCommand.id,
        command.description,
        Array(command.id: AnyRef)
      ),
      icon = "command",
      tooltip = command.description
    )
  override def children(
      params: TreeViewChildrenParams
  ): MetalsTreeViewChildrenResult = {
    val children: Array[TreeViewNode] = params.viewId match {
      case Help =>
        Array(
          echoCommand(ServerCommands.RunDoctor),
          echoCommand(ServerCommands.GotoLog),
          echoCommand(ServerCommands.ReadVscodeDocumentation),
          echoCommand(ServerCommands.ReadBloopDocumentation),
          echoCommand(ServerCommands.ChatOnGitter),
          echoCommand(ServerCommands.OpenIssue),
          echoCommand(ServerCommands.StarMetals),
          echoCommand(ServerCommands.StarBloop),
          echoCommand(ServerCommands.FollowTwitter)
        )
      case Build =>
        Option(params.nodeUri) match {
          case None =>
            Array(
              TreeViewNode.fromCommand(ServerCommands.ImportBuild),
              TreeViewNode.fromCommand(ServerCommands.ConnectBuildServer),
              projects.root,
              libraries.root
            )
          case Some(uri) =>
            if (libraries.matches(uri)) {
              libraries.children(uri)
            } else if (projects.matches(uri)) {
              projects.children(uri)
            } else {
              Array.empty
            }
        }
      case Compile =>
        Option(params.nodeUri) match {
          case None =>
            Array(
              TreeViewNode.fromCommand(ServerCommands.CascadeCompile),
              TreeViewNode.fromCommand(ServerCommands.CancelCompile),
              buildClient().ongoingCompilationNode
            )
          case Some(uri) =>
            if (uri == buildClient().ongoingCompilationNode.nodeUri) {
              buildClient().ongoingCompilations
            } else {
              buildClient()
                .ongoingCompileNode(new BuildTargetIdentifier(uri))
                .toArray
            }
        }
      case _ => Array.empty
    }
    MetalsTreeViewChildrenResult(children)
  }

  def revealNode(viewId: String, uri: String): Unit = {
    languageClient.metalsExecuteClientCommand(
      new ExecuteCommandParams(
        ClientCommands.TreeViewRevealNode.id,
        List(TreeViewRevealNodeParams(viewId, uri): Object).asJava
      )
    )
  }
}
