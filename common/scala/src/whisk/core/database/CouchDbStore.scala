package whisk.core.database

import org.lightcouch.{ View => CouchView }

object CouchDbStore {
  def make[R,D](host: String, port: Int, dbUsername: String, dbPassword: String, dbName: String)(implicit ev: D <:< DocumentSerializer) = {
      new CouchDbLikeStore[CouchView,R,D](CouchDbProvider, host, port, dbUsername, dbPassword, dbName)
  }
}
