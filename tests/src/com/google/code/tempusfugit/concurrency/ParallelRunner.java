/*
 * Copyright (c) 2009-2015, toby weston & tempus-fugit committers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Here's a quick hack to allow us to control the number of threads
 * used by tempus fugit dynamically.   This is cloned-and-owned from tempusfugit
 * since the library is not designed to allow extension.
 *
 * We have to put it in this package to fake Java out into allowing us to
 * see package-protected classes.
 */

package com.google.code.tempusfugit.concurrency;

import static java.util.concurrent.Executors.newFixedThreadPool;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;

import com.google.code.tempusfugit.concurrency.ConcurrentTestRunner;
import common.WhiskProperties;

public class ParallelRunner extends BlockJUnit4ClassRunner {

    public ParallelRunner(Class<?> type) throws InitializationError {
        super(type);
        setScheduler(new ConcurrentScheduler(createExecutor(type)));
    }

    private static ExecutorService createExecutor(Class<?> type) {
        assert WhiskProperties.concurrentTestCount >= 1;
        System.out.println("ParallelRunner: " + WhiskProperties.concurrentTestCount + " threads.");
        return newFixedThreadPool(WhiskProperties.concurrentTestCount, new ConcurrentTestRunnerThreadFactory());
    }

    private static class ConcurrentTestRunnerThreadFactory implements ThreadFactory {
        private AtomicLong count = new AtomicLong();

        public Thread newThread(Runnable runnable) {
            return new Thread(runnable, ConcurrentTestRunner.class.getSimpleName() + "-Thread-" + count.getAndIncrement());
        }
    }
}
