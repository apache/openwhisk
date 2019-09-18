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

package common

import scala.collection.mutable

/**
 * Extensions for Functions to provide introspection of their respective calls.
 *
 * Use like:
 *     val func = LoggedFunction { (a, b) => a + b }
 *     func(1, 2)
 *     func.calls should have size 1
 *     func.calls.head shouldBe (1, 2)
 */
class LoggedFunction1[A1, B](body: A1 => B) extends Function1[A1, B] {
  val calls = mutable.Buffer[A1]()

  override def apply(v1: A1): B = {
    calls += (v1)
    body(v1)
  }
}

class LoggedFunction2[A1, A2, B](body: (A1, A2) => B) extends Function2[A1, A2, B] {
  val calls = mutable.Buffer[(A1, A2)]()

  override def apply(v1: A1, v2: A2): B = {
    calls += ((v1, v2))
    body(v1, v2)
  }
}

class LoggedFunction3[A1, A2, A3, B](body: (A1, A2, A3) => B) extends Function3[A1, A2, A3, B] {
  val calls = mutable.Buffer[(A1, A2, A3)]()

  override def apply(v1: A1, v2: A2, v3: A3): B = {
    calls += ((v1, v2, v3))
    body(v1, v2, v3)
  }
}

class LoggedFunction4[A1, A2, A3, A4, B](body: (A1, A2, A3, A4) => B) extends Function4[A1, A2, A3, A4, B] {
  val calls = mutable.Buffer[(A1, A2, A3, A4)]()

  override def apply(v1: A1, v2: A2, v3: A3, v4: A4): B = {
    calls += ((v1, v2, v3, v4))
    body(v1, v2, v3, v4)
  }
}

class LoggedFunction5[A1, A2, A3, A4, A5, B](body: (A1, A2, A3, A4, A5) => B) extends Function5[A1, A2, A3, A4, A5, B] {
  val calls = mutable.Buffer[(A1, A2, A3, A4, A5)]()

  override def apply(v1: A1, v2: A2, v3: A3, v4: A4, v5: A5): B = {
    calls += ((v1, v2, v3, v4, v5))
    body(v1, v2, v3, v4, v5)
  }
}
class LoggedFunction6[A1, A2, A3, A4, A5, A6, B](body: (A1, A2, A3, A4, A5, A6) => B)
    extends Function6[A1, A2, A3, A4, A5, A6, B] {
  val calls = mutable.Buffer[(A1, A2, A3, A4, A5, A6)]()

  override def apply(v1: A1, v2: A2, v3: A3, v4: A4, v5: A5, v6: A6): B = {
    calls += ((v1, v2, v3, v4, v5, v6))
    body(v1, v2, v3, v4, v5, v6)
  }
}

class LoggedFunction7[A1, A2, A3, A4, A5, A6, A7, B](body: (A1, A2, A3, A4, A5, A6, A7) => B)
    extends Function7[A1, A2, A3, A4, A5, A6, A7, B] {
  val calls = mutable.Buffer[(A1, A2, A3, A4, A5, A6, A7)]()

  override def apply(v1: A1, v2: A2, v3: A3, v4: A4, v5: A5, v6: A6, v7: A7): B = {
    calls += ((v1, v2, v3, v4, v5, v6, v7))
    body(v1, v2, v3, v4, v5, v6, v7)
  }
}

class SynchronizedLoggedFunction1[A1, B](body: A1 => B) extends Function1[A1, B] {
  val calls = mutable.Buffer[A1]()

  override def apply(v1: A1): B = {
    calls.synchronized(calls += (v1))
    body(v1)
  }
}

class SynchronizedLoggedFunction2[A1, A2, B](body: (A1, A2) => B) extends Function2[A1, A2, B] {
  val calls = mutable.Buffer[(A1, A2)]()

  override def apply(v1: A1, v2: A2): B = {
    calls.synchronized(calls += ((v1, v2)))
    body(v1, v2)
  }
}

class SynchronizedLoggedFunction3[A1, A2, A3, B](body: (A1, A2, A3) => B) extends Function3[A1, A2, A3, B] {
  val calls = mutable.Buffer[(A1, A2, A3)]()

