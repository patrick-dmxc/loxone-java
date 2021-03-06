package cz.smarteon.loxone;

import cz.smarteon.loxone.message.ControlCommand;
import cz.smarteon.loxone.message.LoxoneMessage;
import cz.smarteon.loxone.message.MessageHeader;
import cz.smarteon.loxone.message.TextEvent;
import cz.smarteon.loxone.message.ValueEvent;
import org.java_websocket.client.WebSocketClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;

import static cz.smarteon.loxone.Command.KEEP_ALIVE;
import static java.util.Objects.requireNonNull;

public class LoxoneWebSocket {

    private static final Logger log = LoggerFactory.getLogger(LoxoneWebSocket.class);

    private static final int HTTP_OK = 200;
    private static final int HTTP_AUTH_FAIL = 401;
    private static final int HTTP_NOT_AUTHENTICATED = 400;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_AUTH_TOO_LONG = 420;
    private static final int HTTP_UNAUTHORIZED = 500;

    private static final String C_SYS_ENC = "dev/sys/enc";

    private final BiFunction<LoxoneWebSocket, URI, WebSocketClient> webSocketClientProvider;
    private WebSocketClient webSocketClient;
    private final LoxoneEndpoint endpoint;
    private final LoxoneAuth loxoneAuth;

    private Set<LoxoneWebSocketListener> webSocketListeners;
    private final List<CommandResponseListener> commandResponseListeners;
    private final List<LoxoneEventListener> eventListeners;
    private final Queue<Command<?>> commands;

    private ReentrantReadWriteLock connectRwLock = new ReentrantReadWriteLock();
    private CountDownLatch authSeqLatch;
    private CountDownLatch visuLatch;

    private int authTimeoutSeconds = 3;
    private int visuTimeoutSeconds = 3;
    private int retries = 5;

    private boolean autoRestart = false;
    final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture autoRestartFuture;

    public LoxoneWebSocket(final @NotNull LoxoneEndpoint endpoint, final @NotNull LoxoneAuth loxoneAuth) {
        this(endpoint, loxoneAuth, LoxoneWebsocketClient::new);
    }

    // This class is tightly coupled with LoxoneWebsocketClient, however for tests we need to provide different instance
    @TestOnly
    LoxoneWebSocket(final @NotNull  LoxoneEndpoint endpoint, final @NotNull LoxoneAuth loxoneAuth,
                    final @NotNull BiFunction<LoxoneWebSocket, URI, WebSocketClient> webSocketClientProvider) {
        this.endpoint = requireNonNull(endpoint, "loxone endpoint shouldn't be null");
        this.loxoneAuth = requireNonNull(loxoneAuth, "loxoneAuth shouldn't be null");
        this.webSocketClientProvider = requireNonNull(webSocketClientProvider, "webSocketClientProvider shouldn't be null");

        this.webSocketListeners = new HashSet<>();
        this.commandResponseListeners = new LinkedList<>();
        this.eventListeners = new LinkedList<>();
        this.commands = new ConcurrentLinkedQueue<>();

        // link loxoneAuth as command listener
        registerListener(loxoneAuth);

        // register auth guard as auth listener
        loxoneAuth.registerAuthListener(new LoxoneAuthListener());

        // allow auth to send commands
        loxoneAuth.setCommandSender(this::sendInternal);

        // set this class scheduler for auth scheduling as well
        loxoneAuth.setAutoRefreshScheduler(scheduler);
    }

    public void registerListener(@NotNull final CommandResponseListener listener) {
        commandResponseListeners.add(listener);
    }

    public void registerListener(@NotNull final LoxoneEventListener listener) {
        eventListeners.add(listener);
    }

    public void sendCommand(@NotNull final Command command) {
        requireNonNull(command, "command can't be null");
        if (command.isWsSupported()) {
            sendWithRetry(command, retries);
        } else {
            throw new IllegalArgumentException("Only websocket commands are supported");
        }
    }

    public void sendSecureCommand(@NotNull final ControlCommand command) {
        sendSecureWithRetry(command, retries);
    }

    public void close() {
        scheduler.shutdownNow();
        closeWebSocket();
    }

    @NotNull
    public LoxoneAuth getLoxoneAuth() {
        return loxoneAuth;
    }

    /**
     * Set the seconds, it waits for successful authentication, until give up.
     *
     * @param authTimeoutSeconds authentication timeout in seconds
     */
    public void setAuthTimeoutSeconds(final int authTimeoutSeconds) {
        this.authTimeoutSeconds = authTimeoutSeconds;
    }

    /**
     * Get seconds, it waits for successful authentication, until give up.
     *
     * @return authentication timeout in seconds
     */
    public int getAuthTimeoutSeconds() {
        return authTimeoutSeconds;
    }

    /**
     * Set the seconds, it waits for successful visual authentication, until give up.
     *
     * @param visuTimeoutSeconds visual authentication timeout in seconds
     */
    public void setVisuTimeoutSeconds(final int visuTimeoutSeconds) {
        this.visuTimeoutSeconds = visuTimeoutSeconds;
    }

