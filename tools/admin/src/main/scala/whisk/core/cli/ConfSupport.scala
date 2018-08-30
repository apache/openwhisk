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

package whisk.core.cli
import java.io.File

import org.rogach.scallop.singleArgConverter

trait ConfSupport {
  //Spring boot launch script changes the working directory to one where jar file is present
  //So invocation like ./bin/wskadmin-next -c config.conf would fail to resolve file as it would
  //be looked in directory where jar is present. This convertor makes use of `OLDPWD` to also
  //do a fallback check in that directory
  val relativeFileConverter = singleArgConverter[File] { f =>
    val f1 = new File(f)
    val oldpwd = System.getenv("OLDPWD")
    if (f1.exists())
      f1
    else if (oldpwd != null) {
      val f2 = new File(oldpwd, f)
      if (f2.exists()) f2 else f1
    } else {
      f1
    }
  }
}
