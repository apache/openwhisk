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

package system.rest

import com.github.fge.jsonschema.main.JsonSchemaFactory
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Utilities for dealing with JSON schema
 *
 */
trait JsonSchema {

  /**
   * Check whether a JSON document (represented as a String) conforms to a JSON schema (also a String).
   *
   * @return true if the document is valid, false otherwise
   */
  def check(doc: String, schema: String): Boolean = {
    val mapper = new ObjectMapper()
    val docNode = mapper.readTree(doc)
    val schemaNode = mapper.readTree(schema)

    val validator = JsonSchemaFactory.byDefault().getValidator
    validator.validate(schemaNode, docNode).isSuccess
  }
}