    /**
     * Set the number of retries for successful authentication, until give up.
     *
     * @param retries number of retries
     */
    public void setRetries(final int retries) {
        this.retries = retries;
    }

    /**
     * Get the number of retries for successful authentication, until give up.
     *
     * @return number of retries
     */
    public int getRetries() {
        return retries;
    }

    /**
     * Web socket auto restart. If enabled it tries to reestablish the connection in case the remote end was closed.
     * @return true when auto restart is enabled, false otherwise
     */
    public boolean isAutoRestart() {
        return autoRestart;
    }

    /**
     * Web socket auto restart. If enabled it tries to reestablish the connection in case the remote end was closed.
     * Disabled by default.
     * @param autoRestart true when auto restart should be enabled, false otherwise
     */
    public void setAutoRestart(final boolean autoRestart) {
        this.autoRestart = autoRestart;
    }

    /**
     * Register the web socket listener allowing to handle web socket events.
     * @param webSocketListener web socket listener
     */
    public void registerWebSocketListener(final @NotNull LoxoneWebSocketListener webSocketListener) {
        this.webSocketListeners.add(webSocketListener);
    }

    private void ensureConnection() {
        if (!loxoneAuth.isInitialized()) {
            loxoneAuth.init();
        }
        if (webSocketClient == null || !webSocketClient.isOpen()) {
            log.trace("(Re)opening websocket connection");
            if (connectRwLock.writeLock().tryLock()) {
                try {
                    authSeqLatch = new CountDownLatch(1);
                    webSocketClient = webSocketClientProvider.apply(this, endpoint.webSocketUri());
                    webSocketClient.connect();
                } finally {
                    connectRwLock.writeLock().unlock();
                }
            }
        } else if (!loxoneAuth.isUsable()) {
            log.info("Authentication is not usable => starting the authentication");
            authSeqLatch = new CountDownLatch(1);
            loxoneAuth.startAuthentication();
        }
    }

    private void waitForAuth(final CountDownLatch latch, final int timeout, final boolean close) {
        try {
            if (latch.await(timeout, TimeUnit.SECONDS)) {
                log.trace("Waiting for authentication has been successful");
            } else {
                if (close) {
                    closeWebSocket();
                }
                throw new LoxoneConnectionException("Unable to authenticate within timeout");
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for authentication sequence completion", e);
        }
    }

    private void sendWithRetry(final Command command, final int retries) {
        ensureConnection();

        try {
            connectRwLock.readLock().lock();
            try {
                waitForAuth(authSeqLatch, authTimeoutSeconds, true);
                sendInternal(command);
            } finally {
                connectRwLock.readLock().unlock();
            }
        } catch (LoxoneConnectionException e) {
            if (retries > 0) {
                log.info("Connection or authentication failed, retrying...");
                waitForRetry();
                sendWithRetry(command, retries - 1);
            } else {
                log.info("Connection or authentication failed too many times, give up");
                throw new LoxoneException("Unable to authenticate within timeout with retry", e);
            }
        }
    }

    private void sendSecureWithRetry(final ControlCommand<?> command, final int retries) {
        ensureConnection();

        try {
            connectRwLock.readLock().lock();
            try {
                waitForAuth(authSeqLatch, authTimeoutSeconds, true);
                if (visuLatch == null || visuLatch.getCount() == 0) {
                    visuLatch = new CountDownLatch(1);
                    loxoneAuth.startVisuAuthentication();
                }
                waitForAuth(visuLatch, visuTimeoutSeconds, false);

                sendInternal(new SecuredCommand<>(command, loxoneAuth.getVisuHash()));
            } finally {
                connectRwLock.readLock().unlock();
            }
        } catch (LoxoneConnectionException e) {
            if (retries > 0) {
                log.info("Connection or authentication failed, retrying...");
                waitForRetry();
                sendSecureWithRetry(command, retries - 1);
            } else {
                log.info("Connection or authentication failed too many times, give up");
                throw new LoxoneException("Unable to authenticate within timeout with retry", e);
            }
        }
    }

    private void waitForRetry() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {
        }
    }


    // TODO Contains potential race condition when response to the sent command is received faster than command is added to queue
    // however, the probability it happens with real miniserver is very low
    void sendInternal(final Command command) {
        log.debug("Sending websocket message: " + command.getCommand());
        webSocketClient.send(command.getCommand());
        // KEEP_ALIVE command has no response at all
        if (! KEEP_ALIVE.getCommand().equals(command.getCommand())) {
            commands.add(command);
        }
    }

    void processMessage(final String message) {
        try {
            final Command<?> command = commands.remove();
            if (! Void.class.equals(command.getResponseType())) {
                final Object parsedMessage = Codec.readMessage(message, command.getResponseType());
                if (parsedMessage instanceof LoxoneMessage) {
                    final LoxoneMessage loxoneMessage = (LoxoneMessage) parsedMessage;
                    if (checkLoxoneMessage(command, loxoneMessage)) {
                        processCommand(command, loxoneMessage);
                    } else {
                        log.debug(loxoneMessage.toString());
                    }
                } else {
                    processCommand(command, command.ensureResponse(parsedMessage));
                }
            }
        } catch (NoSuchElementException e) {
            log.error("No command expected!", e);
        } catch (IOException e) {
            log.error("Can't parse response: " + e.getMessage());
        }

    }

