package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

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
)(implicit ec: ExecutionContext) {
  private val section = "metals"
  private val supportsConfiguration: Boolean = (for {
    capabilities <- Option(initializeParams.getCapabilities)
    workspace <- Option(capabilities.getWorkspace)
    out <- Option(workspace.getConfiguration())
  } yield out.booleanValue()).getOrElse(false)

  def syncUserConfiguration(services: List[MetalsLspService]): Future[Unit] =
    optSyncUserConfiguration(services).getOrElse(Future.successful(()))

  def onDidChangeConfiguration(
      params: lsp4j.DidChangeConfigurationParams,
      services: List[MetalsLspService],
  ): Future[Unit] =
    optSyncUserConfiguration(services)
      .orElse {
        val fullJson =
          params.getSettings.asInstanceOf[JsonElement].getAsJsonObject
        for {
          metalsSection <- Option(fullJson.getAsJsonObject(section))
          newConfig <- userConfigFrom(metalsSection)
        } yield Future
          .sequence(services.map(_.onUserConfigUpdate(newConfig)))
          .ignoreValue
      }
      .getOrElse(Future.successful(()))

  private def optSyncUserConfiguration(
      services: List[MetalsLspService]
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
                  .map(folder.onUserConfigUpdate)
                  .getOrElse(Future.successful(()))
            }
          )
          .ignoreValue
      } yield res
    }

  private def userConfigFrom(
      json: JsonObject
  ): Option[UserConfiguration] =
    UserConfiguration.fromJson(json, clientConfig) match {
      case Left(errors) =>
        errors.foreach { error => scribe.error(s"config error: $error") }
        None
      case Right(newUserConfig) => Some(newUserConfig)
    }
}
