package whisk.core.database

import com.cloudant.client.api.{ View => CloudantView }

object CloudantStore {
  def make[R,D](protocol: String, host: String, port: Int, dbUsername: String, dbPassword: String, dbName: String)(implicit ev: D <:< DocumentSerializer) = {
      new CouchDbLikeStore[CloudantView,R,D](CloudantProvider, "https", host, port, dbUsername, dbPassword, dbName)
  }
}
