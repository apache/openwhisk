package whisk.spi

import com.typesafe.config.ConfigFactory
import java.util.concurrent.atomic.AtomicReference

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

trait Spi

trait SpiFactory[T <: Spi] {
    def apply(dependencies: Dependencies): T
}

trait SingletonSpiFactory[T <: Spi] extends SpiFactory[T]{
    private val ref = new AtomicReference[T]()
    def buildInstance(dependencies: Dependencies): T
    override def apply(dependencies: Dependencies): T = {
        val oldValue = ref.get()
        if (oldValue != null.asInstanceOf[T]) {
            oldValue
        } else {
            val newValue = buildInstance(dependencies)
            if (ref.compareAndSet(null.asInstanceOf[T], newValue)){
                newValue
            } else {
                ref.get()
            }
        }
    }
}

trait SpiClassResolver {
    def getKeyForType[T](implicit man:Manifest[T]):String
    def getClassnameForKey(key: String): String
}

object SpiLoader {
    def get[A <: Spi](deps: Dependencies = new Dependencies())(implicit resolver: SpiClassResolver = TypesafeConfigClassResolver, man: Manifest[A]): A = {
        val key = resolver.getKeyForType[A]
        val clazz = Class.forName(resolver.getClassnameForKey(key) + "$")
        clazz.getField("MODULE$").get(clazz).asInstanceOf[SpiFactory[A]].apply(deps)
    }
}

/**
 * Lookup the classname for the SPI impl based on a key in the provided Config
 */
object TypesafeConfigClassResolver extends SpiClassResolver {
    val config = ConfigFactory.load()
    override def getClassnameForKey(key: String): String = config.getString(key)
    override def getKeyForType[T](implicit man:Manifest[T]): String =  "whisk.spi." + man.runtimeClass.getSimpleName
}


class Dependencies(deps: Any*) {
    def get[T](implicit man: Manifest[T]) = {
        //check for interface compatible types
        deps.find(d => man.runtimeClass.isAssignableFrom(d.getClass)) match {
            case Some(d) => d.asInstanceOf[T]
            case None => throw new IllegalArgumentException(s"missing dependency of type ${man.runtimeClass.getName}")
        }
    }
}
object Dependencies {
    def apply(deps: Any*): Dependencies = {
        //validate no duplicates
        if (deps.map(d => d.getClass.getName).distinct.size != deps.size){
            throw new IllegalArgumentException(s"Dependencies types can only be used once: ${deps}")
        }
        new Dependencies(deps:_*)
    }

}
