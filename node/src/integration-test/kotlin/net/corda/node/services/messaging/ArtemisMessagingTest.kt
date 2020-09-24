package net.corda.node.services.messaging

import com.codahale.metrics.MetricRegistry
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.whenever
import net.corda.core.crypto.generateKeyPair
import net.corda.core.internal.div
import net.corda.core.node.NetworkParameters
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.seconds
import net.corda.node.services.config.FlowTimeoutConfiguration
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.config.configureWithDevSSLCertificate
import net.corda.node.services.network.PersistentNetworkMapCache
import net.corda.node.services.transactions.PersistentUniquenessProvider
import net.corda.node.utilities.AffinityExecutor.ServiceAffinityExecutor
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.MAX_MESSAGE_SIZE
import net.corda.testing.core.SerializationEnvironmentRule
import net.corda.testing.driver.internal.incrementalPortAllocation
import net.corda.testing.internal.LogHelper
import net.corda.testing.internal.TestingNamedCacheFactory
import net.corda.testing.internal.configureDatabase
import net.corda.coretesting.internal.rigorousMock
import net.corda.coretesting.internal.stubs.CertificateStoreStubs
import net.corda.node.services.statemachine.MessageType
import net.corda.node.services.statemachine.SessionId
import net.corda.testing.common.internal.eventually
import net.corda.testing.node.MockServices.Companion.makeTestDataSourceProperties
import net.corda.testing.node.internal.MOCK_VERSION_INFO
import org.apache.activemq.artemis.api.core.ActiveMQConnectionTimedOutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rx.subjects.PublishSubject
import java.math.BigInteger
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtemisMessagingTest {
    companion object {
        const val TOPIC = "platform.self"
        private val MESSAGE_IDENTIFIER = MessageIdentifier(MessageType.DATA_MESSAGE, "XXXXXXXX", SessionId(BigInteger.valueOf(14)), 0, Clock.systemUTC().instant())
        private val EVENT_HORIZON = Duration.ofDays(5)
    }

    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    // THe
    private val portAllocation = incrementalPortAllocation()
    private val serverPort = portAllocation.nextPort()
    private val identity = generateKeyPair()

    private lateinit var config: NodeConfiguration
    private lateinit var database: CordaPersistence
    private var messagingClient: P2PMessagingClient? = null
    private var messagingServer: ArtemisMessagingServer? = null

    private lateinit var networkMapCache: PersistentNetworkMapCache

    @Before
    fun setUp() {
        abstract class AbstractNodeConfiguration : NodeConfiguration

        val baseDirectory = temporaryFolder.root.toPath()
        val certificatesDirectory = baseDirectory / "certificates"
        val signingCertificateStore = CertificateStoreStubs.Signing.withCertificatesDirectory(certificatesDirectory)
        val p2pSslConfiguration = CertificateStoreStubs.P2P.withCertificatesDirectory(certificatesDirectory)

        config = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(temporaryFolder.root.toPath()).whenever(it).baseDirectory
            doReturn(ALICE_NAME).whenever(it).myLegalName
            doReturn(certificatesDirectory).whenever(it).certificatesDirectory
            doReturn(signingCertificateStore).whenever(it).signingCertificateStore
            doReturn(p2pSslConfiguration).whenever(it).p2pSslOptions
            doReturn(NetworkHostAndPort("0.0.0.0", serverPort)).whenever(it).p2pAddress
            doReturn(null).whenever(it).jmxMonitoringHttpPort
            doReturn(FlowTimeoutConfiguration(5.seconds, 3, backoffBase = 1.0)).whenever(it).flowTimeout
            doReturn(true).whenever(it).crlCheckSoftFail
            doReturn(true).whenever(it).crlCheckArtemisServer
        }
        LogHelper.setLevel(PersistentUniquenessProvider::class)
        database = configureDatabase(makeTestDataSourceProperties(), DatabaseConfig(), { null }, { null })
        networkMapCache = PersistentNetworkMapCache(TestingNamedCacheFactory(), database, rigorousMock()).apply { start(emptyList()) }
    }

    @After
    fun cleanUp() {
        messagingClient?.stop()
        messagingServer?.stop()
        database.close()
        LogHelper.reset(PersistentUniquenessProvider::class)
    }

    @Test(timeout=300_000)
	fun `server starting with the port already bound should throw`() {
        ServerSocket(serverPort).use {
            val messagingServer = createMessagingServer()
            assertThatThrownBy { messagingServer.start() }
        }
    }

    @Test(timeout=300_000)
	fun `client should connect to remote server`() {
        val remoteServerAddress = portAllocation.nextHostAndPort()

        createMessagingServer(remoteServerAddress.port).start()
        createMessagingClient(server = remoteServerAddress)
        startNodeMessagingClient()
    }

    @Test(timeout=300_000)
	fun `client should throw if remote server not found`() {
        val serverAddress = portAllocation.nextHostAndPort()
        val invalidServerAddress = portAllocation.nextHostAndPort()

        createMessagingServer(serverAddress.port).start()

        messagingClient = createMessagingClient(server = invalidServerAddress)
        assertThatThrownBy { startNodeMessagingClient() }
        messagingClient = null
    }

    @Test(timeout=300_000)
	fun `client should connect to local server`() {
        createMessagingServer().start()
        createMessagingClient()
        startNodeMessagingClient()
    }

    @Test(timeout=300_000)
	fun `client should be able to send message to itself`() {
        val (messagingClient, receivedMessages) = createAndStartClientAndServer()
        val message = messagingClient.createMessage(TOPIC, "first msg".toByteArray(), SenderDeduplicationInfo(MESSAGE_IDENTIFIER, null), emptyMap())
        messagingClient.send(message, messagingClient.myAddress)

        val actual: Message = receivedMessages.take()
        assertEquals("first msg", String(actual.data.bytes))
        assertNull(receivedMessages.poll(200, MILLISECONDS))
    }

    @Test(timeout=300_000)
	fun `client should fail if message exceed maxMessageSize limit`() {
        val (messagingClient, receivedMessages) = createAndStartClientAndServer()
        val message = messagingClient.createMessage(TOPIC, ByteArray(MAX_MESSAGE_SIZE), SenderDeduplicationInfo(MESSAGE_IDENTIFIER, null), emptyMap())
        messagingClient.send(message, messagingClient.myAddress)

        val actual: Message = receivedMessages.take()
        assertTrue(ByteArray(MAX_MESSAGE_SIZE).contentEquals(actual.data.bytes))
        assertNull(receivedMessages.poll(200, MILLISECONDS))

        val tooLagerMessage = messagingClient.createMessage(TOPIC, ByteArray(MAX_MESSAGE_SIZE + 1), SenderDeduplicationInfo(MESSAGE_IDENTIFIER, null), emptyMap())
        assertThatThrownBy {
            messagingClient.send(tooLagerMessage, messagingClient.myAddress)
        }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Message exceeds maxMessageSize network parameter")

        assertNull(receivedMessages.poll(200, MILLISECONDS))
    }

    @Test(timeout=300_000)
	fun `server should not process if incoming message exceed maxMessageSize limit`() {
        val (messagingClient, receivedMessages) = createAndStartClientAndServer(clientMaxMessageSize = 100_000, serverMaxMessageSize = 50_000)
        val message = messagingClient.createMessage(TOPIC, ByteArray(50_000), SenderDeduplicationInfo(MESSAGE_IDENTIFIER, null), emptyMap())
        messagingClient.send(message, messagingClient.myAddress)

        val actual: Message = receivedMessages.take()
        assertTrue(ByteArray(50_000).contentEquals(actual.data.bytes))
        assertNull(receivedMessages.poll(200, MILLISECONDS))

        val tooLagerMessage = messagingClient.createMessage(TOPIC, ByteArray(100_000), SenderDeduplicationInfo(MESSAGE_IDENTIFIER, null), emptyMap())
        assertThatThrownBy {
            messagingClient.send(tooLagerMessage, messagingClient.myAddress)
        }.isInstanceOf(ActiveMQConnectionTimedOutException::class.java)
        assertNull(receivedMessages.poll(200, MILLISECONDS))
    }

    @Test(timeout=300_000)
    fun `server should reject messages older than the event horizon`() {
        val (messagingClient, receivedMessages) = createAndStartClientAndServer(clientMaxMessageSize = 100_000, serverMaxMessageSize = 50_000)
        val regularMessage = messagingClient.createMessage(TOPIC, ByteArray(50_000), SenderDeduplicationInfo(MESSAGE_IDENTIFIER.copy(timestamp = Instant.now()), null), emptyMap())
        val tooOldMessage = messagingClient.createMessage(TOPIC, ByteArray(50_000), SenderDeduplicationInfo(MESSAGE_IDENTIFIER.copy(timestamp = Instant.now().minus(EVENT_HORIZON)), null), emptyMap())

        listOf(tooOldMessage, regularMessage).forEach { messagingClient.send(it, messagingClient.myAddress) }

        val regularMsgReceived = receivedMessages.take()
        assertThat(regularMsgReceived.uniqueMessageId).isEqualTo(regularMessage.uniqueMessageId)

        eventually {
            assertThat(messagingServer!!.totalMessagesAcknowledged()).isEqualTo(2)
        }
        assertThat(receivedMessages).isEmpty()
    }

    @Test(timeout=300_000)
	fun `platform version is included in the message`() {
        val (messagingClient, receivedMessages) = createAndStartClientAndServer(platformVersion = 3)
        val message = messagingClient.createMessage(TOPIC, "first msg".toByteArray(), SenderDeduplicationInfo(MESSAGE_IDENTIFIER, null), emptyMap())
        messagingClient.send(message, messagingClient.myAddress)

        val received = receivedMessages.take()
        assertThat(received.platformVersion).isEqualTo(3)
    }

    private fun startNodeMessagingClient(maxMessageSize: Int = MAX_MESSAGE_SIZE) {
        val networkParams = NetworkParameters(3, emptyList(), maxMessageSize, 1_000, Instant.now(), 5, emptyMap(), EVENT_HORIZON)
        messagingClient!!.start(identity.public, null, networkParams)
    }

    private fun createAndStartClientAndServer(platformVersion: Int = 1, serverMaxMessageSize: Int = MAX_MESSAGE_SIZE, clientMaxMessageSize: Int = MAX_MESSAGE_SIZE): Pair<P2PMessagingClient, BlockingQueue<ReceivedMessage>> {
        val receivedMessages = LinkedBlockingQueue<ReceivedMessage>()

        createMessagingServer(maxMessageSize = serverMaxMessageSize).start()

        val messagingClient = createMessagingClient(platformVersion = platformVersion)
        messagingClient.addMessageHandler(TOPIC) { message, _, handle ->
            database.transaction { handle.insideDatabaseTransaction() }
            handle.afterDatabaseTransaction() // We ACK first so that if it fails we won't get a duplicate in [receivedMessages]
            receivedMessages.add(message)
        }
        startNodeMessagingClient(maxMessageSize = clientMaxMessageSize)

        // Run after the handlers are added, otherwise (some of) the messages get delivered and discarded / dead-lettered.
        thread(isDaemon = true) { messagingClient.start() }

        return Pair(messagingClient, receivedMessages)
    }

    private fun createMessagingClient(server: NetworkHostAndPort = NetworkHostAndPort("localhost", serverPort), platformVersion: Int = 1): P2PMessagingClient {
        return database.transaction {
            P2PMessagingClient(
                    config,
                    MOCK_VERSION_INFO.copy(platformVersion = platformVersion),
                    server,
                    ServiceAffinityExecutor("ArtemisMessagingTests", 1),
                    database,
                    networkMapCache,
                    MetricRegistry(),
                    TestingNamedCacheFactory(),
                    isDrainingModeOn = { false },
                    drainingModeWasChangedEvents = PublishSubject.create<Pair<Boolean, Boolean>>()).apply {
                config.configureWithDevSSLCertificate()
                messagingClient = this
            }
        }
    }

    private fun createMessagingServer(local: Int = serverPort, maxMessageSize: Int = MAX_MESSAGE_SIZE): ArtemisMessagingServer {
        return ArtemisMessagingServer(config, NetworkHostAndPort("0.0.0.0", local), maxMessageSize, null).apply {
            config.configureWithDevSSLCertificate()
            messagingServer = this
        }
    }
}
