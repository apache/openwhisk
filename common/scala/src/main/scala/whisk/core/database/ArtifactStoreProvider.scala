package whisk.core.database

import akka.actor.ActorSystem
import spray.json.RootJsonFormat
import whisk.common.Logging
import whisk.core.WhiskConfig
import whisk.spi.Spi
import whisk.spi.SpiProvider

/**
  * Created by tnorris on 6/20/17.
  */

trait ArtifactStoreProvider extends Spi {
  def makeStore[D <: DocumentSerializer](config: WhiskConfig, name: WhiskConfig => String)(
    implicit jsonFormat: RootJsonFormat[D],
    actorSystem: ActorSystem,
    logging: Logging): ArtifactStore[D]
}
object ArtifactStoreProvider  extends SpiProvider[ArtifactStoreProvider]("whisk.spi.database.impl")