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

import java.io.{File, FileOutputStream, InputStream}
import java.util.zip.ZipInputStream

object Unzip {

  def apply(is: InputStream, dir: File): Unit = {
    //Based on https://stackoverflow.com/a/40547896/1035417
    val zis = new ZipInputStream((is))
    val dest = dir.toPath
    Stream.continually(zis.getNextEntry).takeWhile(_ != null).foreach { zipEntry =>
      if (!zipEntry.isDirectory) {
        val outPath = dest.resolve(zipEntry.getName)
        val outPathParent = outPath.getParent
        if (!outPathParent.toFile.exists()) {
          outPathParent.toFile.mkdirs()
        }

        val outFile = outPath.toFile
        val out = new FileOutputStream(outFile)
        val buffer = new Array[Byte](4096)
        Stream.continually(zis.read(buffer)).takeWhile(_ != -1).foreach(out.write(buffer, 0, _))
        out.close()
      }
    }
    zis.close()
  }

}
