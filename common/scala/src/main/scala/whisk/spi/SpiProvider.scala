/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.spi

import java.util.ServiceLoader

import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import scaldi.Binding
import scaldi.DynamicModule
import scaldi.Identifier
import scaldi.ImmutableWrapper
import scaldi.Injectable
import scaldi.Injector
import scaldi.Module
import scaldi.MutableInjector
import whisk.common.Logging
import whisk.core.WhiskConfig

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

/**
  * Created by tnorris on 6/8/17.
  */


/**
  * An Spi (Service Provider Interface) is an extension point.
  * At runtime, multiple Spi implementations may exist in the classpath, but only a single implementation
  * will be available for consumers to use. The specific implementation available at runtime is governed by the
  * configKey specified in the SpiProvider
  */
trait Spi  extends Extension {

}

/**
  * Spi implementations must extend SpiModule to register a factory for their implementation.
  * The SpiModule implementations will be loaded at runtime via ServiceLoader
  * @param tag
  * @param classTag
  * @tparam SpiImpl
  */
abstract class SpiModule[SpiImpl <: Spi ](implicit tag:TypeTag[SpiImpl], classTag:ClassTag[SpiImpl])  {
  //lazy so that we can FIRST load the module, but THEN setup injectables and finally THEN initialize the instance
  // (we call getInstance exactly once per module!)
  lazy val spiImplInstance:SpiImpl = getInstance
  val spiTypeName:String = classTag.runtimeClass.getName
  protected [spi] def getInstance:SpiImpl
}

/**
  * An extended SpiModule that will register a factory that uses an Injector for binding additional dependencies
  * during instantiation of the Spi implementation
  * @param ev$1
  * @param tag
  * @tparam SpiImpl
  */
abstract class SpiFactoryModule[SpiImpl <: Spi : TypeTag](implicit tag:ClassTag[SpiImpl])
  extends SpiModule[SpiImpl] with Injectable{
  protected [spi] final def getInstance:SpiImpl = getInstance(SharedModules.sharedModulesInjector)
  protected [spi] def getInstance(implicit injector:Injector): SpiImpl
}

/**
  * Spi providers must extend SpiProvider to expose an Extension for accessing the Spi impl
  *
  * @param configKey an key in application.conf which indicates which impl will be used at runtime
  * @param tag
  * @param classTag
  * @tparam T
  */
abstract class SpiProvider[T <: Spi](configKey: String)(implicit tag: TypeTag[T], classTag:ClassTag[T])extends ExtensionId[T] with ExtensionIdProvider {

  override def apply(system: ActorSystem): T = {
    system.registerExtension(this)
  }

  override def createExtension(system: ExtendedActorSystem): T = {
    val configuredImpl = system.settings.config.getString(configKey)
    if (configuredImpl != null) {
      val resolved = ModuleLoader(system).getInstance[T](configuredImpl).spiImplInstance
      system.log.info(s"Resolved spi ${classTag.runtimeClass.getName} to impl ${resolved.getClass.getName} using config key '${configKey}'.")
      resolved
    } else {
      //no default and no override? return Nothing?
      throw new IllegalStateException(s"no default and no override config key ${configKey} to resolve impl")
    }
  }

  override def lookup(): ExtensionId[_ <: Extension] = {
    this
  }
}
/**
  * Used for injecting some shared modules into SPI Modules (e.g. WhiskConfig, Logging, etc)
  * @param modules
  */
private class SharedModulesInjector(modules: Seq[Injector]) extends Injector {
  //just delegate getBinding(s) calls to the composite of all the shared modules
  val composite:MutableInjector = modules.foldLeft(SharedModules.empty)((composite, nextModule) => {
    composite :: nextModule
  })
  override def getBinding(identifiers: List[Identifier]): Option[Binding] = composite.getBinding(identifiers)

  override def getBindings(identifiers: List[Identifier]): List[Binding] = composite.getBindings(identifiers)
}

/**
  * Register a List o f Modules
  */
object SharedModules {
  def empty = new MutableInjector{
    override def getBinding(identifiers: List[Identifier]): Option[Binding] = None
    override def getBindings(identifiers: List[Identifier]): List[Binding] = List()
  }
  private var modules = ListBuffer[Injector]()

  //we have to wrap all modules each time they are used for injection, since they become frozen after each use...
  def sharedModulesInjector:Injector = {

    val wrappedModules = ListBuffer[Injector]()

    modules.foreach(mod => {
      wrappedModules += ImmutableWrapper(mod)
    })
    //modules ++= injectableModules
    new SharedModulesInjector(wrappedModules)
  }
  def addSharedModules(modules:Injector*) = {
    modules.foreach(module => {
      this.modules += module
    })
  }
  //a wrapper for Binder.bind[Type] to instance
  def bind[SpiType: TypeTag](instance:SpiType) = {
    val dynModule = DynamicModule(module => {
      module.bind[SpiType] to instance
    })
    modules += dynModule
  }
}

/**
  * Convenience Module - common bindings that all apps would typically use
  * @param actorSystem
  * @param config
  * @param logging
  */
class SharedModule(actorSystem:ActorSystem, config:WhiskConfig, logging:Logging) extends Module {
  bind [ActorSystem] to actorSystem
  bind [WhiskConfig] to config
  bind [Logging] to logging
}

private class ModuleLoaderImpl(actorSystem:ActorSystem) extends Extension{
  /**
    * Load all SpiModule instances and filter to the one who provides this impl
    * @param impl
    * @param tag
    * @tparam SpiImpl
    * @return
    */
  def getInstance[SpiImpl <: Spi](impl:String)(implicit tag:ClassTag[SpiImpl]):SpiModule[SpiImpl] = {
    val spiImpls = (ServiceLoader load classOf[SpiModule[SpiImpl]]).asScala
    //filter the modules by spi type first, then use instance to filter by impl type
    //(this way the impl is constructed lazily, even though the module is loaded)
    val filtered = spiImpls
      .filter(_.spiTypeName == tag.runtimeClass.getName)
      .filter(_.spiImplInstance.getClass.getName == impl)
    if (filtered.size == 0){
      throw new IllegalArgumentException(s"no SpiModule implemented for type ${tag.runtimeClass}")
    }
    if (filtered.size > 1) {
      actorSystem.log.warning(s"multiple SpiModules loaded for type ${tag.runtimeClass}")
    }
    filtered.head.asInstanceOf[SpiModule[SpiImpl]]
  }
}
private object ModuleLoader
  extends ExtensionId[ModuleLoaderImpl]
    with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem): ModuleLoaderImpl = {
    new ModuleLoaderImpl(system)
  }

  override def lookup(): ExtensionId[_ <: Extension] = ModuleLoader
}

