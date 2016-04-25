/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.common

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.Base64

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.language.postfixOps

import org.apache.http.NameValuePair
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.client.utils.URIBuilder
import org.apache.http.config.RegistryBuilder
import org.apache.http.conn.HttpHostConnectException
import org.apache.http.conn.socket.ConnectionSocketFactory
import org.apache.http.conn.socket.PlainConnectionSocketFactory
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.message.BasicNameValuePair
import org.apache.http.ssl.SSLContextBuilder
import org.apache.http.ssl.TrustStrategy
import org.apache.http.util.EntityUtils

import spray.json.JsValue
import javax.net.ssl.SSLSocketFactory

/**
 * Http Client.
 * @deprecated Use {@link HttpClient} instead.
 */
@Deprecated
class HttpUtils(httpclient: CloseableHttpClient, hostname: String, apiKey: String = "") {

    def dopost(endpoint: String, post: JsValue, get: Map[String, String] = Map(), timeoutMsec: Integer = 30000): (Int, Array[Byte]) =
        doPutOrPost(endpoint, new StringEntity(post.compactPrint), get, false, timeoutMsec)

    def doput(endpoint: String, put: JsValue, get: Map[String, String] = Map(), timeoutMsec: Integer = 30000): (Int, Array[Byte]) =
        doPutOrPost(endpoint, new StringEntity(put.compactPrint), get, true, timeoutMsec)

    def doget(endpoint: String, get: Map[String, String] = Map(), timeoutMsec: Integer = 10000, useHttps: Boolean = false): (Int, Array[Byte]) = {
        val uri = makeUri(endpoint, get, useHttps)
        val request = new HttpGet(uri)
        execute(request, timeoutMsec, doget(endpoint, get, _))
    }

    def dodelete(endpoint: String, get: Map[String, String] = Map(), timeoutMsec: Integer = 10000): (Int, Array[Byte]) = {
        val uri = makeUri(endpoint, get)
        val request = new HttpDelete(uri)
        if (this.apiKey.length > 0) {
            request.setHeader("Authorization", "Basic " + Base64.getEncoder.encodeToString(this.apiKey.getBytes(StandardCharsets.UTF_8)))
        }
        execute(request, timeoutMsec, dodelete(endpoint, get, _))
    }

    private def doPutOrPost(endpoint: String, form: StringEntity, get: Map[String, String], put: Boolean, timeoutMsec: Integer): (Int, Array[Byte]) = {
        val uri = makeUri(endpoint, get)

        val body = form
        body.setContentType("application/json")

        val request = if (put) new HttpPut(uri) else new HttpPost(uri)

        if (this.apiKey.length > 0) {
            request.setHeader("Authorization", "Basic " + Base64.getEncoder.encodeToString(this.apiKey.getBytes(StandardCharsets.UTF_8)))
        }

        request.setEntity(body)
        execute(request, timeoutMsec, doPutOrPost(endpoint, form, get, put, _))
    }

    private def execute(request: HttpRequestBase, timeoutMsec: Integer, retry: (Integer => (Int, Array[Byte]))): (Int, Array[Byte]) = {
        try {
            val response = httpclient.execute(request)
            try {
                val entity = response.getEntity
                (response.getStatusLine.getStatusCode, EntityUtils.toByteArray(entity))
            } finally {
                response.close
            }
        } catch {
            case t: HttpHostConnectException =>
                if (timeoutMsec > 0) {
                    Thread sleep 100
                    retry(timeoutMsec - 100)
                } else (-1, t.getMessage.getBytes)
            case t: Throwable => (-1, t.getMessage.getBytes)
        }
    }

    private def encode(m: Map[String, String]): List[NameValuePair] =
        m map {
            case (k, v) =>
                new BasicNameValuePair(k, v).asInstanceOf[NameValuePair]
        } toList

    private def makeUri(endpoint: String, get: Map[String, String], useHttps: Boolean = false): URI = {
        val scheme = if (-1 != hostname.indexOf(":443") || useHttps) { "https" } else "http"
        if (get.nonEmpty)
            new URIBuilder()
                .setScheme(scheme)
                .setHost(hostname)
                .setPath(endpoint)
                .setParameters(encode(get).asJava)
                .build
        else
            new URIBuilder()
                .setScheme(scheme)
                .setHost(hostname)
                .setPath(endpoint)
                .build
    }
}

object HttpUtils {
    def makeHttpClient(timeoutMsec: Integer, tlsAcceptUnauthorized: Boolean): CloseableHttpClient = {
        val sslContext = {
            val builder = new SSLContextBuilder()
            if (tlsAcceptUnauthorized) {
                // setup a Trust Strategy that allows all certificates.
                builder.loadTrustMaterial(new TrustStrategy() {
                    override def isTrusted(x509Certificates: Array[X509Certificate], s: String): Boolean = {
                        true
                    }
                })
            }
            builder.build()
        }

        val sslSocketFactory = new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE)
        val socketFactoryRegistry = RegistryBuilder.create[ConnectionSocketFactory]()
            .register("http", PlainConnectionSocketFactory.getSocketFactory)
            .register("https", sslSocketFactory)
            .build()
        val connectionManager = new PoolingHttpClientConnectionManager(socketFactoryRegistry)

        val httpconfig = RequestConfig.custom
            .setConnectTimeout(timeoutMsec)
            .setConnectionRequestTimeout(timeoutMsec)
            .setSocketTimeout(timeoutMsec)
            .build

        HttpClientBuilder
            .create
            .setDefaultRequestConfig(httpconfig)
            .setSslcontext(sslContext)
            .setConnectionManager(connectionManager)
            .build
    }
}
