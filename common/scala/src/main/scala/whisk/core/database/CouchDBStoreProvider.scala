package whisk.core.database

import akka.actor.ActorSystem
import spray.json.RootJsonFormat
import whisk.common.Logging
import whisk.core.WhiskConfig
import whisk.spi.SpiModule

/**
  * Created by tnorris on 6/20/17.
  */
class CouchDBStoreProvider extends ArtifactStoreProvider{
  def makeStore[D <: DocumentSerializer](config: WhiskConfig, name: WhiskConfig => String)(
    implicit jsonFormat: RootJsonFormat[D],
    actorSystem: ActorSystem,
    logging: Logging): ArtifactStore[D] = {
    require(config != null && config.isValid, "config is undefined or not valid")
    require(config.dbProvider == "Cloudant" || config.dbProvider == "CouchDB", "Unsupported db.provider: " + config.dbProvider)
    assume(Set(config.dbProtocol, config.dbHost, config.dbPort, config.dbUsername, config.dbPassword, name(config)).forall(_.nonEmpty), "At least one expected property is missing")

    new CouchDbRestStore[D](config.dbProtocol, config.dbHost, config.dbPort.toInt, config.dbUsername, config.dbPassword, name(config))
  }
}

class CouchDBStoreProviderModule extends SpiModule[ArtifactStoreProvider]{
  def getInstance = new CouchDBStoreProvider
}