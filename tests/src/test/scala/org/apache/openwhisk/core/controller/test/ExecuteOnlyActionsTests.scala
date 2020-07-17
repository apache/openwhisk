package org.apache.openwhisk.core.controller.test

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.controller.WhiskActionsApi


@RunWith(classOf[JUnitRunner])
class ExecuteOnlyActionsTests extends ControllerTestCommon with WhiskActionsApi{
  behavior of("Execute Only for Actions")

  val creds = WhiskAuthHelpers.newIdentity()
  val namespace = EntityPath(creds.subject.asString)
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
  def aname() = MakeName.next("package_action_tests")

  private val executeOnly = {
    import pureconfig._
    import pureconfig.generic.auto._
    import org.apache.openwhisk.core.ConfigKeys
    try {
      loadConfigOrThrow[Boolean](ConfigKeys.packageExecuteOnly)
    }catch{
      case ex: Exception => false
    }
  }

  it should("deny access to get of action in binding of shared package when config option is enabled") in {
    if (executeOnly == true){
      implicit val tid = transid()
      val auser = WhiskAuthHelpers.newIdentity()
      val provider = WhiskPackage(namespace, aname(), None, Parameters("p", "P"), publish = true)
      val binding = WhiskPackage(EntityPath(auser.subject.asString), aname(), provider.bind, Parameters("b", "B"))
      val action = WhiskAction(provider.fullPath, aname(), jsDefault("??"), Parameters("a", "A"))
      put(entityStore, provider)
      put(entityStore, binding)
      put(entityStore, action)
      Get(s"$collectionPath/${binding.name}/${action.name}") ~> Route.seal(routes(auser)) ~> check {
        status should be(Forbidden)
      }
    }
  }

  it should("allow access to get of action in binding of shared package when config option is disabled") in {
    if (executeOnly == false){
      implicit val tid = transid()
      val auser = WhiskAuthHelpers.newIdentity()
      val provider = WhiskPackage(namespace, aname(), None, Parameters("p", "P"), publish = true)
      val binding = WhiskPackage(EntityPath(auser.subject.asString), aname(), provider.bind, Parameters("b", "B"))
      val action = WhiskAction(provider.fullPath, aname(), jsDefault("??"), Parameters("a", "A"))
      put(entityStore, provider)
      put(entityStore, binding)
      put(entityStore, action)
      Get(s"$collectionPath/${binding.name}/${action.name}") ~> Route.seal(routes(auser)) ~> check {
        status should be(OK)
      }
    }
  }
}