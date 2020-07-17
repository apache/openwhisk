package org.apache.openwhisk.core.controller.test

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.apache.openwhisk.core.controller.WhiskPackagesApi
import org.apache.openwhisk.core.entity._



@RunWith(classOf[JUnitRunner])
class ExecuteOnlyPackagesTests extends ControllerTestCommon with WhiskPackagesApi{
  behavior of("Execute Only for Packages")

  /** Initialize a user identity, and some relevant information */
  val creds = WhiskAuthHelpers.newIdentity()
  val namespace = EntityPath(creds.subject.asString)
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
  def aname() = MakeName.next("execute_only_tests")
  val parametersLimit = Parameters.sizeLimit

  private def bindingAnnotation(binding: Binding) = {
    Parameters(WhiskPackage.bindingFieldName, Binding.serdes.write(binding))
  }


  /** Read in config Option from the config key */
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

  it should("deny access to get of shared package when config option is enabled") in {
    //set config option to true
    //create shared package within current identity
    if (executeOnly == true){
      implicit val tid = transid()
      val auser = WhiskAuthHelpers.newIdentity()
      val provider = WhiskPackage(namespace, aname(), None, publish = true)
      put(entityStore, provider)

      Get(s"/$namespace/${collection.path}/${provider.name}") ~> Route.seal(routes(auser)) ~> check {
        status should be(Forbidden)
      }
    }

  }

  it should("allow access to get of shared package when config option is disabled") in {
    //set config option to false
    if (executeOnly == false){
      implicit val tid = transid()
      val auser = WhiskAuthHelpers.newIdentity()
      val provider = WhiskPackage(namespace, aname(), None, publish = true)
      put(entityStore, provider)

      Get(s"/$namespace/${collection.path}/${provider.name}") ~> Route.seal(routes(auser)) ~> check {
        status should be(OK)
      }
    }
  }
  it should ("deny access to get of shared package binding when config option is enabled") in {
    if (executeOnly == true){
      implicit val tid = transid()
      val auser = WhiskAuthHelpers.newIdentity()
      val provider = WhiskPackage(namespace, aname(), None, Parameters("p", "P"), publish = true)
      val binding = WhiskPackage(EntityPath(auser.subject.asString), aname(), provider.bind, Parameters("b", "B"))
      put(entityStore, provider)
      put(entityStore, binding)
      Get(s"/$namespace/${collection.path}/${provider.name}") ~> Route.seal(routes(auser)) ~> check {
        status should be(Forbidden)
      }
    }
  }

  it should("allow access to get of shared package binding when config option is disabled") in {
    if (executeOnly == false){
      implicit val tid = transid()
      val auser = WhiskAuthHelpers.newIdentity()
      val provider = WhiskPackage(namespace, aname(), None, Parameters("p", "P"), publish = true)
      val binding = WhiskPackage(EntityPath(auser.subject.asString), aname(), provider.bind, Parameters("b", "B"))
      put(entityStore, provider)
      put(entityStore, binding)
      Get(s"/$namespace/${collection.path}/${provider.name}") ~> Route.seal(routes(auser)) ~> check {
        status should be(OK)
      }
    }
  }

}