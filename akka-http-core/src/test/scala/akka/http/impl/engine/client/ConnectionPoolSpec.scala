/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{ ServerSocketChannel, SocketChannel }
import java.util.concurrent.atomic.AtomicInteger

import akka.http.impl.engine.client.PoolMasterActor.PoolInterfaceRunning
import akka.http.impl.settings.ConnectionPoolSettingsImpl
import akka.http.impl.util.SingletonException
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings, ServerSettings }
import akka.http.scaladsl.{ Http, TestUtils }
import akka.stream.ActorMaterializer
import akka.stream.TLSProtocol._
import akka.stream.scaladsl._
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.testkit.AkkaSpec
import akka.util.ByteString

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

class ConnectionPoolSpec extends AkkaSpec("""
    akka.loggers = []
    akka.loglevel = OFF
    akka.io.tcp.windows-connection-abort-workaround-enabled = auto
    akka.io.tcp.trace-logging = off""") {
  implicit val materializer = ActorMaterializer()

  // FIXME: Extract into proper util class to be reusable
  lazy val ConnectionResetByPeerMessage: String = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.socket.bind(new InetSocketAddress("127.0.0.1", 0))
    try {
      val clientSocket = SocketChannel.open(new InetSocketAddress("127.0.0.1", serverSocket.socket().getLocalPort))
      @volatile var serverSideChannel: SocketChannel = null
      awaitCond {
        serverSideChannel = serverSocket.accept()
        serverSideChannel != null
      }
      serverSideChannel.socket.setSoLinger(true, 0)
      serverSideChannel.close()
      clientSocket.read(ByteBuffer.allocate(1))
      null
    } catch {
      case NonFatal(e) ⇒ e.getMessage
    }
  }

  "The host-level client infrastructure" should {

    "properly complete a simple request/response cycle" in new TestSetup {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/") → 42)

      responseOutSub.request(1)
      acceptIncomingConnection()
      val (Success(response), 42) = responseOut.expectNext()
      response.headers should contain(RawHeader("Req-Host", s"$serverHostName:$serverPort"))
    }

    "open a second connection if the first one is loaded" in new TestSetup {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/a") → 42)
      requestIn.sendNext(HttpRequest(uri = "/b") → 43)

      responseOutSub.request(2)
      acceptIncomingConnection()
      val r1 = responseOut.expectNext()
      acceptIncomingConnection()
      val r2 = responseOut.expectNext()

      Seq(r1, r2) foreach {
        case (Success(x), 42) ⇒ requestUri(x) should endWith("/a")
        case (Success(x), 43) ⇒ requestUri(x) should endWith("/b")
        case x                ⇒ fail(x.toString)
      }
      Seq(r1, r2).map(t ⇒ connNr(t._1.get)) should contain allOf (1, 2)
    }

    "open a second connection if the request on the first one is dispatch but not yet completed" in new TestSetup {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int]()

      val responseEntityPub = TestPublisher.probe[ByteString]()

      override def testServerHandler(connNr: Int): HttpRequest ⇒ HttpResponse = {
        case request @ HttpRequest(_, Uri.Path("/a"), _, _, _) ⇒
          val entity = HttpEntity.Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, Source.fromPublisher(responseEntityPub))
          super.testServerHandler(connNr)(request) withEntity entity
        case x ⇒ super.testServerHandler(connNr)(x)
      }

      requestIn.sendNext(HttpRequest(uri = "/a") → 42)
      responseOutSub.request(1)
      acceptIncomingConnection()
      val (Success(r1), 42) = responseOut.expectNext()
      val responseEntityProbe = TestSubscriber.probe[ByteString]()
      r1.entity.dataBytes.runWith(Sink.fromSubscriber(responseEntityProbe))
      responseEntityProbe.expectSubscription().request(2)
      responseEntityPub.sendNext(ByteString("YEAH"))
      responseEntityProbe.expectNext(ByteString("YEAH"))

      requestIn.sendNext(HttpRequest(uri = "/b") → 43)
      responseOutSub.request(1)
      acceptIncomingConnection()
      val (Success(r2), 43) = responseOut.expectNext()
      connNr(r2) shouldEqual 2
    }

    "not open a second connection if there is an idle one available" in new TestSetup {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/a") → 42)
      responseOutSub.request(1)
      acceptIncomingConnection()
      val (Success(response1), 42) = responseOut.expectNext()
      connNr(response1) shouldEqual 1

      requestIn.sendNext(HttpRequest(uri = "/b") → 43)
      responseOutSub.request(1)
      val (Success(response2), 43) = responseOut.expectNext()
      connNr(response2) shouldEqual 1
    }

    "be able to handle 500 pipelined requests against the test server" in new TestSetup {
      val settings = ConnectionPoolSettings(system).withMaxConnections(4).withPipeliningLimit(2)
      val poolFlow = Http().cachedHostConnectionPool[Int](serverHostName, serverPort, settings = settings)

      val N = 500
      val requestIds = Source.fromIterator(() ⇒ Iterator.from(1)).take(N)
      val idSum = requestIds.map(id ⇒ HttpRequest(uri = s"/r$id") → id).via(poolFlow).map {
        case (Success(response), id) ⇒
          requestUri(response) should endWith(s"/r$id")
          id
        case x ⇒ fail(x.toString)
      }.runFold(0)(_ + _)

      acceptIncomingConnection()
      acceptIncomingConnection()
      acceptIncomingConnection()
      acceptIncomingConnection()

      Await.result(idSum, 10.seconds) shouldEqual N * (N + 1) / 2
    }

    "properly surface connection-level errors" in new TestSetup(autoAccept = true) {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int](maxRetries = 0)

      requestIn.sendNext(HttpRequest(uri = "/a") → 42)
      requestIn.sendNext(HttpRequest(uri = "/crash") → 43)
      responseOutSub.request(2)

      override def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString =
        if (bytes.utf8String.contains("/crash")) sys.error("CRASH BOOM BANG") else bytes

      val responses = Seq(responseOut.expectNext(), responseOut.expectNext())

      responses mustContainLike { case (Success(x), 42) ⇒ requestUri(x) should endWith("/a") }
      responses mustContainLike { case (Failure(x), 43) ⇒ x.getMessage should include(ConnectionResetByPeerMessage) }
    }

    "retry failed requests" in new TestSetup(autoAccept = true) {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/a") → 42)
      requestIn.sendNext(HttpRequest(uri = "/crash") → 43)
      responseOutSub.request(2)

      val remainingResponsesToKill = new AtomicInteger(1)
      override def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString =
        if (bytes.utf8String.contains("/crash") && remainingResponsesToKill.decrementAndGet() >= 0)
          sys.error("CRASH BOOM BANG")
        else bytes

      val responses = Seq(responseOut.expectNext(), responseOut.expectNext())

      responses mustContainLike { case (Success(x), 42) ⇒ requestUri(x) should endWith("/a") }
      responses mustContainLike { case (Success(x), 43) ⇒ requestUri(x) should endWith("/crash") }
    }

    "respect the configured `maxRetries` value" in new TestSetup(autoAccept = true) {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int](maxRetries = 4)

      requestIn.sendNext(HttpRequest(uri = "/a") → 42)
      requestIn.sendNext(HttpRequest(uri = "/crash") → 43)
      responseOutSub.request(2)

      val remainingResponsesToKill = new AtomicInteger(5)
      override def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString =
        if (bytes.utf8String.contains("/crash") && remainingResponsesToKill.decrementAndGet() >= 0)
          sys.error("CRASH BOOM BANG")
        else bytes

      val responses = Seq(responseOut.expectNext(), responseOut.expectNext())

      responses mustContainLike { case (Success(x), 42) ⇒ requestUri(x) should endWith("/a") }
      responses mustContainLike { case (Failure(x), 43) ⇒ x.getMessage should include(ConnectionResetByPeerMessage) }
      remainingResponsesToKill.get() shouldEqual 0
    }

    "automatically shutdown after configured timeout periods" in new TestSetup() {
      val (_, _, _, hcp) = cachedHostConnectionPool[Int](idleTimeout = 1.second)
      val gateway = hcp.gateway
      Await.result(gateway.poolStatus(), 1500.millis).get shouldBe a[PoolInterfaceRunning]
      awaitCond({ Await.result(gateway.poolStatus(), 1500.millis).isEmpty }, 2000.millis)
    }

    "transparently restart after idle shutdown" in new TestSetup() {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int](idleTimeout = 1.second)

      val gateway = hcp.gateway
      Await.result(gateway.poolStatus(), 1500.millis).get shouldBe a[PoolInterfaceRunning]
      awaitCond({ Await.result(gateway.poolStatus(), 1500.millis).isEmpty }, 2000.millis)

      requestIn.sendNext(HttpRequest(uri = "/") → 42)

      responseOutSub.request(1)
      acceptIncomingConnection()
      val (Success(_), 42) = responseOut.expectNext()
    }

    "never close hot connections when minConnections key is given and >0 (minConnections = 1)" in new TestSetup() {
      val close: HttpHeader = Connection("close")

      // for lower bound of one connection
      val minConnection = 1
      val (requestIn, requestOut, responseOutSub, hcpMinConnection) =
        cachedHostConnectionPool[Int](idleTimeout = 100.millis, minConnections = minConnection)
      val gatewayConnection = hcpMinConnection.gateway

      acceptIncomingConnection()
      requestIn.sendNext(HttpRequest(uri = "/minimumslots/1", headers = immutable.Seq(close)) → 42)
      responseOutSub.request(1)
      requestOut.expectNextN(1)

      condHolds(500.millis) { () ⇒
        Await.result(gatewayConnection.poolStatus(), 100.millis).get shouldBe a[PoolInterfaceRunning]
      }
    }

    "never close hot connections when minConnections key is given and >0 (minConnections = 5)" in new TestSetup() {
      val close: HttpHeader = Connection("close")

      // for lower bound of five connections
      val minConnections = 5
      val (requestIn, requestOut, responseOutSub, hcpMinConnection) = cachedHostConnectionPool[Int](
        idleTimeout = 100.millis,
        minConnections = minConnections,
        maxConnections = minConnections + 10)

      (0 until minConnections) foreach { _ ⇒ acceptIncomingConnection() }
      (0 until minConnections) foreach { i ⇒
        requestIn.sendNext(HttpRequest(uri = s"/minimumslots/5/$i", headers = immutable.Seq(close)) → 42)
      }
      responseOutSub.request(minConnections)
      requestOut.expectNextN(minConnections)

      val gatewayConnections = hcpMinConnection.gateway
      condHolds(1000.millis) { () ⇒
        val status = gatewayConnections.poolStatus()
        Await.result(status, 100.millis).get shouldBe a[PoolInterfaceRunning]
      }
    }

    "shutdown if idle and min connection has been set to 0" in new TestSetup() {
      val (_, _, _, hcp) = cachedHostConnectionPool[Int](idleTimeout = 1.second, minConnections = 0)
      val gateway = hcp.gateway
      Await.result(gateway.poolStatus(), 1500.millis).get shouldBe a[PoolInterfaceRunning]
      awaitCond({ Await.result(gateway.poolStatus(), 1500.millis).isEmpty }, 2000.millis)
    }

    "be able to handle 500 `Connection: close` requests against the test server" in new TestSetup {
      val settings = ConnectionPoolSettings(system).withMaxConnections(4)
      val poolFlow = Http().cachedHostConnectionPool[Int](serverHostName, serverPort, settings = settings)

      val N = 500
      val requestIds = Source.fromIterator(() ⇒ Iterator.from(1)).take(N)
      val idSum = requestIds.map(id ⇒ HttpRequest(uri = s"/r$id").withHeaders(Connection("close")) → id).via(poolFlow).map {
        case (Success(response), id) ⇒
          requestUri(response) should endWith(s"/r$id")
          id
        case x ⇒ fail(x.toString)
      }.runFold(0)(_ + _)

      (1 to N).foreach(_ ⇒ acceptIncomingConnection())

      Await.result(idSum, 10.seconds) shouldEqual N * (N + 1) / 2
    }

    "be able to handle 500 pipelined requests with connection termination" in new TestSetup(autoAccept = true) {
      def closeHeader(): List[Connection] =
        if (util.Random.nextInt(8) == 0) Connection("close") :: Nil
        else Nil

      override def testServerHandler(connNr: Int): HttpRequest ⇒ HttpResponse = { r ⇒
        val idx = r.uri.path.tail.head.toString
        HttpResponse()
          .withHeaders(RawHeader("Req-Idx", idx) +: responseHeaders(r, connNr))
          .withDefaultHeaders(closeHeader())
      }

      for (pipeliningLimit ← Iterator.from(1).map(math.pow(2, _).toInt).take(4)) {
        val settings = ConnectionPoolSettings(system).withMaxConnections(4).withPipeliningLimit(pipeliningLimit).withMaxOpenRequests(4 * pipeliningLimit)
        val poolFlow = Http().cachedHostConnectionPool[Int](serverHostName, serverPort, settings = settings)

        def method() =
          if (util.Random.nextInt(2) == 0) HttpMethods.POST else HttpMethods.GET

        def request(i: Int) =
          HttpRequest(method = method(), headers = closeHeader(), uri = s"/$i") → i

        try {
          val N = 200
          val (_, idSum) =
            Source.fromIterator(() ⇒ Iterator.from(1)).take(N)
              .map(request)
              .viaMat(poolFlow)(Keep.right)
              .map {
                case (Success(response), id) ⇒
                  requestUri(response) should endWith(s"/$id")
                  id
                case x ⇒ fail(x.toString)
              }.toMat(Sink.fold(0)(_ + _))(Keep.both).run()

          Await.result(idSum, 30.seconds) shouldEqual N * (N + 1) / 2
        } catch {
          case thr: Throwable ⇒
            throw new RuntimeException(s"Failed at pipeliningLimit=$pipeliningLimit, poolFlow=$poolFlow", thr)
        }
      }
    }
  }

  "The single-request client infrastructure" should {
    class LocalTestSetup extends TestSetup(ServerSettings(system).withRawRequestUriHeader(true), autoAccept = true)

    "transform absolute request URIs into relative URIs plus host header" in new LocalTestSetup {
      val request = HttpRequest(uri = s"http://$serverHostName:$serverPort/abc?query#fragment")
      val responseFuture = Http().singleRequest(request)
      val responseHeaders = Await.result(responseFuture, 1.second).headers
      responseHeaders should contain(RawHeader("Req-Raw-Request-URI", "/abc?query"))
      responseHeaders should contain(RawHeader("Req-Host", s"$serverHostName:$serverPort"))
    }

    "support absolute request URIs without path component" in new LocalTestSetup {
      val request = HttpRequest(uri = s"http://$serverHostName:$serverPort")
      val responseFuture = Http().singleRequest(request)
      val responseHeaders = Await.result(responseFuture, 1.second).headers
      responseHeaders should contain(RawHeader("Req-Raw-Request-URI", "/"))
    }

    "support absolute request URIs with a double slash path component" in new LocalTestSetup {
      val request = HttpRequest(uri = s"http://$serverHostName:$serverPort//foo")
      val responseFuture = Http().singleRequest(request)
      val responseHeaders = Await.result(responseFuture, 1.second).headers
      responseHeaders should contain(RawHeader("Req-Uri", s"http://$serverHostName:$serverPort//foo"))
      responseHeaders should contain(RawHeader("Req-Raw-Request-URI", "//foo"))
    }

    "produce an error if the request does not have an absolute URI" in {
      val request = HttpRequest(uri = "/foo")
      val responseFuture = Http().singleRequest(request)
      val thrown = the[IllegalUriException] thrownBy Await.result(responseFuture, 1.second)
      thrown should have message "Cannot determine request scheme and target endpoint as HttpMethod(GET) request to /foo doesn't have an absolute URI"
    }
  }

  "The superPool client infrastructure" should {

    "route incoming requests to the right cached host connection pool" in new TestSetup(autoAccept = true) {
      val (serverEndpoint2, serverHostName2, serverPort2) = TestUtils.temporaryServerHostnameAndPort()
      Http().bindAndHandleSync(testServerHandler(0), serverHostName2, serverPort2)

      val (requestIn, responseOut, responseOutSub, hcp) = superPool[Int]()

      requestIn.sendNext(HttpRequest(uri = s"http://$serverHostName:$serverPort/a") → 42)
      requestIn.sendNext(HttpRequest(uri = s"http://$serverHostName2:$serverPort2/b") → 43)

      responseOutSub.request(2)
      Seq(responseOut.expectNext(), responseOut.expectNext()) foreach {
        case (Success(x), 42) ⇒ requestUri(x) shouldEqual s"http://$serverHostName:$serverPort/a"
        case (Success(x), 43) ⇒ requestUri(x) shouldEqual s"http://$serverHostName2:$serverPort2/b"
        case x                ⇒ fail(x.toString)
      }
    }
  }

  class TestSetup(
    serverSettings: ServerSettings = ServerSettings(system),
    autoAccept:     Boolean        = false) {
    val (serverEndpoint, serverHostName, serverPort) = TestUtils.temporaryServerHostnameAndPort()

    def testServerHandler(connNr: Int): HttpRequest ⇒ HttpResponse = {
      case r: HttpRequest ⇒ HttpResponse(headers = responseHeaders(r, connNr), entity = r.entity)
    }

    def responseHeaders(r: HttpRequest, connNr: Int) =
      ConnNrHeader(connNr) +: RawHeader("Req-Uri", r.uri.toString) +: r.headers.map(h ⇒ RawHeader("Req-" + h.name, h.value))

    def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString = bytes

    val incomingConnectionCounter = new AtomicInteger
    val incomingConnections = TestSubscriber.manualProbe[Http.IncomingConnection]
    val incomingConnectionsSub = {
      val rawBytesInjection = BidiFlow.fromFlows(
        Flow[SslTlsOutbound].collect[ByteString] { case SendBytes(x) ⇒ mapServerSideOutboundRawBytes(x) }
          .recover({ case NoErrorComplete ⇒ ByteString.empty }),
        Flow[ByteString].map(SessionBytes(null, _)))
      val sink = if (autoAccept) Sink.foreach[Http.IncomingConnection](handleConnection) else Sink.fromSubscriber(incomingConnections)
      Tcp().bind(serverEndpoint.getHostString, serverEndpoint.getPort, idleTimeout = serverSettings.timeouts.idleTimeout)
        .map { c ⇒
          val layer = Http().serverLayer(serverSettings, log = log)
          Http.IncomingConnection(c.localAddress, c.remoteAddress, layer atop rawBytesInjection join c.flow)
        }.runWith(sink)
      if (autoAccept) null else incomingConnections.expectSubscription()
    }

    def acceptIncomingConnection(): Unit = {
      incomingConnectionsSub.request(1)
      val conn = incomingConnections.expectNext()
      handleConnection(conn)
    }

    private def handleConnection(c: Http.IncomingConnection) =
      c.handleWithSyncHandler(testServerHandler(incomingConnectionCounter.incrementAndGet()))

    def cachedHostConnectionPool[T](
      maxConnections:  Int                      = 2,
      minConnections:  Int                      = 0,
      maxRetries:      Int                      = 2,
      maxOpenRequests: Int                      = 8,
      pipeliningLimit: Int                      = 1,
      idleTimeout:     Duration                 = 5.seconds,
      ccSettings:      ClientConnectionSettings = ClientConnectionSettings(system)) = {

      val settings =
        new ConnectionPoolSettingsImpl(maxConnections, minConnections,
          maxRetries, maxOpenRequests, pipeliningLimit,
          idleTimeout, ccSettings)
      flowTestBench(
        Http().cachedHostConnectionPool[T](serverHostName, serverPort, settings))
    }

    def superPool[T](
      maxConnections:  Int                      = 2,
      minConnections:  Int                      = 0,
      maxRetries:      Int                      = 2,
      maxOpenRequests: Int                      = 8,
      pipeliningLimit: Int                      = 1,
      idleTimeout:     Duration                 = 5.seconds,
      ccSettings:      ClientConnectionSettings = ClientConnectionSettings(system)) = {
      val settings = new ConnectionPoolSettingsImpl(maxConnections, minConnections, maxRetries, maxOpenRequests, pipeliningLimit,
        idleTimeout, ClientConnectionSettings(system))
      flowTestBench(Http().superPool[T](settings = settings))
    }

    def flowTestBench[T, Mat](poolFlow: Flow[(HttpRequest, T), (Try[HttpResponse], T), Mat]) = {
      val requestIn = TestPublisher.probe[(HttpRequest, T)]()
      val responseOut = TestSubscriber.manualProbe[(Try[HttpResponse], T)]
      val hcp = Source.fromPublisher(requestIn).viaMat(poolFlow)(Keep.right).to(Sink.fromSubscriber(responseOut)).run()
      val responseOutSub = responseOut.expectSubscription()
      (requestIn, responseOut, responseOutSub, hcp)
    }

    def connNr(r: HttpResponse): Int = r.headers.find(_ is "conn-nr").get.value.toInt
    def requestUri(r: HttpResponse): String = r.headers.find(_ is "req-uri").get.value

    /**
     * Makes sure the given condition "f" holds in the timer period of "in".
     * The given condition function should throw if not met.
     * Note: Execution of "condHolds" will take at least "in" time, so for big "in" it might drain the ime budget for tests.
     */
    def condHolds[T](in: FiniteDuration)(f: () ⇒ T): T = {
      val end = System.nanoTime.nanos + in

      var lastR = f()
      while (System.nanoTime.nanos < end) {
        lastR = f()
        Thread.sleep(50)
      }
      lastR
    }
  }

  case class ConnNrHeader(nr: Int) extends CustomHeader {
    def renderInRequests = false
    def renderInResponses = true
    def name = "Conn-Nr"
    def value = nr.toString
  }

  implicit class MustContain[T](specimen: Seq[T]) {
    def mustContainLike(pf: PartialFunction[T, Unit]): Unit =
      specimen.collectFirst(pf) getOrElse fail("did not contain")
  }

  object NoErrorComplete extends SingletonException
}
