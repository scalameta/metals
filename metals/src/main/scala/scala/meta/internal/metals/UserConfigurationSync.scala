package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.eclipse.lsp4j

class UserConfigurationSync(
    initializeParams: lsp4j.InitializeParams,
    languageClient: MetalsLanguageClient,
    clientConfig: ClientConfiguration,
    featureFlags: FeatureFlagProvider,
)(implicit ec: ExecutionContext) {
  private val section = "metals"
  private val supportsConfiguration: Boolean = (for {
    capabilities <- Option(initializeParams.getCapabilities)
    workspace <- Option(capabilities.getWorkspace)
    out <- Option(workspace.getConfiguration())
  } yield out.booleanValue()).getOrElse(false)

  def initSyncUserConfiguration(
      services: List[MetalsLspService]
  ): Future[Unit] =
    optSyncUserConfiguration(
      services,
      folder =>
        newConfig => {
          folder.setUserConfig(newConfig)
          Future.unit
        },
    ).getOrElse(Future.successful(()))

  def onDidChangeConfiguration(
      params: lsp4j.DidChangeConfigurationParams,
      services: List[MetalsLspService],
  ): Future[Unit] =
    optSyncUserConfiguration(
      services,
      folder => newConfig => folder.onUserConfigUpdate(newConfig),
    )
      .orElse {
        val fullJson =
          params.getSettings.asInstanceOf[JsonElement].getAsJsonObject
        val metalsSection =
          Option(fullJson.getAsJsonObject(section)).getOrElse(new JsonObject())
        for {
          newConfig <- userConfigFrom(metalsSection)
        } yield Future
          .sequence(services.map(_.onUserConfigUpdate(newConfig)))
          .ignoreValue
      }
      .getOrElse(Future.successful(()))

  private def optSyncUserConfiguration(
      services: List[MetalsLspService],
      consume: MetalsLspService => UserConfiguration => Future[Unit],
  ): Option[Future[Unit]] =
    Option.when(supportsConfiguration) {
      val items = services.map { service =>
        val configItem = new lsp4j.ConfigurationItem()
        configItem.setScopeUri(service.path.toURI.toString())
        configItem.setSection(section)
        configItem
      }
      val params = new lsp4j.ConfigurationParams(items.asJava)
      for {
        items <- languageClient.configuration(params).asScala
        res <- Future
          .sequence(
            items.asScala.toList.zip(services).map {
              case (_: JsonNull, _) => Future.successful(())
              case (item, folder) =>
                val json = item.asInstanceOf[JsonElement].getAsJsonObject()
                userConfigFrom(json)
                  .map(consume(folder))
                  .getOrElse(Future.successful(()))
            }
          )
          .ignoreValue
      } yield res
    }

  private def userConfigFrom(
      json: JsonObject
  ): Option[UserConfiguration] =
    UserConfiguration.fromJson(
      json,
      clientConfig,
      featureFlags = featureFlags,
    ) match {
      case Left(errors) =>
        errors.foreach { error => scribe.error(s"config error: $error") }
        None
      case Right(newUserConfig) =>
        scribe.debug("New user configuration: " + newUserConfig)
        Some(newUserConfig)
    }
}
