package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.CancellationException
import scala.concurrent.ExecutionContext
import scala.util.Try

import scala.meta.internal.decorations.PublishDecorationsParams
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.internal.metals.clients.language.DelegatingLanguageClient
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsSlowTaskParams
import scala.meta.internal.metals.clients.language.MetalsSlowTaskResult
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.io.AbsolutePath

import io.undertow.server.HttpServerExchange
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.ShowMessageRequestParams

/**
 * Editor client that implement dialogue UIs like window/showMessageRequest.
 *
 * Goals:
 *
 * - enable editor plugin authors access all functionality within Metals
 *   even if the editor plugin only supports a limited set of LSP endpoints.
 *
 * Non-goals:
 *
 * - Pretty UI, the interface only needs to be functional, not look nice.
 * - Become permanent/primary interface for ordinary users. The end goal is to
 *   enable users to interact with Metals from their editor, not via a browser.
 *
 * The most popular LSP clients in editors like Vim currently have
 * limited support so that endpoints like `window/showMessageRequest` are ignored,
 * with no workaround for users to interact with the Metals language server.
 * This http client allows users in those editors to trigger server commands
 * and respond to UI dialogues through their browser instead.
 */
final class MetalsHttpClient(
    folders: List[AbsolutePath],
    url: () => String,
    initial: MetalsLanguageClient,
    triggerReload: () => Unit,
    icons: Icons,
    time: Time,
    sh: ScheduledExecutorService,
    clientConfig: ClientConfiguration,
)(implicit ec: ExecutionContext)
    extends DelegatingLanguageClient(initial) {

  override def metalsExecuteClientCommand(
      params: ExecuteCommandParams
  ): Unit = {
    underlying.metalsExecuteClientCommand(params)
  }

  // =============
  // metals/status
  // =============
  private val status = new AtomicReference[String]("")
  private def statusFormatted: String =
    Icons.translate(icons, Icons.unicode, status.get())
  override def metalsStatus(params: MetalsStatusParams): Unit = {
    if (params.hide) status.set("")
    else status.set(params.text)
    triggerReload()
    underlying.metalsStatus(params)
  }

  // ===============
  // metals/slowTask
  // ===============
  private case class SlowTask(
      id: String,
      value: MetalsSlowTaskParams,
      promise: CompletableFuture[MetalsSlowTaskResult],
      timer: Timer,
  )
  private val slowTasks = new ConcurrentLinkedDeque[SlowTask]()
  private def slowTasksFormatted(html: HtmlBuilder): HtmlBuilder = {
    slowTasks.removeIf(_.promise.isDone)
    html.unorderedList(slowTasks.asScala) { task =>
      html
        .text(task.value.message)
        .text(" ")
        .text(task.timer.toString)
        .submitButton(s"id=${task.id}", "Cancel")
    }
  }
  override def metalsSlowTask(
      params: MetalsSlowTaskParams
  ): CompletableFuture[MetalsSlowTaskResult] = {
    val fromEditorCompletable = underlying.metalsSlowTask(params)
    slowTasks.add(
      SlowTask(nextId(), params, fromEditorCompletable, new Timer(time))
    )
    sh.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit = {
          triggerReload()
          if (fromEditorCompletable.isDone) {
            throw new CancellationException
          }
        }
      },
      0,
      1,
      TimeUnit.SECONDS,
    )
    fromEditorCompletable.asScala.onComplete { _ => triggerReload() }
    fromEditorCompletable
  }

  // ==================
  // window/showMessage
  // ==================
  private case class ShowMessage(id: String, value: MessageParams)
  private val showMessages = new ConcurrentLinkedDeque[ShowMessage]()
  def showMessagesFormatted(html: HtmlBuilder): Unit = {
    html.unorderedList(showMessages.asScala) { params =>
      html
        .append(params.value)
        .submitButton(s"id=${params.id}", "Dismiss")
    }
  }
  override def showMessage(params: MessageParams): Unit = {
    showMessages.add(ShowMessage(nextId(), params))
    triggerReload()
    underlying.showMessage(params)
  }

  // =========================
  // window/showMessageRequest
  // =========================
  private case class MessageRequest(
      id: String,
      value: ShowMessageRequestParams,
      promise: CompletableFuture[MessageActionItem],
  )
  private val showMessageRequests = new ConcurrentLinkedDeque[MessageRequest]()
  private def showMessageRequestsFormatted(html: HtmlBuilder): Unit = {
    showMessageRequests.removeIf(_.promise.isDone)
    html.unorderedList(showMessageRequests.asScala) { params =>
      html.append(params.value)
      params.value.getActions.asScala.zipWithIndex.foreach {
        case (action, index) =>
          html.submitButton(s"id=${params.id}&item=$index", action.getTitle)
      }
      html.submitButton(s"id=${params.id}&dismiss='true'", "Dismiss")
    }
  }
  override def showMessageRequest(
      params: ShowMessageRequestParams
  ): CompletableFuture[MessageActionItem] = {
    val fromEditorCompletable = underlying.showMessageRequest(params)
    showMessageRequests.add(
      MessageRequest(nextId(), params, fromEditorCompletable)
    )
    triggerReload()
    fromEditorCompletable.asScala.onComplete { _ => triggerReload() }
    fromEditorCompletable
  }

  // =================
  // window/logMessage
  // =================
  private val logs = new ConcurrentLinkedDeque[MessageParams]()
  private def logsFormatted(html: HtmlBuilder): Unit = {
    while (logs.size() > 20) logs.pollLast()
    logs.forEach { params => html.append(params) }
  }
  override def logMessage(message: MessageParams): Unit = {
    logs.addFirst(message)
    triggerReload()
    underlying.logMessage(message)
  }

  override def metalsPublishDecorations(
      params: PublishDecorationsParams
  ): Unit = {
    if (clientConfig.isDecorationProvider()) {
      underlying.metalsPublishDecorations(params)
    }
  }

  // =======
  // Helpers
  // =======
  def tracePath(
      protocolName: String,
      workspace: AbsolutePath,
      description: String,
  ): Option[(String, AbsolutePath)] =
    Some((description, Trace.protocolTracePath(protocolName, workspace)))

  def globalTracePath(
      protocolName: String,
      description: String,
  ): Option[(String, AbsolutePath)] =
    Trace.globalDirectory.flatMap(dir =>
      tracePath(protocolName, dir, description)
    )

  private def serverCommands(html: HtmlBuilder): HtmlBuilder = {
    ServerCommands.all.foreach { command =>
      html.element(
        "form",
        s"action='/execute-command?command=${command.id}' method='post'",
      )(
        _.text(command.title)
          .text(": ")
          .element(
            "button",
            "type='submit' class='btn' style='padding:0.4em'",
          )(
            _.text("Execute")
          )
      )
    }
    html
  }

  private val ids = new AtomicInteger()
  private def nextId(): String = ids.getAndIncrement().toString

  def renderHtml: String = {
    val livereload = Urls.livereload(url())
    val result = HtmlBuilder().page(
      "Metals",
      List(livereload, HtmlBuilder.htmlCSS),
      HtmlBuilder.bodyStyle,
    ) { html =>
      html
        .section(
          "metals/status",
          _.element("p")(_.text("Status: ").raw(statusFormatted)),
        )
        .section("metals/slowTask", slowTasksFormatted)
        .section("workspace/executeCommand", serverCommands)
        .section("window/showMessageRequests", showMessageRequestsFormatted)
        .section("window/showMessage", showMessagesFormatted)
        .section(
          "window/logMessage",
          builder =>
            builder
              .text("Paths: ")
              .unorderedList(folders)(folder =>
                builder.path(folder.resolve(Directories.log).toNIO)
              )
              .element("section", "class='container is-dark'")(
                _.element(
                  "pre",
                  "style='overflow:auto;max-height:400px;min-height:100px;color:white;'",
                )(logsFormatted)
              ),
        )
        .section(
          "Log files",
          builder => {
            val traces = List(
              tracePath("LSP", PathIO.workingDirectory, "LSP trace"),
              globalTracePath("LSP", "LSP global trace"),
              tracePath("BSP", PathIO.workingDirectory, "BSP trace"),
              globalTracePath("BSP", "BSP global trace"),
            ).flatten

            traces.foreach { case (description, path) =>
              builder
                .element("p")(
                  _.text(s"$description (enabled=${path.isFile}):")
                    .path(path.toNIO)
                )
            }
          },
        )
    }
    result.render
  }

  def completeCommand(exchange: HttpServerExchange): Unit = {
    val id = exchange.getQuery("id").getOrElse("<unknown>")
    showMessages.removeIf(_.id == id)
    slowTasks.forEach { task =>
      if (task.id == id) {
        task.promise.complete(MetalsSlowTaskResult(cancel = true))
        triggerReload()
      }
    }
    for {
      messageRequest <- showMessageRequests.asScala
      if id == messageRequest.id
    } {
      if (exchange.getQuery("dismiss").isDefined) {
        messageRequest.promise.complete(null)
      } else {
        val result = for {
          indexString <- exchange.getQuery("item")
          index <- Try(indexString.toInt).toOption
          item <- messageRequest.value.getActions.asScala.lift(index)
        } yield item
        result match {
          case Some(item) =>
            messageRequest.promise.complete(item)
          case None =>
            messageRequest.promise.completeExceptionally(
              new NoSuchElementException(exchange.getQueryString)
            )
        }
      }
    }
  }

}