  override def apply(v1: A1, v2: A2, v3: A3): B = {
    calls.synchronized(calls += ((v1, v2, v3)))
    body(v1, v2, v3)
  }
}

class SynchronizedLoggedFunction4[A1, A2, A3, A4, B](body: (A1, A2, A3, A4) => B) extends Function4[A1, A2, A3, A4, B] {
  val calls = mutable.Buffer[(A1, A2, A3, A4)]()

  override def apply(v1: A1, v2: A2, v3: A3, v4: A4): B = {
    calls.synchronized(calls += ((v1, v2, v3, v4)))
    body(v1, v2, v3, v4)
  }
}

class SynchronizedLoggedFunction5[A1, A2, A3, A4, A5, B](body: (A1, A2, A3, A4, A5) => B)
    extends Function5[A1, A2, A3, A4, A5, B] {
  val calls = mutable.Buffer[(A1, A2, A3, A4, A5)]()

  override def apply(v1: A1, v2: A2, v3: A3, v4: A4, v5: A5): B = {
    calls.synchronized(calls += ((v1, v2, v3, v4, v5)))
    body(v1, v2, v3, v4, v5)
  }
}
class SynchronizedLoggedFunction6[A1, A2, A3, A4, A5, A6, B](body: (A1, A2, A3, A4, A5, A6) => B)
    extends Function6[A1, A2, A3, A4, A5, A6, B] {
  val calls = mutable.Buffer[(A1, A2, A3, A4, A5, A6)]()

  override def apply(v1: A1, v2: A2, v3: A3, v4: A4, v5: A5, v6: A6): B = {
    calls.synchronized(calls += ((v1, v2, v3, v4, v5, v6)))
    body(v1, v2, v3, v4, v5, v6)
  }
}

object LoggedFunction {
  def apply[A1, B](body: (A1) => B) = new LoggedFunction1[A1, B](body)
  def apply[A1, A2, B](body: (A1, A2) => B) = new LoggedFunction2[A1, A2, B](body)
  def apply[A1, A2, A3, B](body: (A1, A2, A3) => B) = new LoggedFunction3[A1, A2, A3, B](body)
  def apply[A1, A2, A3, A4, B](body: (A1, A2, A3, A4) => B) = new LoggedFunction4[A1, A2, A3, A4, B](body)
  def apply[A1, A2, A3, A4, A5, B](body: (A1, A2, A3, A4, A5) => B) = new LoggedFunction5[A1, A2, A3, A4, A5, B](body)
  def apply[A1, A2, A3, A4, A5, A6, B](body: (A1, A2, A3, A4, A5, A6) => B) =
    new LoggedFunction6[A1, A2, A3, A4, A5, A6, B](body)
  def apply[A1, A2, A3, A4, A5, A6, A7, B](body: (A1, A2, A3, A4, A5, A6, A7) => B) =
    new LoggedFunction7[A1, A2, A3, A4, A5, A6, A7, B](body)
}

object SynchronizedLoggedFunction {
  def apply[A1, B](body: (A1) => B) = new SynchronizedLoggedFunction1[A1, B](body)
  def apply[A1, A2, B](body: (A1, A2) => B) = new SynchronizedLoggedFunction2[A1, A2, B](body)
  def apply[A1, A2, A3, B](body: (A1, A2, A3) => B) = new SynchronizedLoggedFunction3[A1, A2, A3, B](body)
  def apply[A1, A2, A3, A4, B](body: (A1, A2, A3, A4) => B) = new SynchronizedLoggedFunction4[A1, A2, A3, A4, B](body)
  def apply[A1, A2, A3, A4, A5, B](body: (A1, A2, A3, A4, A5) => B) =
    new SynchronizedLoggedFunction5[A1, A2, A3, A4, A5, B](body)
  def apply[A1, A2, A3, A4, A5, A6, B](body: (A1, A2, A3, A4, A5, A6) => B) =
    new SynchronizedLoggedFunction6[A1, A2, A3, A4, A5, A6, B](body)
}
