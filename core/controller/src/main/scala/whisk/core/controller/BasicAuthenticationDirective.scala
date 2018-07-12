package whisk.core.controller

import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.{AuthenticationDirective, AuthenticationResult}
import whisk.common.{Logging, TransactionId}
import whisk.core.database.NoDocumentException
import whisk.core.entity._
import whisk.core.entity.types.AuthStore

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object BasicAuthenticationDirective extends AuthenticationDirectiveProvider {

  def validateCredentials(credentials: Option[BasicHttpCredentials])(implicit transid: TransactionId,
                                                                     ec: ExecutionContext,
                                                                     logging: Logging,
                                                                     authStore: AuthStore): Future[Option[Identity]] = {
    credentials flatMap { pw =>
      Try {
        // authkey deserialization is wrapped in a try to guard against malformed values
        val authkey = BasicAuthenticationAuthKey(UUID(pw.username), Secret(pw.password))
        logging.info(this, s"authenticate: ${authkey.uuid}")
        val future = Identity.get(authStore, authkey) map { result =>
          if (authkey == result.authkey) {
            logging.debug(this, s"authentication valid")
            Some(result)
          } else {
            logging.debug(this, s"authentication not valid")
            None
          }
        } recover {
          case _: NoDocumentException | _: IllegalArgumentException =>
            logging.debug(this, s"authentication not valid")
            None
        }
        future onFailure { case t => logging.error(this, s"authentication error: $t") }
        future
      }.toOption
    } getOrElse {
      credentials.foreach(_ => logging.debug(this, s"credentials are malformed"))
      Future.successful(None)
    }
  }

  /** Creates HTTP BasicAuth handler */
  def basicAuth[A](verify: Option[BasicHttpCredentials] => Future[Option[A]]): AuthenticationDirective[A] = {
    extractExecutionContext.flatMap { implicit ec =>
      authenticateOrRejectWithChallenge[BasicHttpCredentials, A] { creds =>
        verify(creds).map {
          case Some(t) => AuthenticationResult.success(t)
          case None    => AuthenticationResult.failWithChallenge(HttpChallenges.basic("OpenWhisk secure realm"))
        }
      }
    }
  }

  def authenticate(implicit transid: TransactionId,
                   authStore: AuthStore,
                   logging: Logging): AuthenticationDirective[Identity] = {
    extractExecutionContext.flatMap { implicit ec =>
      basicAuth(validateCredentials)
    }
  }
}
