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

import scala.collection.JavaConverters._
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

/**
 * An Spi (Service Provider Interface) is an extension point.
 * At runtime, multiple Spi implementations may exist in the classpath, but only a single implementation
 * will be available for consumers to use. The specific implementation available at runtime is governed by the
 * configKey specified in the SpiProvider
 */
trait Spi extends Extension {

}

/**
 * Spi implementations must extend SpiModule to register a factory for their implementation.
 * The SpiModule implementations will be loaded at runtime via ServiceLoader
 *
 * @param tag
 * @param classTag
 * @tparam SpiImpl
 */
abstract class SpiModule[SpiImpl <: Spi](implicit tag: TypeTag[SpiImpl], classTag: ClassTag[SpiImpl]) {
    //lazy so that we can FIRST load the module, but THEN setup injectables and finally THEN initialize the instance
    // (we call getInstance exactly once per module!)
    lazy val spiImplInstance: SpiImpl = getInstance
    val spiTypeName: String = classTag.runtimeClass.getName
    protected def getInstance(): SpiImpl
}

/**
 * Spi providers must extend SpiProvider to expose an Extension for accessing the Spi impl
 *
 * @param configKey an key in application.conf which indicates which impl will be used at runtime
 * @param tag
 * @param classTag
 * @tparam T
 */
abstract class SpiProvider[T <: Spi](configKey: String)(implicit tag: TypeTag[T], classTag: ClassTag[T]) extends ExtensionId[T] with ExtensionIdProvider {

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

private class ModuleLoaderImpl(actorSystem: ActorSystem) extends Extension {
    /**
     * Load all SpiModule instances and filter to the one who provides this impl
     *
     * @param impl
     * @param tag
     * @tparam SpiImpl
     * @return
     */
    def getInstance[SpiImpl <: Spi](impl: String)(implicit tag: ClassTag[SpiImpl]): SpiModule[SpiImpl] = {
        val modules = (ServiceLoader load classOf[SpiModule[SpiImpl]]).asScala
        //filter the modules by spi type first, then use instance to filter by impl type
        //(this way the impl is constructed lazily, even though the module is loaded)
        val filteredModules = modules
                .filter(_.spiTypeName == tag.runtimeClass.getName)
        if (filteredModules.size == 0) {
            throw new IllegalArgumentException(s"no SpiModule implemented for type ${tag.runtimeClass}")
        }
        val spiModules = filteredModules
                .filter(_.spiImplInstance.getClass.getName == impl)
        if (spiModules.size == 0) {
            throw new IllegalArgumentException(s"no SpiModule for requested impl ${impl} for type ${tag.runtimeClass}; check configured impls for detected modules: ${filteredModules.map(_.getClass)} ")
        }
        if (spiModules.size > 1) {
            actorSystem.log.warning(s"multiple SpiModules loaded for type ${tag.runtimeClass}")
        }
        spiModules.head
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

