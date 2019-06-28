package scala.meta.internal.tvp

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import scala.meta.io.AbsolutePath
import scala.meta.internal.mtags.OnDemandSymbolIndex
import java.util.concurrent.ScheduledExecutorService
import scala.collection.concurrent.TrieMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import scala.meta.internal.metals._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Mtags
import org.eclipse.{lsp4j => l}
import java.util.concurrent.atomic.AtomicBoolean
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala._

class MetalsTreeViewProvider(
    workspace: () => AbsolutePath,
    languageClient: MetalsLanguageClient,
    buildTargets: BuildTargets,
    compilations: () => TreeViewCompilations,
    definitionIndex: OnDemandSymbolIndex,
    statistics: StatisticsConfig,
    doCompile: BuildTargetIdentifier => Unit,
    sh: ScheduledExecutorService
) extends TreeViewProvider {
  val ticks = TrieMap.empty[String, ScheduledFuture[_]]
  private val isVisible = TrieMap.empty[String, Boolean].withDefaultValue(false)
  private val isCollapsed = TrieMap.empty[BuildTargetIdentifier, Boolean]
  private val pendingProjectUpdates =
    ConcurrentHashSet.empty[BuildTargetIdentifier]
  private val classpath = new ClasspathSymbols(
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

  override def reset(): Unit = {
    classpath.reset()
  }

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
          echoCommand(ServerCommands.OpenIssue, "issue-opened"),
          echoCommand(ServerCommands.MetalsGithub, "github"),
          echoCommand(ServerCommands.BloopGithub, "github"),
          echoCommand(ServerCommands.ScalametaTwitter, "twitter")
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
              ongoingCompilationNode
            )
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
          children(TreeViewChildrenParams(Build, uri))
        }
        TreeViewNodeRevealResult(Build, uriChain.toArray)
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
    } yield
      TreeViewNode(
        Compile,
        id.getUri,
        s"${info.getDisplayName()} - ${compilation.timer.toStringSeconds} (${compilation.progressPercentage}%)"
      )
  }

  private def ongoingCompilationNode: TreeViewNode = {
    val size = compilations().size
    val counter = if (size > 0) s" ($size)" else ""
    TreeViewNode(
      Compile,
      "metals://ongoing-compilations",
      s"Ongoing compilations$counter",
      collapseState = MetalsTreeItemCollapseState.collapsed
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
