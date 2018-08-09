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
// Copyright 2017-2018 Adobe.
package com.karate.openwhisk.utils;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.intuit.karate.FileUtils;
public class OWFileUtil {
    private static final Logger logger = LoggerFactory.getLogger(OWFileUtil.class);

    public static String writeToFile(String jsonMsg, String fileName) {
        File parentDir = FileUtils.getDirContaining(OWFileUtil.class);
        //String parentDir = System.getProperty("user.dir") + "/src/test/java/com/karate/openwhisk/utils";
        System.out.println("Parent dir path: "+parentDir);
        File file = new File(parentDir, fileName);
        FileWriter writer = null;
        BufferedWriter bufferedWriter = null;
        try {
            writer = new FileWriter(file);
            bufferedWriter = new BufferedWriter(writer);
            bufferedWriter.write(jsonMsg);
        } catch (IOException e) {
            logger.error("Error saving {} to file {}", jsonMsg, fileName, e);
            throw new RuntimeException(e);
        } finally {
            if (bufferedWriter != null) {
                try {
                    bufferedWriter.close();
                } catch (IOException e) {
                }
            }
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                }
            }
        }
        logger.info("Successfully saved {} content: {}", fileName, jsonMsg);
        return "success";
    }


}
