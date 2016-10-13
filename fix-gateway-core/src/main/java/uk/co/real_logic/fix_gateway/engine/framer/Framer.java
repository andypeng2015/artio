/*
 * Copyright 2015-2016 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.engine.framer;

import io.aeron.Subscription;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.QueuedPipe;
import uk.co.real_logic.fix_gateway.DebugLogger;
import uk.co.real_logic.fix_gateway.LivenessDetector;
import uk.co.real_logic.fix_gateway.Pressure;
import uk.co.real_logic.fix_gateway.engine.EngineConfiguration;
import uk.co.real_logic.fix_gateway.engine.EngineDescriptorStore;
import uk.co.real_logic.fix_gateway.engine.SessionInfo;
import uk.co.real_logic.fix_gateway.engine.framer.TcpChannelSupplier.NewChannelHandler;
import uk.co.real_logic.fix_gateway.engine.logger.ReplayQuery;
import uk.co.real_logic.fix_gateway.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.fix_gateway.messages.*;
import uk.co.real_logic.fix_gateway.protocol.*;
import uk.co.real_logic.fix_gateway.replication.ClusterableStreams;
import uk.co.real_logic.fix_gateway.replication.ClusterableSubscription;
import uk.co.real_logic.fix_gateway.replication.SoloSubscription;
import uk.co.real_logic.fix_gateway.session.CompositeKey;
import uk.co.real_logic.fix_gateway.session.Session;
import uk.co.real_logic.fix_gateway.session.SessionIdStrategy;
import uk.co.real_logic.fix_gateway.timing.Timer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.aeron.Publication.BACK_PRESSURED;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.agrona.CloseHelper.close;
import static uk.co.real_logic.fix_gateway.GatewayProcess.OUTBOUND_LIBRARY_STREAM;
import static uk.co.real_logic.fix_gateway.LogTag.LIBRARY_CONNECT;
import static uk.co.real_logic.fix_gateway.Pressure.isBackPressured;
import static uk.co.real_logic.fix_gateway.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.fix_gateway.engine.SessionInfo.UNK_SESSION;
import static uk.co.real_logic.fix_gateway.engine.framer.Continuation.COMPLETE;
import static uk.co.real_logic.fix_gateway.library.FixLibrary.NO_MESSAGE_REPLAY;
import static uk.co.real_logic.fix_gateway.messages.ConnectionType.ACCEPTOR;
import static uk.co.real_logic.fix_gateway.messages.ConnectionType.INITIATOR;
import static uk.co.real_logic.fix_gateway.messages.GatewayError.*;
import static uk.co.real_logic.fix_gateway.messages.LogonStatus.LIBRARY_NOTIFICATION;
import static uk.co.real_logic.fix_gateway.messages.SequenceNumberType.DETERMINE_AT_LOGON;
import static uk.co.real_logic.fix_gateway.messages.SessionReplyStatus.*;
import static uk.co.real_logic.fix_gateway.messages.SessionState.ACTIVE;
import static uk.co.real_logic.fix_gateway.messages.SessionState.CONNECTED;
import static uk.co.real_logic.fix_gateway.session.Session.UNKNOWN;

/**
 * Handles incoming connections from clients and outgoing connections to exchanges.
 */
class Framer implements Agent, EngineEndPointHandler, ProtocolHandler
{

    private static final ByteBuffer CONNECT_ERROR;
    private static final List<SessionInfo> NO_SESSIONS = emptyList();

    static
    {
        final byte[] errorBytes =
            "This is not the cluster's leader node, please connect to the leader".getBytes(US_ASCII);
        CONNECT_ERROR = ByteBuffer.wrap(errorBytes);
    }

    private final RetryManager retryManager = new RetryManager();
    private final List<ResetSequenceNumberCommand> replies = new ArrayList<>();
    private final Int2ObjectHashMap<LiveLibraryInfo> idToLibrary = new Int2ObjectHashMap<>();
    private final Consumer<AdminCommand> onAdminCommand = command -> command.execute(this);
    private final NewChannelHandler onNewConnectionFunc = this::onNewConnection;

    private final TcpChannelSupplier channelSupplier;
    private final EpochClock clock;
    private final Timer outboundTimer;
    private final Timer sendTimer;

    private final ControlledFragmentHandler outboundLibrarySubscriber;
    private final ControlledFragmentHandler outboundClusterSubscriber;

