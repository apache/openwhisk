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

package org.apache.openwhisk.standalone

import java.io.{File, InputStream}
import java.nio.file.Files
import java.util.zip.ZipInputStream

object Unzip {

  def apply(is: InputStream, dir: File): Unit = {
    val zis = new ZipInputStream(is)
    val dest = dir.toPath
    val buffer = new Array[Byte](4096)

    try {
      var zipEntry = zis.getNextEntry
      while (zipEntry != null) {
        val outPath = dest.resolve(zipEntry.getName)
        if (zipEntry.isDirectory) {
          Files.createDirectories(outPath)
        } else {
          Files.createDirectories(outPath.getParent)
          val out = Files.newOutputStream(outPath)
          try {
            var bytesRead = zis.read(buffer)
            while (bytesRead != -1) {
              out.write(buffer, 0, bytesRead)
              bytesRead = zis.read(buffer)
            }
          } finally {
            out.close()
          }
        }
        zis.closeEntry()
        zipEntry = zis.getNextEntry
      }
    } finally {
      zis.close()
    }
  }

}
