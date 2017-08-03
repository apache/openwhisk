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

import com.typesafe.config.ConfigFactory
import java.util.concurrent.atomic.AtomicReference

/** Marker trait to mark an Spi */
trait Spi

/** Trait to be extended by factory objects creating Spi implementations */
trait SpiFactory[T <: Spi] {
    def apply(dependencies: Dependencies): T

    /**
     * Proxy method only called by the SpiLoader so the apply method
     * is overridable even with custom logic implemented.
     *
     * @param dependencies Dependencies to pass to the Spi
     */
    def buildInstance(dependencies: Dependencies): T = apply(dependencies)
}

/**
 * SpiFactory which is guaranteed to always return the same reference for
 * the same type of Spi.
 */
trait SingletonSpiFactory[T <: Spi] extends SpiFactory[T] {
    private val ref = new AtomicReference[T]()

    override def buildInstance(dependencies: Dependencies): T = {
        val oldValue = ref.get()
        if (oldValue != null.asInstanceOf[T]) {
            oldValue
        } else {
            val newValue = apply(dependencies)
            if (ref.compareAndSet(null.asInstanceOf[T], newValue)) {
                newValue
            } else {
                ref.get()
            }
        }
    }
}

trait SpiClassResolver {
    /** Resolves the implementation for a given type */
    def getClassNameForType[T](implicit man: Manifest[T]): String
}

object SpiLoader {
    /**
     * Instantiates an object of the given type.
     *
     * The ClassName to load is resolved via the SpiClassResolver in scode, which defaults to
     * a TypesafeConfig based resolver.
     */
    def get[A <: Spi](deps: Dependencies = Dependencies())(implicit resolver: SpiClassResolver = TypesafeConfigClassResolver, man: Manifest[A]): A = {
        val clazz = Class.forName(resolver.getClassNameForType[A] + "$")
        clazz.getField("MODULE$").get(clazz).asInstanceOf[SpiFactory[A]].buildInstance(deps)
    }
}

/** Lookup the classname for the SPI impl based on a key in the provided Config */
object TypesafeConfigClassResolver extends SpiClassResolver {
    private val config = ConfigFactory.load()

    override def getClassNameForType[T](implicit man: Manifest[T]): String = config.getString("whisk.spi." + man.runtimeClass.getSimpleName)
}

/**
 * Object containing arbitrary objects acting as dependencies.
 *
 * This is solely a helper type to cross the border between possibly heterogeneous Spi
 * interfaces and the production code.
 */
case class Dependencies(private val deps: Any*) {
    require(deps.map(_.getClass).distinct.size == deps.size, "A type can only occur once as a dependency")

    def get[T](implicit man: Manifest[T]): T =
        deps.find(d => man.runtimeClass.isAssignableFrom(d.getClass)) match {
            case Some(d: T) => d
            case _          => throw new IllegalArgumentException(s"missing dependency of type ${man.runtimeClass.getName}")
        }
}
