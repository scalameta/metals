package scala.meta.internal.metals

import java.nio.file.Path

import scala.collection.mutable

import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.VersionRecommendation
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.clients.language.MetalsInputBoxParams
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams

/**
 * Constants for requests/dialogues via LSP window/showMessage and window/showMessageRequest.
 */
object Messages {

  def errorMessageParams(msg: String) = new MessageParams(
    MessageType.Error,
    msg,
  )

  val noRoot = new MessageParams(
    MessageType.Error,
    """|No rootUri or rootPath detected.
       |Metals will not function correctly without either of these set since a workspace is needed.
       |Try opening your project at the workspace root.""".stripMargin,
  )

  val showTastyFailed = new MessageParams(
    MessageType.Error,
    """|Cannot execute show TASTy command because there is no .tasty file for given file.
       |For now, this command only works with Scala 3.
       |""".stripMargin,
  )

  object Worksheets {

    val unableToExport = new MessageParams(
      MessageType.Warning,
      "Unable to export worksheet. Please fix any diagnostics, save, and try again.",
    )
  }

  val NoBspSupport = new MessageParams(
    MessageType.Warning,
    "Workspace doesn't support BSP, please see logs.",
  )

  object BspProvider {
    val noBuildToolFound = new MessageParams(
      MessageType.Warning,
      "No build tool found to generate a BSP config.",
    )
    val genericUnableToCreateConfig = new MessageParams(
      MessageType.Error,
      "Unable to create bsp config. Please check your log for more details.",
    )

    def unableToCreateConfigFromMessage(message: String) = new MessageParams(
      MessageType.Error,
      message,
    )

    def notificationParams(buildTool: BuildTool): MessageParams = {
      new MessageParams(
        MessageType.Info,
        s"Multiple build tools found that could be build servers. Using ${buildTool.executableName}",
      )
    }

    def params(buildTools: List[BuildTool]): ShowMessageRequestParams = {
      val messageActionItems =
        buildTools.map(bt => new MessageActionItem(bt.executableName))
      val params = new ShowMessageRequestParams()
      params.setMessage(
        "Multiple build tools found that could be build servers. Which would you like to use?"
      )
      params.setType(MessageType.Info)
      params.setActions(messageActionItems.asJava)
      params
    }
  }

  def unableToStartServer(buildTool: String) = new MessageParams(
    MessageType.Warning,
    s"Metals is unable to start ${buildTool}. Please try to connect after starting it manually.",
  )

  def unknownScalafixRules(unknownRuleMessage: String): MessageParams = {
    // To match: "Rule not found 'ARuleName'"
    val regex = ".*'(.*)'.*".r
    val rule = unknownRuleMessage match {
      case regex(rule) => rule
      case _ => "a rule"
    }
    new MessageParams(
      MessageType.Warning,
      s"Metals is unable to run ${rule}. Please add the rule dependency to `metals.scalafixRulesDependencies`.",
    )
  }

  val ImportProjectFailed = new MessageParams(
    MessageType.Error,
    "Import project failed, no functionality will work. See the logs for more details",
  )

  object ImportProjectFailedSuggestBspSwitch {
    val switchBsp = new MessageActionItem("Switch build server")
    val cancel = new MessageActionItem("Cancel")

    def params(): ShowMessageRequestParams = {
      val request = new ShowMessageRequestParams()
      request.setMessage(
        "Import project failed, no functionality will work. See the logs for more details. You can try using a different build server."
      )
      request.setType(MessageType.Error)
      request.setActions(List(switchBsp).asJava)
      request
    }
  }

  val ImportAlreadyRunning = new MessageParams(
    MessageType.Warning,
    s"Import already running. \nPlease cancel the current import to run a new one.",
  )

  object ImportProjectPartiallyFailed {
    val showLogs = new MessageActionItem("Show logs")

    def params(): ShowMessageRequestParams = {
      val request = new ShowMessageRequestParams()
      request.setMessage(
        "Import project partially failed, limited functionality may work in some parts of the workspace. " +
          "See the logs for more details. "
      )
      request.setType(MessageType.Warning)
      request.setActions(List(showLogs).asJava)
      request
    }
  }