    private final ReceiverEndPoints receiverEndPoints = new ReceiverEndPoints();
    private final SenderEndPoints senderEndPoints;

    private final EngineConfiguration configuration;
    private final EndPointFactory endPointFactory;
    private final ClusterableSubscription outboundClusterSubscription;
    private final SoloSubscription outboundLibrarySubscription;
    private final ClusterableSubscription outboundSlowSubscription;
    private final Subscription replaySubscription;
    private final GatewayPublication inboundPublication;
    private final ClusterableStreams clusterableStreams;
    private final SessionIdStrategy sessionIdStrategy;
    private final SessionIds sessionIds;
    private final QueuedPipe<AdminCommand> adminCommands;
    private final SequenceNumberIndexReader sentSequenceNumberIndex;
    private final SequenceNumberIndexReader receivedSequenceNumberIndex;
    private final IdleStrategy idleStrategy;
    private final int inboundBytesReceivedLimit;
    private final int outboundLibraryFragmentLimit;
    private final int replayFragmentLimit;
    private final GatewaySessions gatewaySessions;
    /** Null if inbound messages are not logged */
    private final ReplayQuery inboundMessages;
    private final ErrorHandler errorHandler;
    private final GatewayPublication outboundPublication;

    private long nextConnectionId = (long)(Math.random() * Long.MAX_VALUE);

