package scala.meta.internal.tvp

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.concurrent.TrieMap

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.{lsp4j => l}

class MetalsTreeViewProvider(
    workspace: () => AbsolutePath,
    languageClient: MetalsLanguageClient,
    buildTargets: BuildTargets,
    compilations: () => TreeViewCompilations,
    definitionIndex: GlobalSymbolIndex,
    statistics: StatisticsConfig,
    doCompile: BuildTargetIdentifier => Unit,
    sh: ScheduledExecutorService
) extends TreeViewProvider {
  private val ticks =
    TrieMap.empty[String, ScheduledFuture[_]]
  private val isVisible = TrieMap.empty[String, Boolean].withDefaultValue(false)
  private val isCollapsed = TrieMap.empty[BuildTargetIdentifier, Boolean]
  private val pendingProjectUpdates =
    ConcurrentHashSet.empty[BuildTargetIdentifier]
  private val classpath = new ClasspathSymbols(
    isStatisticsEnabled = statistics.isTreeView
  )
  val libraries = new ClasspathTreeView[AbsolutePath, AbsolutePath](
    definitionIndex,
    Project,
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
    Project,
    "projects",
    "Projects",
    _.id,
    _.getUri(),
    uri => new BuildTargetIdentifier(uri),
    _.displayName,
    _.baseDirectory,
    { () =>
      buildTargets.all.filter(target =>
        buildTargets.buildTargetSources(target.id).nonEmpty
      )
    },
    { (id, symbol) =>
      doCompile(id)
      buildTargets.scalacOptions(id) match {
        case None =>
          Nil.iterator
        case Some(info) =>
          classpath.symbols(info.getClassDirectory().toAbsolutePath, symbol)
      }
    }
  )

  override def init(): Unit = {
    languageClient.metalsTreeViewDidChange(
      TreeViewDidChangeParams(
        Array(
          TreeViewNode.empty(Project),
          TreeViewNode.empty(Build),
          TreeViewNode.empty(Compile)
        )
      )
    )
  }

  override def reset(): Unit = {
    classpath.reset()
  }

  private def flushPendingProjectUpdates(): Unit = {
    val toUpdate = pendingProjectUpdates.asScala.iterator
      .filter { id =>
        !isCollapsed.getOrElse(id, true) &&
        isVisible(Project)
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
    buildTargets.scalaTarget(id).foreach { target =>
      classpath.clearCache(target.scalac.getClassDirectory().toAbsolutePath)
    }
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
        isCollapsed(uri.key) = params.collapsed
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
            () => tickBuildTreeView(),
            1,
            1,
            TimeUnit.SECONDS
          )
        case Project =>
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
        case Project =>
          val uri = params.nodeUri
          if (libraries.matches(uri)) {
            libraries.parent(uri).orNull
          } else if (projects.matches(uri)) {
            projects.parent(uri).orNull
          } else {
            null
          }
        case _ =>
          null
      }
    )
  }
  def echoCommand(command: Command, icon: String): TreeViewNode =
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
      icon = icon,
      tooltip = command.description
    )
  override def children(
      params: TreeViewChildrenParams
  ): MetalsTreeViewChildrenResult = {
    val children: Array[TreeViewNode] = params.viewId match {
      case Help =>
        Array(
          echoCommand(ServerCommands.RunDoctor, "bug"),
          echoCommand(ServerCommands.GotoLog, "bug"),
          echoCommand(ServerCommands.ReadVscodeDocumentation, "book"),
          echoCommand(ServerCommands.ReadBloopDocumentation, "book"),
          echoCommand(ServerCommands.ChatOnGitter, "gitter"),
          echoCommand(ServerCommands.ChatOnDiscord, "discord"),
          echoCommand(ServerCommands.OpenIssue, "issue-opened"),
          echoCommand(ServerCommands.MetalsGithub, "github"),
          echoCommand(ServerCommands.BloopGithub, "github"),
          echoCommand(ServerCommands.ScalametaTwitter, "twitter")
        )
      case Project =>
        Option(params.nodeUri) match {
          case None if buildTargets.all.nonEmpty =>
            Array(
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
          case _ => Array.empty
        }
      case Build =>
        Option(params.nodeUri) match {
          case None =>
            Array(
              TreeViewNode.fromCommand(ServerCommands.ImportBuild, "sync"),
              TreeViewNode
                .fromCommand(ServerCommands.NewScalaProject, "empty-window"),
              TreeViewNode
                .fromCommand(ServerCommands.ConnectBuildServer, "connect"),
              TreeViewNode
                .fromCommand(ServerCommands.CascadeCompile, "cascade"),
              TreeViewNode.fromCommand(ServerCommands.CancelCompile, "cancel"),
              TreeViewNode.fromCommand(ServerCommands.CleanCompile, "clean"),
              TreeViewNode
                .fromCommand(ServerCommands.RestartBuildServer, "debug-stop")
            )
          case _ =>
            Array()
        }
      case Compile =>
        Option(params.nodeUri) match {
          case None =>
            ongoingCompilations
          case Some(uri) =>
            if (uri == ongoingCompilationNode.nodeUri) {
              ongoingCompilations
            } else {
              ongoingCompileNode(new BuildTargetIdentifier(uri)).toArray
            }
        }
      case _ => Array.empty
    }
    MetalsTreeViewChildrenResult(children)
  }

  override def reveal(
      path: AbsolutePath,
      pos: l.Position
  ): Option[TreeViewNodeRevealResult] = {
    val input = path.toInput
    val occurrences =
      Mtags.allToplevels(input).occurrences.filterNot(_.symbol.isPackage)
    if (occurrences.isEmpty) None
    else {
      val closestSymbol = occurrences.minBy { occ =>
        val startLine = occ.range.fold(Int.MaxValue)(_.startLine)
        val distance = math.abs(pos.getLine - startLine)
        val isLeading = pos.getLine() > startLine
        (!isLeading, distance)
      }
      val result =
        if (path.isDependencySource(workspace())) {
          buildTargets
            .inferBuildTarget(List(Symbol(closestSymbol.symbol).toplevel))
            .map { inferred =>
              libraries.toUri(inferred.jar, inferred.symbol).parentChain
            }
        } else {
          buildTargets
            .inverseSources(path)
            .map(id => projects.toUri(id, closestSymbol.symbol).parentChain)
        }
      result.map { uriChain =>
        uriChain.foreach { uri =>
          // Cache results
          children(TreeViewChildrenParams(Project, uri))
        }
        TreeViewNodeRevealResult(Project, uriChain.toArray)
      }
    }
  }

  private def ongoingCompilations: Array[TreeViewNode] = {
    compilations().buildTargets.flatMap(ongoingCompileNode).toArray
  }

  private def ongoingCompileNode(
      id: BuildTargetIdentifier
  ): Option[TreeViewNode] = {
    for {
      compilation <- compilations().get(id)
      info <- buildTargets.info(id)
    } yield TreeViewNode(
      Compile,
      id.getUri,
      s"${info.getDisplayName()} - ${compilation.timer.toStringSeconds} (${compilation.progressPercentage}%)",
      icon = "compile"
    )
  }

  private def ongoingCompilationNode: TreeViewNode = {
    TreeViewNode(
      Compile,
      null,
      Compile
    )
  }

  private def toplevelTreeNodes: Array[TreeViewNode] =
    Array(ongoingCompilationNode)

  private val wasEmpty = new AtomicBoolean(true)
  private val isEmpty = new AtomicBoolean(true)
  private def tickBuildTreeView(): Unit = {
    isEmpty.set(compilations().isEmpty)
    if (wasEmpty.get() && isEmpty.get()) {
      () // Nothing changed since the last notification.
    } else {
      languageClient.metalsTreeViewDidChange(
        TreeViewDidChangeParams(toplevelTreeNodes)
      )
    }
    wasEmpty.set(isEmpty.get())
  }
}
