package scala.meta.internal.metals

import scala.collection.mutable

import scala.meta.internal.builds.BuildTool
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.BloopJsonUpdateCause.BloopJsonUpdateCause
import scala.meta.internal.metals.clients.language.MetalsInputBoxParams
import scala.meta.internal.metals.clients.language.MetalsSlowTaskParams
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

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
  val ImportAlreadyRunning = new MessageParams(
    MessageType.Warning,
    s"Import already running. \nPlease cancel the current import to run a new one.",
  )
  val ImportProjectPartiallyFailed = new MessageParams(
    MessageType.Warning,
    "Import project partially failed, limited functionality may work in some parts of the workspace. " +
      "See the logs for more details. ",
  )

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

  def bloopInstallProgress(buildToolExecName: String) =
    new MetalsSlowTaskParams(s"$buildToolExecName bloopInstall")

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
  }

  object MainClass {
    val message = "Multiple main classes found. Which would you like to run?"
  }

  object ChooseBuildTool {
    def params(builtTools: List[BuildTool]): ShowMessageRequestParams = {
      val messageActionItems =
        builtTools.map(bt => new MessageActionItem(bt.executableName))
      val params = new ShowMessageRequestParams()
      params.setMessage(
        "Multiple build definitions found. Which would you like to use?"
      )
      params.setType(MessageType.Info)
      params.setActions(messageActionItems.asJava)
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
        "Build is correctly configured now, navigation will work for all build targets.",
      )

    def moreInfo: String =
      " Select 'More information' to learn how to fix this problem.."

    def allProjectsMisconfigured: String =
      "Navigation will not work for this build due to mis-configuration." + moreInfo

    def singleMisconfiguredProject(name: String): String =
      s"Navigation will not work in project '$name' due to mis-configuration." + moreInfo

    def multipleMisconfiguredProjects(count: Int): String =
      s"Code navigation will not work for $count build targets in this workspace due to mis-configuration. " + moreInfo

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

    def params(tool: BuildTool): ShowMessageRequestParams = {
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

  object BloopVersionChange {
    def reconnect: MessageActionItem =
      new MessageActionItem("Restart Bloop")

    def notNow: MessageActionItem =
      new MessageActionItem("Not now")

    def params(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"Bloop version was updated, do you want to restart the running Bloop server?"
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

  object BloopGlobalJsonFilePremodified {
    def applyAndRestart: MessageActionItem =
      new MessageActionItem("Apply and Restart Bloop")

    def useGlobalFile: MessageActionItem =
      new MessageActionItem("Use the Global File's JVM Properties")

    def openGlobalJsonFile: MessageActionItem =
      new MessageActionItem("Open the Global File")

    def params(
        bloopJsonUpdateCause: BloopJsonUpdateCause
    ): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"""|Setting $bloopJsonUpdateCause will result in updating Bloop's global Json file by Metals, which has been previously modified manually!
            |Do you want to replace them with the new properties and restart the running Bloop server?""".stripMargin
      )
      params.setType(MessageType.Warning)
      params.setActions(
        List(
          applyAndRestart,
          useGlobalFile,
          openGlobalJsonFile,
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

    def params(
        bloopJsonUpdateCause: BloopJsonUpdateCause
    ): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"""|Setting $bloopJsonUpdateCause will result in updating Bloop's global Json file, by Metals.
            |Bloop will need to be restarted in order for these changes to take effect.""".stripMargin
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

  object AmmoniteJvmParametersChange {
    def restart: MessageActionItem =
      new MessageActionItem("Restart Ammonite")

    def notNow: MessageActionItem =
      new MessageActionItem("Not now")

    def params(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"Ammonite JVM parameters have been updated, do you want to restart the ammonite BSP server? (the changes will only be picked up after the restart)"
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

  object DebugClassNotFound {

    def invalidTargetClass(cls: String, target: String): MessageParams = {
      new MessageParams(
        MessageType.Error,
        s"Class '$cls' not found in build target '$target'.",
      )
    }

    def invalidTarget(target: String): MessageParams = {
      new MessageParams(
        MessageType.Error,
        s"Target '$target' not found.",
      )
    }

    def invalidClass(cls: String): MessageParams = {
      new MessageParams(
        MessageType.Error,
        s"Class '$cls' not found.",
      )
    }

  }

  object MissingScalafmtConf {
    def tryAgain(isAgain: Boolean): String =
      if (isAgain) ", try formatting again"
      else ""

    def createFile = new MessageActionItem("Create .scalafmt.conf")

    def fixedParams(isAgain: Boolean): MessageParams =
      new MessageParams(
        MessageType.Info,
        s"Created .scalafmt.conf${tryAgain(isAgain)}.",
      )

    def isCreateScalafmtConf(params: ShowMessageRequestParams): Boolean =
      params.getMessage == createScalafmtConfMessage

    def createScalafmtConfMessage: String =
      s"Unable to format since this workspace has no .scalafmt.conf file. " +
        s"To fix this problem, create an empty .scalafmt.conf and try again."

    def params(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(createScalafmtConfMessage)
      params.setType(MessageType.Error)
      params.setActions(
        List(
          createFile,
          notNow,
          dontShowAgain,
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
      s"You are using $using, which might not be supported in future versions of Metals. " +
        s"Please upgrade to $recommended."
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
      s"You are using an old sbt version, navigation for which might not be supported in the future versions of Metals. " +
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
    val doImportScalaCli: String = "Scala CLI"
    val doImportAmmonite: String = "Ammonite"
    val dismiss: String = "Dismiss"
    def params(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams(
        List(
          doImportScalaCli,
          doImportAmmonite,
          dismiss,
        )
          .map(new MessageActionItem(_))
          .asJava
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
    def ImportedAmmonite =
      new MessageParams(
        MessageType.Info,
        "Ammonite project imported.",
      )
  }

  object ImportAllScripts {
    val message: String = "Should Metals automatically import scripts?"
    val importAll: String = "Automatically import"
    val dismiss: String = "Keep asking"
    def params(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams(
        List(
          importAll,
          dismiss,
        )
          .map(new MessageActionItem(_))
          .asJava
      )
      params.setMessage(message)
      params.setType(MessageType.Info)
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

}