    Framer(
        final EpochClock clock,
        final Timer outboundTimer,
        final Timer sendTimer,
        final EngineConfiguration configuration,
        final EndPointFactory endPointFactory,
        final ClusterableSubscription outboundClusterSubscription,
        final SoloSubscription outboundLibrarySubscription,
        final ClusterableSubscription outboundSlowSubscription,
        final Subscription replaySubscription,
        final QueuedPipe<AdminCommand> adminCommands,
        final SessionIdStrategy sessionIdStrategy,
        final SessionIds sessionIds,
        final SequenceNumberIndexReader sentSequenceNumberIndex,
        final SequenceNumberIndexReader receivedSequenceNumberIndex,
        final GatewaySessions gatewaySessions,
        final ReplayQuery inboundMessages,
        final ErrorHandler errorHandler,
        final GatewayPublication outboundPublication,
        final GatewayPublication replyPublication,
        final ClusterableStreams clusterableStreams,
        final EngineDescriptorStore engineDescriptorStore,
        final LongHashSet replicatedConnectionIds,
        final GatewayPublication inboundPublication)
    {
        this.clock = clock;
        this.outboundTimer = outboundTimer;
        this.sendTimer = sendTimer;
        this.configuration = configuration;
        this.endPointFactory = endPointFactory;
        this.outboundClusterSubscription = outboundClusterSubscription;
        this.outboundLibrarySubscription = outboundLibrarySubscription;
        this.outboundSlowSubscription = outboundSlowSubscription;
        this.replaySubscription = replaySubscription;
        this.gatewaySessions = gatewaySessions;
        this.inboundMessages = inboundMessages;
        this.errorHandler = errorHandler;
        this.outboundPublication = outboundPublication;
        this.inboundPublication = inboundPublication;
        this.clusterableStreams = clusterableStreams;
        this.senderEndPoints = new SenderEndPoints();
        this.sessionIdStrategy = sessionIdStrategy;
        this.sessionIds = sessionIds;
        this.adminCommands = adminCommands;
        this.sentSequenceNumberIndex = sentSequenceNumberIndex;
        this.receivedSequenceNumberIndex = receivedSequenceNumberIndex;
        this.idleStrategy = configuration.framerIdleStrategy();

        this.outboundLibraryFragmentLimit = configuration.outboundLibraryFragmentLimit();
        this.replayFragmentLimit = configuration.replayFragmentLimit();
        this.inboundBytesReceivedLimit = configuration.inboundBytesReceivedLimit();

        if (isClustered())
        {
            outboundLibrarySubscriber = new SubscriptionSplitter(
                clusterableStreams,
                new EngineProtocolSubscription(this),
                clusterableStreams.publication(OUTBOUND_LIBRARY_STREAM),
                replyPublication,
                engineDescriptorStore,
                configuration.bindAddress().toString(),
                replicatedConnectionIds);
            outboundClusterSubscriber = ProtocolSubscription.of(this);
        }
        else
        {
            outboundLibrarySubscriber = ProtocolSubscription.of(this, new EngineProtocolSubscription(this));
            outboundClusterSubscriber = null;
        }

        try
        {
            channelSupplier = configuration.channelSupplier();
        }
        catch (IOException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    private boolean isClustered()
    {
        return outboundClusterSubscription != null;
    }

    @Override
    public int doWork() throws Exception
    {
        final long timeInMs = clock.time();
        return retryManager.attemptSteps() +
            sendOutboundMessages() +
            sendReplayMessages() +
            pollEndPoints() +
            pollNewConnections(timeInMs) +
            pollLibraries(timeInMs) +
            gatewaySessions.pollSessions(timeInMs) +
            adminCommands.drain(onAdminCommand) +
            checkReplies();
    }

    private int checkReplies()
    {
        return RetryManager.removeIf(replies, ResetSequenceNumberCommand::poll);
    }

    private int sendReplayMessages()
    {
        return replaySubscription.controlledPoll(outboundLibrarySubscriber, replayFragmentLimit);
    }

    private int sendOutboundMessages()
    {
        final int newMessagesRead =
            outboundLibrarySubscription.controlledPoll(outboundLibrarySubscriber, outboundLibraryFragmentLimit);
        int messagesRead = newMessagesRead +
            outboundSlowSubscription.controlledPoll(senderEndPoints, outboundLibraryFragmentLimit);

        if (isClustered())
        {
            messagesRead +=
                outboundClusterSubscription.controlledPoll(outboundClusterSubscriber, outboundLibraryFragmentLimit);
        }

        return messagesRead;
    }

    private int pollLibraries(final long timeInMs)
    {
        int total = 0;
        final Iterator<LiveLibraryInfo> iterator = idToLibrary.values().iterator();
        while (iterator.hasNext())
        {
            final LiveLibraryInfo library = iterator.next();
            total += library.poll(timeInMs);
            if (!library.isConnected())
            {
                iterator.remove();
                acquireLibrarySessions(library);
                saveLibraryTimeout(library);
            }
        }

        return total;
    }

    private void saveLibraryTimeout(final LibraryInfo library)
    {
        final int libraryId = library.libraryId();
        schedule(() -> inboundPublication.saveLibraryTimeout(libraryId, 0));
    }

    private void acquireLibrarySessions(final LiveLibraryInfo library)
    {
        // Ensure latest library message is indexed
        final int librarySessionId = library.aeronSessionId();
        final long position = outboundLibrarySubscription.positionOf(librarySessionId);
        sentSequenceNumberIndex.awaitingIndexingUpTo(librarySessionId, position, idleStrategy);

        final List<GatewaySession> sessions = library.gatewaySessions();
        for (int i = 0, size = sessions.size(); i < size; i++)
        {
            final GatewaySession session = sessions.get(i);
            final long sessionId = session.sessionId();
            final int sentSequenceNumber = sentSequenceNumberIndex.lastKnownSequenceNumber(sessionId);
            final int receivedSequenceNumber = receivedSequenceNumberIndex.lastKnownSequenceNumber(sessionId);
            final boolean hasLoggedIn = receivedSequenceNumber != UNK_SESSION;
            final SessionState state = hasLoggedIn ? ACTIVE : CONNECTED;
            this.gatewaySessions.acquire(
                session,
                state,
                session.heartbeatIntervalInS(),
                sentSequenceNumber,
                receivedSequenceNumber,
                session.username(),
                session.password()
            );
        }
    }

    private int pollEndPoints() throws IOException
    {
        final int inboundBytesReceivedLimit = this.inboundBytesReceivedLimit;

        int totalBytesReceived = 0;
        int bytesReceived;
        do
        {
            bytesReceived = receiverEndPoints.pollEndPoints();
            totalBytesReceived += bytesReceived;
        }
        while (bytesReceived > 0 && totalBytesReceived < inboundBytesReceivedLimit);

        return totalBytesReceived;
    }

    private int pollNewConnections(final long timeInMs) throws IOException
    {
        return channelSupplier.forEachChannel(timeInMs, onNewConnectionFunc);
    }

    private void onNewConnection(final long timeInMs, final TcpChannel channel) throws IOException
    {
        if (clusterableStreams.isLeader())
        {
            final long connectionId = this.nextConnectionId++;
            final GatewaySession session = setupConnection(
                channel, connectionId, UNKNOWN, null, ENGINE_LIBRARY_ID, ACCEPTOR, DETERMINE_AT_LOGON);

            session.disconnectAt(timeInMs + configuration.noLogonDisconnectTimeoutInMs());

            gatewaySessions.acquire(
                session,
                CONNECTED,
                configuration.defaultHeartbeatIntervalInS(),
                UNK_SESSION,
                UNK_SESSION,
                null,
                null);

            final String address = channel.remoteAddress();
            // In this case the save connect is simply logged for posterities sake
            // So in the back-pressure we should just drop it
            final long position = inboundPublication.saveConnect(connectionId, address);
            if (isBackPressured(position))
            {
                errorHandler.onError(new IllegalStateException(
                    "Failed to log connect from " + address + " due to backpressure"));
            }
        }
        else
        {
            final String address = channel.remoteAddress();
            errorHandler.onError(new IllegalStateException(
                String.format("Attempted connection from %s whilst follower", address)));

            // NB: channel is still blocking at this point, so will be placed in buffer
            // NB: Writing error message is best effort, possible that other end closes
            // Before receipt.
            channel.write(CONNECT_ERROR);
            channel.close();
        }
    }

    public Action onInitiateConnection(
        final int libraryId,
        final int port,
        final String host,
        final String senderCompId,
        final String senderSubId,
        final String senderLocationId,
        final String targetCompId,
        final SequenceNumberType sequenceNumberType,
        final int requestedInitialSequenceNumber,
        final String username,
        final String password,
        final int heartbeatIntervalInS,
        final long correlationId,
        final Header header)
    {
        final Action action = retryManager.retry(correlationId);
        if (action != null)
        {
            return action;
        }

        final LiveLibraryInfo library = idToLibrary.get(libraryId);
        if (library == null)
        {
            saveError(GatewayError.UNKNOWN_LIBRARY, libraryId, correlationId);

            return CONTINUE;
        }

        try
        {
            final TcpChannel channel;
            final InetSocketAddress address;
            try
            {
                address = new InetSocketAddress(host, port);
                channel = channelSupplier.open(address);
            }
            catch (final Exception e)
            {
                saveError(UNABLE_TO_CONNECT, libraryId, correlationId, e);

                return CONTINUE;
            }

            final long connectionId = this.nextConnectionId++;

            final CompositeKey sessionKey = sessionIdStrategy.onLogon(
                senderCompId, senderSubId, senderLocationId, targetCompId);
            final long sessionId = sessionIds.onLogon(sessionKey);
            if (sessionId == SessionIds.DUPLICATE_SESSION)
            {
                saveError(DUPLICATE_SESSION, libraryId, correlationId);

                return CONTINUE;
            }

            final GatewaySession session =
                setupConnection(channel, connectionId, sessionId, sessionKey, libraryId, INITIATOR, sequenceNumberType);

            library.addSession(session);

            sentSequenceNumberIndex.awaitingIndexingUpTo(header, idleStrategy);

            final int lastSentSequenceNumber = sentSequenceNumberIndex.lastKnownSequenceNumber(sessionId);
            final int lastReceivedSequenceNumber = receivedSequenceNumberIndex.lastKnownSequenceNumber(sessionId);
            session.onLogon(sessionId, sessionKey, username, password, heartbeatIntervalInS);

            final UnitOfWork unitOfWork = new UnitOfWork(
                () -> inboundPublication.saveManageConnection(
                    connectionId, sessionId, address.toString(), libraryId, INITIATOR,
                    lastSentSequenceNumber, lastReceivedSequenceNumber, CONNECTED, heartbeatIntervalInS, correlationId),
                () -> inboundPublication.saveLogon(
                    libraryId, connectionId, sessionId,
                    lastSentSequenceNumber, lastReceivedSequenceNumber,
                    senderCompId, senderSubId, senderLocationId, targetCompId,
                    username, password, LogonStatus.NEW)
            );

            return retryManager.firstAttempt(correlationId, unitOfWork);
        }
        catch (final Exception e)
        {
            saveError(EXCEPTION, libraryId, correlationId, e);
        }

        return CONTINUE;
    }

    private void saveError(final GatewayError error, final int libraryId, final long replyToId)
    {
        final long position = inboundPublication.saveError(error, libraryId, replyToId, "");
        pressuredError(error, libraryId, null, position);
    }

    private void saveError(final GatewayError error, final int libraryId, final long replyToId, final Exception e)
    {
        final String message = e.getMessage();
        final long position = inboundPublication.saveError(error, libraryId, replyToId, message == null ? "" : message);
        pressuredError(error, libraryId, message, position);
    }

    private void pressuredError(
        final GatewayError error, final int libraryId, final String message, final long position)
    {
        if (isBackPressured(position))
        {
            if (message == null)
            {
                errorHandler.onError(new IllegalStateException(
                    "Back pressured " + error + " for " + libraryId));
            }
            else
            {
                errorHandler.onError(new IllegalStateException(
                    "Back pressured " + error + ": " + message + " for " + libraryId));
            }
        }
    }

    public Action onMessage(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final int libraryId,
        final long connectionId,
        final long sessionId,
        final int messageType,
        final long timestamp,
        final long position)
    {
        final long now = outboundTimer.recordSince(timestamp);

        if (!clusterableStreams.isLeader())
        {
            sessionIds.onSentFollowerMessage(sessionId, messageType, buffer, offset, length);
        }

        senderEndPoints.onMessage(libraryId, connectionId, buffer, offset, length);

        sendTimer.recordSince(now);

        return CONTINUE;
    }

    private GatewaySession setupConnection(
        final TcpChannel channel,
        final long connectionId,
        final long sessionId,
        final CompositeKey sessionKey,
        final int libraryId,
        final ConnectionType connectionType,
        final SequenceNumberType sequenceNumberType)
        throws IOException
    {
        final ReceiverEndPoint receiverEndPoint =
            endPointFactory.receiverEndPoint(channel, connectionId, sessionId, libraryId, this,
                sentSequenceNumberIndex, receivedSequenceNumberIndex, sequenceNumberType, connectionType);
        receiverEndPoints.add(receiverEndPoint);

        final SenderEndPoint senderEndPoint =
            endPointFactory.senderEndPoint(channel, connectionId, libraryId, this);
        senderEndPoints.add(senderEndPoint);

        final GatewaySession gatewaySession = new GatewaySession(
            connectionId,
            sessionId,
            channel.remoteAddress(),
            connectionType,
            sessionKey,
            receiverEndPoint,
            senderEndPoint);

        receiverEndPoint.gatewaySession(gatewaySession);

        return gatewaySession;
    }

    public Action onRequestDisconnect(final int libraryId, final long connectionId, final DisconnectReason reason)
    {
        return onDisconnect(libraryId, connectionId, reason);
    }

    public Action onDisconnect(final int libraryId, final long connectionId, final DisconnectReason reason)
    {
        receiverEndPoints.removeConnection(connectionId, reason);
        senderEndPoints.removeConnection(connectionId);
        final LiveLibraryInfo library = idToLibrary.get(libraryId);
        if (library != null)
        {
            library.removeSession(connectionId);
        }
        else
        {
            gatewaySessions.releaseByConnectionId(connectionId);
        }

        return CONTINUE;
    }

    public Action onLibraryConnect(final int libraryId,
                                   final long correlationId,
                                   final int aeronSessionId)
    {
        final Action action = retryManager.retry(correlationId);
        if (action != null)
        {
            return action;
        }

        final LiveLibraryInfo existingLibrary = idToLibrary.get(libraryId);
        if (existingLibrary != null)
        {
            existingLibrary.onHeartbeat(clock.time());

            return Pressure.apply(
                inboundPublication.saveControlNotification(libraryId, existingLibrary.sessions()));
        }

        final LivenessDetector livenessDetector = LivenessDetector.forEngine(
            inboundPublication,
            libraryId,
            configuration.replyTimeoutInMs(),
            clock.time());

        final LiveLibraryInfo library = new LiveLibraryInfo(libraryId, livenessDetector, aeronSessionId);
        idToLibrary.put(libraryId, library);

        final UnitOfWork unitOfWork = new UnitOfWork(
            gatewaySessions
                .sessions()
                .stream()
                .map(gatewaySession ->
                    (Continuation) () ->
                        saveLogon(libraryId, gatewaySession, UNK_SESSION, UNK_SESSION, LIBRARY_NOTIFICATION))
                .collect(Collectors.toList()));

        return retryManager.firstAttempt(correlationId, unitOfWork);
    }

    public Action onApplicationHeartbeat(final int libraryId, final int aeronSessionId)
    {
        final LiveLibraryInfo library = idToLibrary.get(libraryId);
        if (library != null)
        {
            final long timeInMs = clock.time();
            DebugLogger.log(
                LIBRARY_CONNECT, "Received Heartbeat from library %d at timeInMs %d\n", libraryId, timeInMs);
            library.onHeartbeat(timeInMs);

            return CONTINUE;
        }
        else
        {
            final Action action = onLibraryConnect(libraryId, libraryId, aeronSessionId);
            if (action == ABORT)
            {
                return ABORT;
            }

            return Pressure.apply(
                inboundPublication.saveControlNotification(libraryId, NO_SESSIONS));
        }
    }

    public Action onReleaseSession(
        final int libraryId,
        final long connectionId,
        final long correlationId,
        final SessionState state,
        final long heartbeatIntervalInMs,
        final int lastSentSequenceNumber,
        final int lastReceivedSequenceNumber,
        final String username,
        final String password,
        final Header header)
    {
        final LiveLibraryInfo libraryInfo = idToLibrary.get(libraryId);
        if (libraryInfo == null)
        {
            return Pressure.apply(
                inboundPublication.saveReleaseSessionReply(
                    libraryId, SessionReplyStatus.UNKNOWN_LIBRARY, correlationId));
        }

        final GatewaySession session = libraryInfo.removeSession(connectionId);
        if (session == null)
        {
            return Pressure.apply(
                inboundPublication.saveReleaseSessionReply(
                    libraryId, SessionReplyStatus.UNKNOWN_SESSION, correlationId));
        }

        final Action action = Pressure.apply(inboundPublication.saveReleaseSessionReply(libraryId, OK, correlationId));
        if (action == ABORT)
        {
            libraryInfo.addSession(session);
        }
        else
        {
            gatewaySessions.acquire(
                session,
                state,
                (int) MILLISECONDS.toSeconds(heartbeatIntervalInMs),
                lastSentSequenceNumber,
                lastReceivedSequenceNumber,
                username,
                password);
        }

        return action;
    }

    public Action onRequestSession(
        final int libraryId,
        final long sessionId,
        final long correlationId,
        final int replayFromSequenceNumber)
    {
        final LiveLibraryInfo libraryInfo = idToLibrary.get(libraryId);
        if (libraryInfo == null)
        {
            return Pressure.apply(
                inboundPublication.saveRequestSessionReply(
                    libraryId, SessionReplyStatus.UNKNOWN_LIBRARY, correlationId));
        }

        final GatewaySession gatewaySession = gatewaySessions.releaseBySessionId(sessionId);
        if (gatewaySession == null)
        {
            return Pressure.apply(
                inboundPublication.saveRequestSessionReply(
                    libraryId, SessionReplyStatus.UNKNOWN_SESSION, correlationId));
        }

        final Session session = gatewaySession.session();
        if (!session.isActive())
        {
            return Pressure.apply(
                inboundPublication.saveRequestSessionReply(
                    libraryId, SESSION_NOT_LOGGED_IN, correlationId));
        }

        final Action action = retryManager.retry(correlationId);
        if (action != null)
        {
            return action;
        }

        final long connectionId = gatewaySession.connectionId();
        final int lastSentSeqNum = session.lastSentMsgSeqNum();
        final int lastRecvSeqNum = session.lastReceivedMsgSeqNum();
        final SessionState sessionState = session.state();
        gatewaySession.handoverManagementTo(libraryId);
        libraryInfo.addSession(gatewaySession);

        final List<Continuation> continuations = new ArrayList<>();
        continuations.add(() -> inboundPublication.saveManageConnection(
            connectionId,
            sessionId, gatewaySession.address(),
            libraryId,
            gatewaySession.connectionType(),
            lastSentSeqNum,
            lastRecvSeqNum,
            sessionState,
            gatewaySession.heartbeatIntervalInS(),
            correlationId));

        continuations.add(() ->
            saveLogon(libraryId, gatewaySession, lastSentSeqNum, lastRecvSeqNum, LogonStatus.NEW));

        catchupSession(
            continuations,
            libraryId,
            connectionId,
            correlationId,
            replayFromSequenceNumber,
            gatewaySession,
            lastRecvSeqNum);

        return retryManager.firstAttempt(correlationId, new UnitOfWork(continuations));
    }

    private long saveLogon(
        final int libraryId,
        final GatewaySession gatewaySession,
        final int lastSentSeqNum,
        final int lastReceivedSeqNum,
        final LogonStatus status)
    {
        final CompositeKey compositeKey = gatewaySession.sessionKey();
        if (compositeKey != null)
        {
            final long connectionId = gatewaySession.connectionId();
            final String username = gatewaySession.username();
            final String password = gatewaySession.password();
            return inboundPublication.saveLogon(
                libraryId,
                connectionId,
                gatewaySession.sessionId(),
                lastSentSeqNum,
                lastReceivedSeqNum,
                compositeKey.senderCompId(),
                compositeKey.senderSubId(),
                compositeKey.senderLocationId(),
                compositeKey.targetCompId(),
                username,
                password,
                status);
        }

        return COMPLETE;
    }

    private void catchupSession(
        final List<Continuation> continuations,
        final int libraryId,
        final long connectionId,
        final long correlationId,
        final int replayFromSequenceNumber,
        final GatewaySession session,
        final int lastReceivedSeqNum)
    {
        if (replayFromSequenceNumber != NO_MESSAGE_REPLAY)
        {
            final long sessionId = session.sessionId();
            final int expectedNumberOfMessages = lastReceivedSeqNum - replayFromSequenceNumber;
            if (expectedNumberOfMessages < 0)
            {
                continuations.add(() ->
                    sequenceNumberTooHigh(libraryId, correlationId, replayFromSequenceNumber, lastReceivedSeqNum));
                return;
            }

            continuations.add(() ->
                inboundPublication.saveCatchup(libraryId, connectionId, expectedNumberOfMessages));
            continuations.add(() ->
                receivedSequenceNumberIndex.lastKnownSequenceNumber(sessionId) < lastReceivedSeqNum ? BACK_PRESSURED : COMPLETE);
            continuations.add(
                new CatchupReplayer(
                    inboundMessages,
                    inboundPublication,
                    errorHandler,
                    correlationId,
                    libraryId,
                    expectedNumberOfMessages,
                    lastReceivedSeqNum,
                    replayFromSequenceNumber,
                    session));
        }
        else
        {
            continuations.add(() ->
                CatchupReplayer.sendOk(inboundPublication, correlationId, session, libraryId));
        }
    }

    private long sequenceNumberTooHigh(
        final int libraryId,
        final long correlationId,
        final int replayFromSequenceNumber,
        final int lastReceivedSeqNum)
    {
        final long position = inboundPublication.saveRequestSessionReply(
            libraryId, SEQUENCE_NUMBER_TOO_HIGH, correlationId);
        if (position > 0)
        {
            errorHandler.onError(new IllegalStateException(String.format(
                "Sequence Number too high for %d, wanted %d, but we've only archived %d",
                correlationId,
                replayFromSequenceNumber,
                lastReceivedSeqNum)));
        }
        return position;
    }

    void onQueryLibraries(final QueryLibrariesCommand command)
    {
        final List<LibraryInfo> libraries = new ArrayList<>(idToLibrary.values());
        libraries.add(new EngineLibraryInfo(gatewaySessions));
        command.success(libraries);
    }

    void onResetSessionIds(final File backupLocation, final ResetSessionIdsCommand command)
    {
        schedule(
            new UnitOfWork(
                inboundPublication::saveResetSessionIds,
                outboundPublication::saveResetSessionIds,
                () ->
                {
                    try
                    {
                        sessionIds.reset(backupLocation);
                    }
                    catch (final Exception ex)
                    {
                        command.onError(ex);
                    }
                    return COMPLETE;
                },
                () ->
                {
                    if (command.isDone())
                    {
                        return COMPLETE;
                    }

                    if (sequenceNumbersNotReset())
                    {
                        return BACK_PRESSURED;
                    }
                    else
                    {
                        command.success();
                        return COMPLETE;
                    }
                }
            )
        );
    }

    void onResetSequenceNumber(final ResetSequenceNumberCommand reply)
    {
        if (!reply.poll())
        {
            replies.add(reply);
        }
    }

    private boolean sequenceNumbersNotReset()
    {
        return sentSequenceNumberIndex.lastKnownSequenceNumber(1) != UNK_SESSION
            || receivedSequenceNumberIndex.lastKnownSequenceNumber(1) != UNK_SESSION;
    }

    public void onClose()
    {
        close(inboundMessages);
        receiverEndPoints.close();
        senderEndPoints.close();
        close(channelSupplier);
    }

    public String roleName()
    {
        return "Framer " + configuration.nodeId();
    }

    void schedule(final Continuation continuation)
    {
        if (continuation.attemptToAction() != CONTINUE)
        {
            retryManager.schedule(continuation);
        }
    }

}
