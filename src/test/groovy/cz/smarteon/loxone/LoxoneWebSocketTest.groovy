package cz.smarteon.loxone

import cz.smarteon.loxone.MockWebSocketServer.MockWebSocketServerListener
import cz.smarteon.loxone.message.ApiInfo
import cz.smarteon.loxone.message.LoxoneMessage
import cz.smarteon.loxone.message.PubKeyInfo
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Timeout
import spock.lang.Unroll

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static cz.smarteon.loxone.MockWebSocketServer.PASS
import static cz.smarteon.loxone.MockWebSocketServer.PUBLIC_KEY
import static cz.smarteon.loxone.MockWebSocketServer.USER
import static cz.smarteon.loxone.MockWebSocketServer.VISU_PASS
import static cz.smarteon.loxone.Protocol.C_JSON_API
import static cz.smarteon.loxone.Protocol.C_JSON_PUBLIC_KEY
import static org.hamcrest.CoreMatchers.equalTo

class LoxoneWebSocketTest extends Specification {

    MockWebSocketServer server
    LoxoneAuth authMock
    @Subject LoxoneWebSocket lws
    MockWebSocketServerListener listener = new MockWebSocketServerListener() {

        @Override
        void started() {
            listenerLatch.countDown()
        }

        @Override
        void stopped() {
            listenerLatch.countDown()
        }
    }
    CountDownLatch listenerLatch

    void setup() {
        server = new MockWebSocketServer(listener)
        startServer()
        authMock = Stub(LoxoneAuth)
        def http = Stub(LoxoneHttp) {
            get(C_JSON_API) >> new LoxoneMessage(C_JSON_API, 200, new ApiInfo('50:4F:94:10:B8:4A', '9.1.10.30'))
            get(C_JSON_PUBLIC_KEY) >> new LoxoneMessage(C_JSON_PUBLIC_KEY, 200, new PubKeyInfo(PUBLIC_KEY))
        }
        lws = new LoxoneWebSocket("localhost:${server.port}", new LoxoneAuth(http, USER, PASS, VISU_PASS))
    }

    void cleanup() {
        stopServer()
        lws.close()
    }

    void startServer() {
        listenerLatch = new CountDownLatch(1)
        server.start()
        listenerLatch.await(500, TimeUnit.MILLISECONDS)
    }

    void stopServer() {
        listenerLatch = new CountDownLatch(1)
        server.stop()
        listenerLatch.await(500, TimeUnit.MILLISECONDS)
    }

    @Unroll
    def "should send #type command"() {
        given:
        server.expect(equalTo('testCmd'))

        when:
        lws."$method"('testCmd')

        then:
        server.verifyExpectations(100)

        where:
        type     | method
        'simple' | 'sendCommand'
        'secure' | 'sendSecureCommand'
    }

    @Timeout(2)
    def "should handle bad credentials"() {
        given:
        lws.retries = 0
        server.badCredentials = 1

        when:
        lws.sendCommand('baf')

        then:
        thrown(LoxoneException)
    }

    def "should handle server restart"() {
        when:
        server.expect(equalTo('beforeRestart'))
        lws.sendCommand('beforeRestart')

        then:
        server.verifyExpectations(100)

        when:
        stopServer()
        server = new MockWebSocketServer(server)
        server.expect(equalTo('afterRestart'))
        startServer()
        lws.sendCommand('afterRestart')

        then:
        server.verifyExpectations(100)
    }

    @Timeout(10)
    def "should retry on bad credentials"() {
        given:
        server.expect(equalTo('baf'))
        lws.retries = 5
        server.badCredentials = 4

        when:
        lws.sendCommand('baf')

        then:
        server.verifyExpectations(100)
    }
}