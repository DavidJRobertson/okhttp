/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import java.io.IOException
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Make continuous calls to MockWebServer and print the ongoing QPS and failure count.
 *
 * This can be used with tools like Envoy.
 */
class OkHttpStressTest(
  private val maxConcurrentRequests: Int = 100,
  private val logsPerSecond: Int = 10,
  private val protocols: List<Protocol> = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1),
  private val overridePort: Int? = null
) : Dispatcher(), Callback {
  private lateinit var heldCertificate: HeldCertificate
  private lateinit var server: MockWebServer
  private lateinit var client: OkHttpClient
  private val latestStats = AtomicReference<Stats>()

  private fun addStats(stats: Stats) {
    while (true) {
      val before = latestStats.get()
      val after = (before?.plus(stats) ?: stats).copy(nanotime = System.nanoTime())
      if (latestStats.compareAndSet(before, after)) return
    }
  }

  private fun prepareHeldCertificate() {
    val certificate = """
        |-----BEGIN CERTIFICATE-----
        |MIIBbzCCARSgAwIBAgIBATAKBggqhkjOPQQDAjAvMS0wKwYDVQQDEyRlNjUwMzY2
        |Yi1kYzk3LTQxZjMtYTg5Ni1mZjMzOTU1ZWExNjgwHhcNMTkwOTA0MjAxMTQ1WhcN
        |MTkwOTA1MjAxMTQ1WjAvMS0wKwYDVQQDEyRlNjUwMzY2Yi1kYzk3LTQxZjMtYTg5
        |Ni1mZjMzOTU1ZWExNjgwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATW9EKXvV6G
        |+97SPJtipU+8r6Fgwtj8djKoHtf5AGA0ajNgV5IbtASYeOeMcpvrpix4hEh6Rqzz
        |OnowkT2EHGyJoyEwHzAdBgNVHREBAf8EEzARgg9qZXNzZXRlc3QubG9jYWwwCgYI
        |KoZIzj0EAwIDSQAwRgIhAOpPmREAn+dcqPKHR8X2gNa1xrlvHz2AVqe6oaS+RU0s
        |AiEAgUzxGK83LLrUxxO2V5GyZtVBUVWswoalEqC9h56lnPI=
        |-----END CERTIFICATE-----
        |""".trimMargin()

    val privateKey = """
        |-----BEGIN PRIVATE KEY-----
        |MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCAVrOCnn68DKtYVYxFc
        |VfgZIWj/Q0PcBbiC5jRw8E4qoQ==
        |-----END PRIVATE KEY-----
        |""".trimMargin()

    if (true) {
      heldCertificate = HeldCertificate.decode(certificate, privateKey)
    } else {
      // TODO(jwilson): make this easier.
      heldCertificate = HeldCertificate.Builder()
          .addSubjectAlternativeName("jessetest.local")
          .build()
    }
  }

  private fun prepareServer() {
    val logger = Logger.getLogger(MockWebServer::class.java.name)
    logger.level = Level.WARNING

    val handshakeCertificates = HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .build()

    server = MockWebServer()
    server.dispatcher = this
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
    server.protocols = protocols
    server.start(4434)
  }

  private fun prepareClient() {
    val handshakeCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(heldCertificate.certificate)
        .build()

    client = OkHttpClient.Builder()
        .protocols(protocols)
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(),
            handshakeCertificates.trustManager
        )
        .dispatcher(okhttp3.Dispatcher().apply {
          maxRequests = maxConcurrentRequests
          maxRequestsPerHost = maxConcurrentRequests
        })
        .dns(object : Dns {
          override fun lookup(hostname: String): List<InetAddress> {
            return when (hostname) {
              "jessetest.local" -> listOf(InetAddress.getByName("localhost"))
              else -> Dns.SYSTEM.lookup(hostname)
            }
          }
        })
        .build()
  }

  override fun dispatch(request: RecordedRequest): MockResponse {
    val requestBodySize = request.body.size
    request.body.clear()

    val responseBodyBuffer = Buffer()
        .writeUtf8("response body")
    val responseBodySize = responseBodyBuffer.size

    val response = MockResponse()
    response.setBody(responseBodyBuffer)

    addStats(Stats(
        serverResponses = 1,
        serverResponseBytes = responseBodySize,
        serverRequestBytes = requestBodySize
    ))

    return response
  }

  private fun enqueueCall() {
    val requestBody = "request body".toRequestBody()
    val httpUrl = "https://jessetest.local/foo".toHttpUrl().newBuilder()
        .port(overridePort ?: server.port)
        .build()
    val call = client.newCall(Request.Builder()
        .url(httpUrl)
        .method("POST", requestBody)
        .build())

    addStats(Stats(
        clientRequests = 1,
        clientRequestBytes = requestBody.contentLength()
    ))

    call.enqueue(this)
  }

  override fun onFailure(call: Call, e: IOException) {
    addStats(Stats(
        clientFailures = 1
    ))

    enqueueCall()
  }

  override fun onResponse(call: Call, response: Response) {
    try {
      response.use {
        val buffer = Buffer()
        response.body!!.source().readAll(buffer)
        val responseBodySize = buffer.size
        buffer.clear()

        if (response.isSuccessful) {

        }
        addStats(Stats(
            clientResponses = (if (response.isSuccessful) 1 else 0),
            clientFailures = (if (response.isSuccessful) 0 else 1),
            clientResponseBytes = responseBodySize,
            clientHttp1Responses = (if (response.protocol == Protocol.HTTP_1_1) 1 else 0),
            clientHttp2Responses = (if (response.protocol == Protocol.HTTP_2) 1 else 0)
        ))
      }
    } catch (e: IOException) {
      addStats(Stats(
          clientFailures = 1
      ))
    }

    enqueueCall()
  }

  data class Stats(
    val nanotime: Long = 0L,
    val clientRequests: Long = 0L,
    val clientFailures: Long = 0L,
    val clientResponses: Long = 0L,
    val clientRequestBytes: Long = 0L,
    val clientResponseBytes: Long = 0L,
    val clientHttp1Responses: Long = 0L,
    val clientHttp2Responses: Long = 0L,
    val serverResponses: Long = 0L,
    val serverRequestBytes: Long = 0L,
    val serverResponseBytes: Long = 0L
  ) {
    fun plus(other: Stats): Stats {
      return Stats(
          clientRequests = clientRequests + other.clientRequests,
          clientFailures = clientFailures + other.clientFailures,
          clientResponses = clientResponses + other.clientResponses,
          clientRequestBytes = clientRequestBytes + other.clientRequestBytes,
          clientResponseBytes = clientResponseBytes + other.clientResponseBytes,
          clientHttp1Responses = clientHttp1Responses + other.clientHttp1Responses,
          clientHttp2Responses = clientHttp2Responses + other.clientHttp2Responses,
          serverResponses = serverResponses + other.serverResponses,
          serverRequestBytes = serverRequestBytes + other.serverRequestBytes,
          serverResponseBytes = serverResponseBytes + other.serverResponseBytes
      )
    }
  }

  fun run() {
    addStats(Stats())

    prepareHeldCertificate()
    prepareServer()
    prepareClient()

    for (i in 0 until maxConcurrentRequests) {
      enqueueCall()
    }

    val rollingWindow = ArrayDeque<Stats>()
    rollingWindow += latestStats.get()

    while (true) {
      val previous = rollingWindow.first
      val stats = latestStats.get()
      printStatsLine(stats, previous)
      rollingWindow.addLast(stats)

      // Trim the window to 10 seconds
      while (TimeUnit.NANOSECONDS.toSeconds(stats.nanotime - rollingWindow.first.nanotime) > 10) {
        rollingWindow.removeFirst()
      }

      Thread.sleep((1000.0 / logsPerSecond).toLong())
    }
  }

  private fun printStatsLine(current: Stats, previous: Stats) {
    val elapsedSeconds = (current.nanotime - previous.nanotime) / 1e9
    val qps = (current.clientResponses - previous.clientResponses) / elapsedSeconds
    println("${"%.2f".format(qps)} qps $current")
  }
}

fun main() {
  OkHttpStressTest(
      maxConcurrentRequests = 50,
      logsPerSecond = 3,
//      protocols = listOf(Protocol.HTTP_1_1),
      overridePort = 10000
  ).run()
}