  val InsertInferredTypeFailed = new MessageParams(
    MessageType.Error,
    "Could not insert inferred type, please check the logs for more details or report an issue.",
  )

  val ExtractMemberDefinitionFailed = new MessageParams(
    MessageType.Error,
    "Could not extract the given definition, please check the logs for more details or report an issue.",
  )

  val ReloadProjectFailed = new MessageParams(
    MessageType.Error,
    "Reloading your project failed, no functionality will work. See the log for more details",
  )

  val ResetWorkspaceFailed = new MessageParams(
    MessageType.Error,
    "Failed to reset the workspace. See the log for more details.",
  )

  def dontShowAgain: MessageActionItem =
    new MessageActionItem("Don't show again")

  def notNow: MessageActionItem =
    new MessageActionItem("Not now")

  object ImportBuildChanges {
    def yes: MessageActionItem =
      new MessageActionItem("Import changes")

    def notNow: MessageActionItem = Messages.notNow

    def params(buildToolName: String): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(s"$buildToolName build needs to be re-imported")
      params.setType(MessageType.Info)
      params.setActions(
        List(
          yes,
          notNow,
          dontShowAgain,
        ).asJava
      )
      params
    }

    def notificationParams(buildToolName: String): MessageParams = {
      new MessageParams(
        MessageType.Info,
        s"$buildToolName build needs to be re-imported.",
      )
    }
  }

  object ImportBuild {
    def yes = new MessageActionItem("Import build")

    def notNow: MessageActionItem = Messages.notNow

    def params(buildToolName: String): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"New $buildToolName workspace detected, would you like to import the build?"
      )
      params.setType(MessageType.Info)
      params.setActions(
        List(
          yes,
          notNow,
          dontShowAgain,
        ).asJava
      )
      params
    }

    def notificationParams(buildToolName: String): MessageParams = {
      new MessageParams(
        MessageType.Info,
        s"New $buildToolName workspace detected, please import the build using the 'Import build' command.",
      )
    }
  }

  object StartHttpServer {
    def yes = new MessageActionItem("Start")

    def notNow: MessageActionItem = Messages.notNow

    def params(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"Http server is required for such features as Metals Doctor, do you want to start it now?" +
          "To avoid this pop up start metals with -Dmetals.http=on property or isHttpEnabled initialization option"
      )
      params.setType(MessageType.Info)
      params.setActions(
        List(
          yes,
          notNow,
          dontShowAgain,
        ).asJava
      )
      params
    }
  }

  object GenerateBspAndConnect {
    def yes = new MessageActionItem("Connect")

    def notNow: MessageActionItem = Messages.notNow

    def params(
        buildToolName: String,
        buildServerName: String,
    ): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"New $buildToolName workspace detected, would you like connect to the $buildServerName build server?"
      )
      params.setType(MessageType.Info)
      params.setActions(
        List(
          yes,
          notNow,
          dontShowAgain,
        ).asJava
      )
      params
    }
  }

  object MainClass {
    val message = "Multiple main classes found. Which would you like to run?"
  }

  object ChooseBuildTool {
    def message =
      "Multiple build definitions found. Which would you like to use?"

    def notificationParams(buildTool: BuildTool): MessageParams = {
      new MessageParams(
        MessageType.Info,
        s"Multiple build definitions found. Using ${buildTool.executableName}. Use `metals.targetBuildTool` to change the build tool.",
      )
    }

    def params(builtTools: List[BuildTool]): ShowMessageRequestParams = {
      val messageActionItems =
        builtTools.map(bt => new MessageActionItem(bt.executableName))
      val params = new ShowMessageRequestParams()
      params.setMessage(message)
      params.setType(MessageType.Info)
      params.setActions(messageActionItems.asJava)
      params
    }
  }

  object NewBuildToolDetected {
    val switch = new MessageActionItem("yes")
    val dontSwitch = new MessageActionItem("no")
    def notificationParams(
        newBuildTool: String,
        currentBuildTool: String,
    ): MessageParams = {
      new MessageParams(
        MessageType.Info,
        s"""|New build tool is now available in the workspace: ${newBuildTool}?
            |Use switch build server command to switch to the new build tool.
            |""".stripMargin,
      )
    }
    def params(
        newBuildTool: String,
        currentBuildTool: String,
    ): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"""|Would you like to switch from the current build tool (${currentBuildTool}) to newly detected ${newBuildTool}?
            |This action will also reset build server choice.
            |""".stripMargin
      )
      params.setType(MessageType.Info)
      params.setActions(List(switch, dontSwitch).asJava)
      params
    }
  }

  def partialNavigation(icons: Icons) =
    new MetalsStatusParams(
      s"${icons.info} Partial navigation",
      tooltip = "This external library source has compile errors. " +
        "To fix this problem, update your build settings to use the same compiler plugins and compiler settings as " +
        "the external library.",
    )

  object CheckDoctor {
    def problemsFixed: MessageParams =
      new MessageParams(
        MessageType.Info,
        "Build is correctly configured now, Metals should work correctly for all build targets.",
      )

    def moreInfo: String =
      " Select 'More information' to learn how to fix this problem."

    def allProjectsMisconfigured: String =
      "Metals might not work correctly for this build due to mis-configuration." + moreInfo

    def singleMisconfiguredProject(name: String): String =
      s"Metals might not work correctly in project '$name' due to mis-configuration." + moreInfo

    def multipleMisconfiguredProjects(count: Int): String =
      s"Metals might not work correctly for $count build targets in this workspace due to mis-configuration. " + moreInfo

    def multipleProblemsDetected: String =
      s"Multiple problems detected in your build."

    val misconfiguredTestFrameworks: String =
      "Test Explorer won't work due to mis-configuration." + moreInfo

    def isDoctor(params: ShowMessageRequestParams): Boolean =
      params.getActions.asScala.contains(moreInformation)

    def moreInformation: MessageActionItem =
      new MessageActionItem("More information")

    def dismissForever: MessageActionItem =
      new MessageActionItem("Don't show again")

    def params(problem: String): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(problem)
      params.setType(MessageType.Warning)
      params.setActions(
        List(
          moreInformation,
          dismissForever,
        ).asJava
      )
      params
    }
  }

  object IncompatibleBuildToolVersion {

    def dismissForever: MessageActionItem =
      new MessageActionItem("Don't show again")

    def learnMore: MessageActionItem =
      new MessageActionItem("Learn more")

    def learnMoreUrl: String = Urls.docs("import-build")

    def params(tool: VersionRecommendation): ShowMessageRequestParams = {
      def toFixMessage =
        s"To fix this problem, upgrade to $tool ${tool.recommendedVersion} "

      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"Automatic build import is not supported for $tool ${tool.version}. $toFixMessage"
      )
      params.setType(MessageType.Warning)
      params.setActions(
        List(
          learnMore,
          dismissForever,
        ).asJava
      )
      params
    }
  }

  object DisconnectedServer {
    def reconnect: MessageActionItem =
      new MessageActionItem("Reconnect to build server")

    def notNow: MessageActionItem =
      new MessageActionItem("Not now")

    def params(): ShowMessageRequestParams = {

      val params = new ShowMessageRequestParams()
      params.setMessage(
        "Metals lost connection with the build server, most functionality will not work. " +
          "To fix this problem, select \"reconnect to build server\"."
      )
      params.setType(MessageType.Warning)
      params.setActions(
        List(
          reconnect,
          notNow,
        ).asJava
      )
      params
    }
  }

  object OldBloopVersionRunning {

    def yes: MessageActionItem =
      new MessageActionItem("Yes")

    def notNow: MessageActionItem =
      new MessageActionItem("Not now")

    def msg: String =
      s"Deprecated Bloop server is still running and is taking up resources, " +
        s"do you want to kill the process?"

    def params(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(msg)
      params.setType(MessageType.Warning)
      params.setActions(
        List(
          yes,
          notNow,
        ).asJava
      )
      params
    }

    def killingBloopParams(): MessageParams = {
      val params = new MessageParams()
      params.setMessage("No response, killing old Bloop server.")
      params.setType(MessageType.Warning)
      params
    }
  }

  object BloopVersionChange {
    def reconnect: MessageActionItem =
      new MessageActionItem("Restart Bloop")

    def notNow: MessageActionItem =
      new MessageActionItem("Not now")

    def msg: String =
      s"Bloop version was updated, do you want to restart the running Bloop server?"

    def params(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(msg)
      params.setType(MessageType.Warning)
      params.setActions(
        List(
          reconnect,
          notNow,
        ).asJava
      )
      params
    }
  }

  object BloopJvmPropertiesChange {
    def reconnect: MessageActionItem =
      new MessageActionItem("Apply and restart Bloop")

    def notNow: MessageActionItem =
      new MessageActionItem("Not now")
    def notificationParams(): MessageParams = {
      new MessageParams(
        MessageType.Info,
        "Bloop will need to be restarted in order for these changes to take effect.",
      )
    }
    def params(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        "Bloop will need to be restarted in order for these changes to take effect."
      )
      params.setType(MessageType.Warning)
      params.setActions(
        List(
          reconnect,
          notNow,
        ).asJava
      )
      params
    }
  }

  def errorFromThrowable(
      throwable: Throwable
  ): MessageParams =
    new MessageParams(
      MessageType.Error,
      throwable.getMessage(),
    )

  object IncompatibleBloopVersion {
    def manually: MessageActionItem =
      new MessageActionItem("I'll update manually")

    def shutdown: MessageActionItem =
      new MessageActionItem("Turn off old server")

    def dismissForever: MessageActionItem =
      new MessageActionItem("Don't show again")

    def params(
        bloopVersion: String,
        minimumBloopVersion: String,
        isChangedInSettings: Boolean,
    ): ShowMessageRequestParams = {

      val params = new ShowMessageRequestParams()
      val additional =
        if (isChangedInSettings)
          "You will also need to remove or update the `bloopVersion` setting"
        else ""
      params.setMessage(
        s"""|You have Bloop $bloopVersion installed and Metals requires at least Bloop $minimumBloopVersion.
            |If you installed bloop via a system package manager (brew, aur, scoop), please upgrade manually.
            |If not, select "Turn off old server". A newer server will be started automatically afterwards.
            |""".stripMargin + s"\n$additional"
      )
      params.setType(MessageType.Warning)
      params.setActions(
        List(
          shutdown,
          manually,
          dismissForever,
        ).asJava
      )
      params
    }
  }

  object BspSwitch {
    case class Request(
        params: ShowMessageRequestParams,
        mapping: Map[String, String],
    )

    val message: String =
      "Multiple build servers detected, which one do you want to use?"

    def notificationParams(tool: String): MessageParams = {
      new MessageParams(
        MessageType.Info,
        s"Multiple build servers detected, switching to: $tool",
      )
    }

    def chooseServerRequest(
        possibleBuildServers: List[String],
        currentBsp: Option[String],
    ): Request = {
      val mapping = mutable.Map.empty[String, String]
      val messageActionItems =
        possibleBuildServers.map { buildServer =>
          val title = if (currentBsp.exists(_ == buildServer)) {
            buildServer + " (currently using)"
          } else {
            buildServer
          }
          mapping(title) = buildServer
          new MessageActionItem(title)
        }
      val params = new ShowMessageRequestParams()
      params.setMessage(message)
      params.setType(MessageType.Info)
      params.setActions(messageActionItems.asJava)
      Request(params, mapping.toMap)
    }

    def noInstalledServer: MessageParams =
      new MessageParams(
        MessageType.Error,
        "Unable to switch build server since there are no installed build servers on this computer. " +
          "To fix this problem, install a build server first.",
      )

    def onlyOneServer(name: String): MessageParams =
      new MessageParams(
        MessageType.Warning,
        s"Unable to switch build server since there is only one supported build server '$name' detected for this workspace.",
      )

    def isSelectBspServer(params: ShowMessageRequestParams): Boolean =
      params.getMessage == message
  }

  object MissingScalafmtVersion {
    def failedToResolve(message: String): MessageParams = {
      new MessageParams(MessageType.Error, message)
    }

    def fixedVersion(isAgain: Boolean): MessageParams =
      new MessageParams(
        MessageType.Info,
        s"Updated .scalafmt.conf${MissingScalafmtConf.tryAgain(isAgain)}.",
      )

    def isMissingScalafmtVersion(params: ShowMessageRequestParams): Boolean =
      params.getMessage == messageRequestMessage

    def inputBox(): MetalsInputBoxParams =
      MetalsInputBoxParams(
        prompt =
          "No Scalafmt version is configured for this workspace, what version would you like to use?",
        value = BuildInfo.scalafmtVersion,
      )

    def messageRequestMessage: String =
      s"No Scalafmt version is configured for this workspace. " +
        s"To fix this problem, update .scalafmt.conf to include 'version=${BuildInfo.scalafmtVersion}'."

    def changeVersion: MessageActionItem =
      new MessageActionItem(
        s"Update .scalafmt.conf to use v${BuildInfo.scalafmtVersion}"
      )

    def notificationParams(): MessageParams = {
      new MessageParams(
        MessageType.Error,
        s"No Scalafmt version is configured for this workspace. " +
          s"To fix this problem, update .scalafmt.conf to include 'version=${BuildInfo.scalafmtVersion}'.",
      )
    }

    def messageRequest(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(messageRequestMessage)
      params.setType(MessageType.Error)
      params.setActions(
        List(
          changeVersion,
          notNow,
          dontShowAgain,
        ).asJava
      )
      params
    }
  }

  def DebugErrorsPresent(icons: Icons): MetalsStatusParams =
    new MetalsStatusParams(
      s"${icons.error} Errors in workspace",
      tooltip =
        "Cannot run or debug due to existing errors in the workspace. " +
          "Please fix the errors and retry.",
      command = ClientCommands.FocusDiagnostics.id,
      show = true,
    )

  object MissingScalafmtConf {
    def tryAgain(isAgain: Boolean): String =
      if (isAgain) ", try formatting again"
      else ""

    def createFile = new MessageActionItem("Create .scalafmt.conf")
    def runDefaults = new MessageActionItem("Run anyway")

    def fixedParams(where: RelativePath): MessageParams =
      new MessageParams(
        MessageType.Info,
        s"Created $where.",
      )

    def isCreateScalafmtConf(params: ShowMessageRequestParams): Boolean =
      params.getMessage == createScalafmtConfMessage

    def createScalafmtConfMessage: String =
      s"No .scalafmt.conf file detected. " +
        s"How would you like to proceed:"
    def notificationParams(): MessageParams = {
      new MessageParams(
        MessageType.Error,
        "No .scalafmt.conf file detected. It should be present in the workspace root.",
      )
    }
    def params(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(createScalafmtConfMessage)
      params.setType(MessageType.Error)
      params.setActions(
        List(
          createFile,
          runDefaults,
          notNow,
        ).asJava
      )
      params
    }
  }

  object UpdateScalafmtConf {

    def letUpdate = new MessageActionItem("Let Metals update .scalafmt.conf")

    def createMessage(dialect: ScalafmtDialect): String = {
      s"Some source directories can't be formatted by scalafmt " +
        s"because they require the `runner.dialect = ${dialect.value}` setting." +
        "[See scalafmt docs](https://scalameta.org/scalafmt/docs/configuration.html#scala-3)" +
        " and logs for more details"
    }

    def notificationParams(dialect: ScalafmtDialect): MessageParams = {
      new MessageParams(
        MessageType.Warning,
        createMessage(dialect),
      )
    }

    def params(
        dialect: ScalafmtDialect,
        canUpdate: Boolean,
    ): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(createMessage(dialect))
      params.setType(MessageType.Warning)
      params.setActions(
        List(
          if (canUpdate) Some(letUpdate) else None,
          if (canUpdate) Some(notNow) else None,
          Some(dontShowAgain),
        ).flatten.asJava
      )
      params
    }
  }

  object WorkspaceSymbolDependencies {
    def title: String =
      "Add ';' to search library dependencies"

    def detail: String =
      """|The workspace/symbol feature ("Go to symbol in workspace") allows you to search for
         |classes, traits and objects that are defined in your workspace as well as library dependencies.
         |
         |By default, a query searches only for symbols defined in this workspace. Include a semicolon
         |character `;` in the query to search for symbols in library dependencies.
         |
         |Examples:
         |- "Future": workspace only
         |- "Future;": workspace + library dependencies
         |- ";Future": workspace + library dependencies
         |
         |The library dependencies are automatically searched when no results are found in the workspace.
         |""".stripMargin
  }

  object NewScalaFile {
    def selectTheKindOfFileMessage = "Select the kind of file to create"

    def enterNameMessage(kind: String): String =
      s"Enter the name for the new $kind"

  }

  object DisplayBuildTarget {
    def selectTheBuildTargetMessage = "Select the build target to display"
  }

  private def usingString(usingNow: Iterable[String]): String = {
    if (usingNow.size == 1)
      s"Scala version ${usingNow.head}"
    else
      usingNow.toSeq
        .sortWith(SemVer.isCompatibleVersion)
        .mkString("Scala versions ", ", ", "")
  }

  private def recommendationString(usingNow: Iterable[String]): String = {
    val shouldBeUsing = usingNow.map(ScalaVersions.recommendedVersion).toSet

    if (shouldBeUsing.size == 1) s"Scala version ${shouldBeUsing.head}"
    else
      shouldBeUsing.toSeq
        .sortWith(SemVer.isCompatibleVersion)
        .mkString("Scala versions ", ", ", "")
  }

  object DeprecatedScalaVersion {
    def message(
        usingNow: Set[String]
    ): String = {
      val using = "legacy " + usingString(usingNow)
      val recommended = recommendationString(usingNow)
      s"You are using $using, which might no longer be supported by Metals in the future. " +
        s"To get the best support possible it's recommended to update to at least $recommended."
    }
  }

  object DeprecatedRemovedScalaVersion {
    def message(
        usingNow: Set[String]
    ): String = {
      val using = "legacy " + usingString(usingNow)
      val isAre = if (usingNow.size == 1) "is" else "are"
      val recommended = recommendationString(usingNow)
      s"You are using $using, which $isAre no longer supported by Metals. " +
        s"To get the best support possible it's recommended to update to at least $recommended."
    }
  }

  object UnsupportedScalaVersion {
    def message(
        usingNow: Set[String]
    ): String =
      message(usingNow, None)

    def fallbackScalaVersionParams(
        scalaVersion: String
    ): MessageParams = {
      new MessageParams(
        MessageType.Warning,
        message(Set(scalaVersion), Some("fallback")),
      )
    }

    def message(
        usingNow: Set[String],
        description: Option[String],
    ): String = {
      val using = usingString(usingNow)
      val recommended = recommendationString(usingNow)
      val uses211 = usingNow.exists(
        ScalaVersions.scalaBinaryVersionFromFullVersion(_) == "2.11"
      )
      val deprecatedAleternative =
        if (uses211) s" or alternatively to legacy Scala ${BuildInfo.scala211}"
        else ""
      val isAre = if (usingNow.size == 1) "is" else "are"
      val descriptionString = description.map(s => s"$s ").getOrElse("")
      s"You are using $descriptionString$using, which $isAre not supported in this version of Metals. " +
        s"Please upgrade to $recommended$deprecatedAleternative."
    }
  }

  object FutureScalaVersion {
    def message(
        usingNow: Set[String]
    ): String = {
      val using = usingString(usingNow)
      val recommended = recommendationString(usingNow)
      val isAre = if (usingNow.size == 1) "is" else "are"
      s"You are using $using, which $isAre not yet supported in this version of Metals. " +
        s"Please downgrade to $recommended for the moment until the new Metals release."
    }
  }

  object DeprecatedSbtVersion {
    def message: String = {
      s"You are using an old sbt version, support for which might stop being bugfixed in the future versions of Metals. " +
        s"Please upgrade to at least sbt ${BuildInfo.minimumSupportedSbtVersion}."
    }
  }

  object DeprecatedRemovedSbtVersion {
    def message: String = {
      s"You are using an old sbt version, support for which is no longer being bugfixed. " +
        s"Please upgrade to at least sbt ${BuildInfo.minimumSupportedSbtVersion}."
    }
  }

  object UnsupportedSbtVersion {
    def message: String = {
      s"You are using an old sbt version, navigation for which is not supported in this version of Metals. " +
        s"Please upgrade to at least sbt ${BuildInfo.minimumSupportedSbtVersion}."
    }
  }

  object FutureSbtVersion {
    def message: String = {
      s"You are using an sbt version not yet supported in this version of Metals." +
        s"Please downgrade to sbt ${BuildInfo.sbtVersion}"
    }
  }

  object UnresolvedDebugSessionParams {
    def runningClassMultipleBuildTargetsMessage(
        className: String,
        chosenTarget: String,
        anotherTargets: Seq[String],
        mainOrTest: String,
    ): String = {
      val anotherTargetsStr = anotherTargets.map(t => s"'$t'").mkString(", ")
      s"Running '${className}' $mainOrTest class from '${chosenTarget}' build target,\n" +
        s"but class(es) with the same name also found in $anotherTargetsStr.\n" +
        "Build target can be specified with 'buildTarget' debug configuration"
    }
  }

  object ImportScalaScript {
    val message: String = "Scala script detected. Import it as…"
    val doImportScalaCli: MessageActionItem = new MessageActionItem("Scala CLI")
    val dismiss: MessageActionItem = new MessageActionItem("Dismiss")
    def params(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams(
        List(
          doImportScalaCli,
          dismiss,
        ).asJava
      )
      params.setMessage(message)
      params.setType(MessageType.Info)
      params
    }
    def ImportFailed(source: String) =
      new MessageParams(
        MessageType.Error,
        s"Error importing Scala script $source. See the logs for more details.",
      )
    def ImportedScalaCli =
      new MessageParams(
        MessageType.Info,
        "Scala CLI project imported.",
      )
  }

  object ImportAllScripts {
    val message: String = "Should Metals automatically import scripts?"
    val importAll: MessageActionItem = new MessageActionItem(
      "Automatically import"
    )
    val dismiss: MessageActionItem = new MessageActionItem("Keep asking")
    def params(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams(
        List(
          importAll,
          dismiss,
        ).asJava
      )
      params.setMessage(message)
      params.setType(MessageType.Info)
      params
    }
  }

  object ResetWorkspace {
    val message: String =
      "Are you sure you want to clear all caches and compiled artifacts and reset workspace?"
    val resetWorkspace: MessageActionItem = new MessageActionItem(
      "Reset workspace"
    )
    val cancel: MessageActionItem = new MessageActionItem("Cancel")
    def params(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams(
        List(
          resetWorkspace,
          cancel,
        ).asJava
      )
      params.setMessage(message)
      params.setType(MessageType.Warning)
      params
    }
  }

  object NewScalaProject {
    def selectTheTemplate: String = "Select the template to use"

    def enterName: String =
      "Enter a name or a relative path for the new project"

    def enterG8Template: String =
      "Enter the giter template, for example `scala/hello-world.g8`," +
        " which corresponds to a github path `github.com/scala/hello-world.g8`"

    def creationFailed(what: String, where: String) =
      new MessageParams(
        MessageType.Error,
        s"Could not create $what in $where",
      )

    def templateDownloadFailed(why: String) =
      new MessageParams(
        MessageType.Error,
        s"Failed to download templates from the web.\n" + why,
      )

    def yes = new MessageActionItem("Yes")

    def no = new MessageActionItem("No")

    def newWindowMessage =
      "Do you want to open the new project in a new window?"

    def newProjectCreated(path: AbsolutePath) =
      new MessageParams(
        MessageType.Info,
        s"New project has been in created in $path",
      )

    def askForNewWindowParams(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(newWindowMessage)
      params.setType(MessageType.Info)
      params.setActions(
        List(
          yes,
          no,
        ).asJava
      )
      params
    }

  }

  object NoBuildTool {

    def newProject: String =
      "No build tool detected in the current folder." +
        " Do you want to create a new project?"

    def inCurrent = new MessageActionItem("In the current directory")

    def newWindow = new MessageActionItem("In a new directory")

    def dismiss = new MessageActionItem("Not now")

    def noBuildToolAskForTemplate(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(newProject)
      params.setType(MessageType.Info)
      params.setActions(
        List(
          inCurrent,
          newWindow,
          dismiss,
        ).asJava
      )
      params
    }
  }

  object SbtServerJavaHomeUpdate {
    val restart: MessageActionItem =
      new MessageActionItem("Restart sbt server")

    val notNow: MessageActionItem =
      new MessageActionItem("Not now")

    def params(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"Java home has been updated, do you want to restart the sbt BSP server? (the change will only be picked up after the restart)"
      )
      params.setType(MessageType.Info)
      params.setActions(
        List(
          restart,
          notNow,
        ).asJava
      )
      params
    }
  }

  object ProjectJavaHomeUpdate {
    val restart: MessageActionItem =
      new MessageActionItem("Restart/Reconnect to the build server")

    val notNow: MessageActionItem =
      new MessageActionItem("Not now")
    def notificationParams(): MessageParams = {
      new MessageParams(
        MessageType.Info,
        s"Java home has been updated, you will need to restart the build server to apply the changes.",
      )
    }
    def params(isRestart: Boolean): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"Java home has been updated, do you want to ${if (isRestart) "restart"
          else "reconnect"} to the BSP server?"
      )
      params.setType(MessageType.Info)
      params.setActions(
        List(
          restart,
          notNow,
        ).asJava
      )
      params
    }
  }

  object RequestTimeout {

    val cancel = new MessageActionItem("Cancel")
    val waitAction = new MessageActionItem("Wait")
    val waitAlways = new MessageActionItem("Wait always")

    def notificationParams(actionName: String, minutes: Int): MessageParams = {
      new MessageParams(
        MessageType.Info,
        s"$actionName request is taking longer than expected (over $minutes minutes)",
      )
    }
    def params(actionName: String, minutes: Int): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"$actionName request is taking longer than expected (over $minutes minutes), do you want to cancel and rerun it?"
      )
      params.setType(MessageType.Info)
      params.setActions(
        List(
          cancel,
          waitAction,
          waitAlways,
        ).asJava
      )
      params
    }
  }

  def worksheetTimeout: String =
    """|Failed to evaluate worksheet, timeout reached, if needed modify add `metals.worksheet-timeout` property.
       |See: https://scalameta.org/metals/docs/troubleshooting/faq#i-see-spurious-errors-or-worksheet-fails-to-evaluate
       |""".stripMargin

  val indexing = "Indexing"
  val importingBuild = "Importing build"

  object ScalafixConfig {
    val adjustScalafix = new MessageActionItem("Yes")
    val ignore = new MessageActionItem("Not now")
    val dontShowAgain = new MessageActionItem("Don't show again")

    def message(
        settings: List[String],
        scalaVersion: String,
        isScala3Source: Boolean,
    ): String = {
      val scala3SourceText = if (isScala3Source) " with `-Xsource:3`" else ""
      s"""|Your `.scalafix.conf` misses the following settings for organize imports for $scalaVersion$scala3SourceText:
          |${settings.map(s => s"`$s`").mkString(", ")}.
          |""".stripMargin
    }

    def notificationParams(
        settings: List[String],
        scalaVersion: String,
        isScala3Source: Boolean,
    ): MessageParams = {
      new MessageParams(
        MessageType.Info,
        message(settings, scalaVersion, isScala3Source),
      )
    }

    def amendRequest(
        settings: List[String],
        scalaVersion: String,
        isScala3Source: Boolean,
    ): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      if (isScala3Source) " with `-Xsource:3`" else ""
      params.setMessage(
        s"""|${message(settings, scalaVersion, isScala3Source)}
            |Would you like to add them?
            |""".stripMargin
      )
      params.setType(MessageType.Info)
      params.setActions(List(adjustScalafix, ignore, dontShowAgain).asJava)
      params
    }
  }

  val missedByUser = new MessageActionItem("Missed by user")

}
object FileOutOfScalaCliBspScope {
  val regenerateAndRestart = new MessageActionItem("Yes")
  val openDoctor = new MessageActionItem("Open Doctor")
  val ignore = new MessageActionItem("No")
  def askToRegenerateConfigAndRestartBspMsg(file: String): String =
    s"""|$file is outside of scala-cli build server scope.
        |Would you like to fix this by regenerating bsp configuration and restarting the build server?""".stripMargin
  def askToRegenerateConfigAndRestartBsp(
      file: Path
  ): ShowMessageRequestParams = {
    val params = new ShowMessageRequestParams()
    params.setMessage(
      askToRegenerateConfigAndRestartBspMsg(
        s"File: ${file.getFileName().toString()}"
      )
    )
    params.setType(MessageType.Warning)
    params.setActions(List(regenerateAndRestart, ignore, openDoctor).asJava)
    params
  }
}
