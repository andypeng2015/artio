/*
 * Copyright 2015 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.replication;

import org.junit.Before;
import org.junit.Test;
import uk.co.real_logic.aeron.Image;
import uk.co.real_logic.aeron.Subscription;
import uk.co.real_logic.aeron.logbuffer.BlockHandler;
import uk.co.real_logic.aeron.logbuffer.FragmentHandler;
import uk.co.real_logic.agrona.collections.IntHashSet;
import uk.co.real_logic.fix_gateway.engine.logger.ArchiveReader;

import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.fix_gateway.messages.AcknowledgementStatus.MISSING_LOG_ENTRIES;
import static uk.co.real_logic.fix_gateway.messages.Vote.FOR;
import static uk.co.real_logic.fix_gateway.replication.Follower.NO_ONE;
import static uk.co.real_logic.fix_gateway.replication.ReplicationAsserts.*;

public class LeaderTest
{
    private static final short ID = 2;
    private static final int LEADERSHIP_TERM = 1;
    private static final int NEW_TERM = LEADERSHIP_TERM + 1;
    private static final int LEADER_SESSION_ID = 42;
    private static final long TIME = 10L;
    private static final long POSITION = 40L;
    private static final int HEARTBEAT_INTERVAL_IN_MS = 10;
    private static final short NEW_LEADER_ID = 3;
    private static final short FOLLOWER_ID = 4;
    private static final short CANDIDATE_ID = 5;
    private static final int NEW_LEADER_SESSION_ID = 43;

    private RaftPublication controlPublication = mock(RaftPublication.class);
    private RaftNode raftNode = mock(RaftNode.class);
    private Subscription acknowledgementSubscription = mock(Subscription.class);
    private Subscription dataSubscription = mock(Subscription.class);
    private Image leaderDataImage = mock(Image.class);
    private ArchiveReader archiveReader = mock(ArchiveReader.class);
    private TermState termState = new TermState()
        .leadershipTerm(LEADERSHIP_TERM)
        .commitPosition(POSITION);

    private Leader leader = new Leader(
        ID,
        new EntireClusterAcknowledgementStrategy(),
        new IntHashSet(40, -1),
        raftNode,
        mock(FragmentHandler.class),
        0,
        HEARTBEAT_INTERVAL_IN_MS,
        termState,
        LEADER_SESSION_ID,
        archiveReader);

    @Before
    public void setUp()
    {
        when(dataSubscription.getImage(LEADER_SESSION_ID)).thenReturn(leaderDataImage);

        leader
            .controlPublication(controlPublication)
            .acknowledgementSubscription(acknowledgementSubscription)
            .dataSubscription(dataSubscription)
            .getsElected(TIME);

        when(archiveReader.readBlock(eq(LEADER_SESSION_ID), anyLong(), anyInt(), any())).then(inv ->
        {
            final Object[] arguments = inv.getArguments();
            final int length = (int) arguments[2];
            final BlockHandler handler = (BlockHandler) arguments[3];

            handler.onBlock(null, 0, length, LEADER_SESSION_ID, 1);

            return true;
        });
    }

    @Test
    public void onNewLeaderHeartbeatBecomeFollower()
    {
        receivesHeartbeat(NEW_LEADER_ID, NEW_TERM, NEW_LEADER_SESSION_ID);

        transitionsToFollower(NO_ONE);

        assertThat(termState, hasLeaderSessionId(NEW_LEADER_SESSION_ID));
        assertThat(termState, hasLeadershipTerm(NEW_TERM));
        assertThat(termState, hasPositions(POSITION));
    }

    @Test
    public void onHeartbeatFromOldTermStayLeader()
    {
        receivesHeartbeat(NEW_LEADER_ID, LEADERSHIP_TERM, NEW_LEADER_SESSION_ID);

        neverTransitionsToFollower(raftNode);
    }

    @Test
    public void onHeartbeatFromSelfStayLeader()
    {
        receivesHeartbeat(ID, LEADERSHIP_TERM, LEADER_SESSION_ID);

        neverTransitionsToFollower(raftNode);
    }

    @Test
    public void onRequestVoteWithHigherTermBecomeFollower()
    {
        leader.onRequestVote(CANDIDATE_ID, NEW_TERM, POSITION);

        voteForCandidate();
        transitionsToFollower(CANDIDATE_ID);

        assertThat(termState, noLeaderMatcher());
        assertThat(termState, hasLeadershipTerm(NEW_TERM));
        assertThat(termState, hasPositions(POSITION));
    }

    @Test
    public void onRequestVoteWithLowerTermStayLeader()
    {
        leader.onRequestVote(CANDIDATE_ID, LEADERSHIP_TERM, POSITION);

        neverTransitionsToFollower(raftNode);
    }

    @Test
    public void onRequestVoteWithLowerPositionStayLeader()
    {
        leader.onRequestVote(CANDIDATE_ID, LEADERSHIP_TERM, 0L);

        neverTransitionsToFollower(raftNode);
    }

    @Test
    public void onElectionHeartbeat()
    {
        verify(controlPublication).saveConcensusHeartbeat(ID, LEADERSHIP_TERM, POSITION, LEADER_SESSION_ID);
    }

    @Test
    public void shouldResendDataInResponseToMissingLogEntries()
    {
        final long followerPosition = 0;

        receivesMissingLogEntries(followerPosition);

        resendsMissingLogEntries(followerPosition);
    }

    private void voteForCandidate()
    {
        verify(controlPublication).saveReplyVote(ID, CANDIDATE_ID, NEW_TERM, FOR);
    }

    private void receivesMissingLogEntries(final long followerPosition)
    {
        leader.onMessageAcknowledgement(followerPosition, FOLLOWER_ID, MISSING_LOG_ENTRIES);
    }

    private void transitionsToFollower(final int votedFor)
    {
        verify(raftNode, atLeastOnce()).transitionToFollower(eq(leader), eq(votedFor), anyLong());
    }

    private void resendsMissingLogEntries(final long followerPosition)
    {
        verify(controlPublication).saveResend(
            eq(LEADER_SESSION_ID),
            eq(LEADERSHIP_TERM),
            eq(followerPosition),
            any(),
            eq(0),
            eq((int) POSITION));
    }

    private void receivesHeartbeat(final short leaderId, final int leaderShipTerm, final int dataSessionId)
    {
        leader.onConcensusHeartbeat(leaderId, leaderShipTerm, POSITION, dataSessionId);
    }

}