    void processEvents(final MessageHeader msgHeader, final ByteBuffer bytes) {
        switch (msgHeader.getKind()) {
            case EVENT_VALUE:
                final Collection<ValueEvent> valueEvents = Codec.readValueEvents(bytes);
                log.trace("Incoming " + valueEvents);
                for (ValueEvent event : valueEvents) {
                    for (LoxoneEventListener eventListener : eventListeners) {
                        eventListener.onEvent(event);
                    }
                }
                break;
            case EVENT_TEXT:
                final Collection<TextEvent> textEvents = Codec.readTextEvents(bytes);
                log.trace(("Incoming " + textEvents));
                for (TextEvent event : textEvents) {
                    for (LoxoneEventListener eventListener : eventListeners) {
                        eventListener.onEvent(event);
                    }
                }
                break;
            default:
                log.trace("Incoming binary message " + Codec.bytesToHex(bytes.order(ByteOrder.LITTLE_ENDIAN).array()));
        }
    }

    void connectionOpened() {
        if (autoRestartFuture != null) {
            autoRestartFuture.cancel(true);
            autoRestartFuture = null;
        }
        scheduler.execute(() -> {
            loxoneAuth.startAuthentication();
            webSocketListeners.forEach(LoxoneWebSocketListener::webSocketOpened);
        });

    }

    void connectionClosed(int code, boolean remote) {
        webSocketListeners.forEach( webSocketListener ->  {
            if (remote) {
                webSocketListener.webSocketRemoteClosed(code);
            } else {
                webSocketListener.webSocketLocalClosed(code);
            }
        });
    }

    void autoRestart() {
        if (autoRestart) {
            final int rateSeconds = (retries + 1) * authTimeoutSeconds + 1;
            log.info("Scheduling automatic web socket restart in " + rateSeconds + " seconds");
            autoRestartFuture = scheduler.scheduleAtFixedRate(this::ensureConnection, rateSeconds, rateSeconds, TimeUnit.SECONDS);
        }
    }

    void closeWebSocket() {
        try {
            if (webSocketClient != null) {
                webSocketClient.closeBlocking();
            }
        } catch (InterruptedException e) {
            throw new LoxoneException("Interrupted while closing websocket", e);
        }
    }

    void wsClosed() {
        commands.clear();
        loxoneAuth.wsClosed();
    }

    private boolean checkLoxoneMessage(final Command command, final LoxoneMessage loxoneMessage) {
        switch (loxoneMessage.getCode()) {
            case HTTP_OK:
                log.debug("Message successfully processed.");
                if (command.is(loxoneMessage.getControl())) {
                    return true;
                } else {
                    log.error("Expected message with control containing " + command.getShouldContain()
                            +  " but " + loxoneMessage.getControl() + " received");
                    return false;
                }
            case HTTP_AUTH_TOO_LONG:
                log.debug("Not authenticated after connection. Authentication took too long.");
                return false;
            case HTTP_NOT_AUTHENTICATED:
                log.debug("Not authenticated. You must send auth request at the first.");
                return false;
            case HTTP_AUTH_FAIL:
                log.debug("Not authenticated. Bad credentials.");
                return false;
            case HTTP_UNAUTHORIZED:
                log.debug("Not authenticated for secured action.");
                return false;
            case HTTP_NOT_FOUND:
                log.debug("Can't find deviceId.");
                return false;
            default:
                log.debug("Unknown response code: " + loxoneMessage.getCode() + " for message");
                return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void processCommand(final Command<?> command, final Object message) {
        CommandResponseListener.State commandState = CommandResponseListener.State.IGNORED;
        final Iterator<CommandResponseListener> listeners = commandResponseListeners.iterator();
        while (listeners.hasNext() && commandState != CommandResponseListener.State.CONSUMED) {
            final CommandResponseListener next = listeners.next();
            if (next.accepts(message.getClass())) {
                commandState = commandState.fold(next.onCommand(command, message));
            }
        }

        if (commandState == CommandResponseListener.State.IGNORED) {
            log.warn("No command listener registered, ignoring command=" + command);
        }

        if (command != null && command.getCommand().startsWith(C_SYS_ENC)) {
            log.warn("Encrypted message receive is not supported");
        }
    }

    private class LoxoneAuthListener implements AuthListener {

        @Override
        public void authCompleted() {
            log.info("Authentication completed");
            if (authSeqLatch != null) {
                authSeqLatch.countDown();
            } else {
                throw new IllegalStateException("Authentication not guarded");
            }
        }

        @Override
        public void visuAuthCompleted() {
            log.info("Visualization authentication completed");
            if (visuLatch != null) {
                visuLatch.countDown();
            } else {
                throw new IllegalStateException("Visualization authentication not guarded");
            }
        }
    }
}
