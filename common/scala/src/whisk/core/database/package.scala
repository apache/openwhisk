package whisk.core

import com.cloudant.client.api.{ View => CloudantView }
import org.lightcouch.{ View => CouchView }

package object database {
    implicit val cloudantViewProvider: CouchDbLikeViewProvider[CloudantView] = CloudantViewProvider
    implicit val couchDbViewProvider: CouchDbLikeViewProvider[CouchView] = CouchDbViewProvider
}
