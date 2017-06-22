package whisk.core.connector

import whisk.spi.Spi
import whisk.spi.SpiProvider

/**
  * Created by tnorris on 6/20/17.
  */
trait MessagingProvider extends Spi {
  def getConsumer(topic:String, maxdepth:Int = Int.MaxValue):MessageConsumer
  def getProducer():MessageProducer
}
object MessagingProvider extends SpiProvider[MessagingProvider]("whisk.spi.messaging.impl")