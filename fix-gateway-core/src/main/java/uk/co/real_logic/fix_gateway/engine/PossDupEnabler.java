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
package uk.co.real_logic.fix_gateway.engine;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import io.aeron.logbuffer.ExclusiveBufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.EpochClock;
import uk.co.real_logic.fix_gateway.DebugLogger;
import uk.co.real_logic.fix_gateway.dictionary.IntDictionary;
import uk.co.real_logic.fix_gateway.fields.UtcTimestampEncoder;
import uk.co.real_logic.fix_gateway.messages.FixMessageDecoder;
import uk.co.real_logic.fix_gateway.messages.MessageHeaderDecoder;
import uk.co.real_logic.fix_gateway.otf.OtfParser;
import uk.co.real_logic.fix_gateway.util.MutableAsciiBuffer;

import java.util.function.Consumer;
import java.util.function.IntPredicate;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static io.aeron.protocol.DataHeaderFlyweight.BEGIN_FLAG;
import static io.aeron.protocol.DataHeaderFlyweight.END_FLAG;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static uk.co.real_logic.fix_gateway.LogTag.CATCHUP;
import static uk.co.real_logic.fix_gateway.engine.PossDupFinder.NO_ENTRY;
import static uk.co.real_logic.fix_gateway.engine.framer.CatchupReplayer.FRAME_LENGTH;
import static uk.co.real_logic.fix_gateway.util.AsciiBuffer.SEPARATOR_LENGTH;
import static uk.co.real_logic.fix_gateway.util.MutableAsciiBuffer.SEPARATOR;

public class PossDupEnabler
{
    public static final byte[] POSS_DUP_FIELD = "43=Y\001".getBytes(US_ASCII);
    public static final String ORIG_SENDING_TIME_PREFIX_AS_STR = "122=";
    public static final byte[] ORIG_SENDING_TIME_PREFIX = ORIG_SENDING_TIME_PREFIX_AS_STR.getBytes(US_ASCII);

    private static final int CHECKSUM_VALUE_LENGTH = 3;
    public static final int FRAGMENTED_MESSAGE_BUFFER_OFFSET = 0;

    private final ExpandableArrayBuffer fragmentedMessageBuffer = new ExpandableArrayBuffer();
    private final PossDupFinder possDupFinder = new PossDupFinder();
    private final OtfParser parser = new OtfParser(possDupFinder, new IntDictionary());
    private final MutableAsciiBuffer mutableAsciiFlyweight = new MutableAsciiBuffer();
    private final UtcTimestampEncoder utcTimestampEncoder = new UtcTimestampEncoder();

    private final ExclusiveBufferClaim bufferClaim;
    private final IntPredicate claimer;
    private final PreCommit onPreCommit;
    private final Consumer<String> onIllegalStateFunc;
    private final ErrorHandler errorHandler;
    private final EpochClock clock;
    private final int maxPayloadLength;

    private int fragmentedMessageLength;

    public PossDupEnabler(
        final ExclusiveBufferClaim bufferClaim,
        final IntPredicate claimer,
        final PreCommit onPreCommit,
        final Consumer<String> onIllegalStateFunc,
        final ErrorHandler errorHandler,
        final EpochClock clock,
        final int maxPayloadLength)
    {
        this.bufferClaim = bufferClaim;
        this.claimer = claimer;
        this.onPreCommit = onPreCommit;
        this.onIllegalStateFunc = onIllegalStateFunc;
        this.errorHandler = errorHandler;
        this.clock = clock;
        this.maxPayloadLength = maxPayloadLength;
    }

    public Action enablePossDupFlag(
        final DirectBuffer srcBuffer,
        final int messageOffset,
        final int messageLength,
        final int srcOffset,
        final int srcLength)
    {
        parser.onMessage(srcBuffer, messageOffset, messageLength);
        final int possDupSrcOffset = possDupFinder.possDupOffset();
        if (possDupSrcOffset == NO_ENTRY)
        {
            final int lengthOfOldBodyLength = possDupFinder.lengthOfBodyLength();
            final int lengthOfAddedFields = POSS_DUP_FIELD.length +
                ORIG_SENDING_TIME_PREFIX.length +
                possDupFinder.sendingTimeLength() +
                SEPARATOR_LENGTH;
            int newLength = srcLength + lengthOfAddedFields;
            final int newBodyLength = possDupFinder.bodyLength() + lengthOfAddedFields;
            final int lengthOfNewBodyLength = MutableAsciiBuffer.lengthInAscii(newBodyLength);
            // Account for having to resize the body length field
            // Might be smaller due to padding
            final int lengthDelta = Math.max(0, lengthOfNewBodyLength - lengthOfOldBodyLength);
            newLength += lengthDelta;

            if (!claim(newLength))
            {
                return ABORT;
            }

            try
            {
                if (addFields(
                    srcBuffer,
                    srcOffset,
                    srcLength,
                    messageOffset,
                    messageLength,
                    writeBuffer(),
                    writeOffset(),
                    lengthDelta + lengthOfAddedFields,
                    newBodyLength,
                    newLength))
                {
                    commit();
                }
                else
                {
                    onIllegalStateFunc.accept("[%s] Missing sending time field in resend request");
                    abort();
                }
            }
            catch (final Exception e)
            {
                abort();
                errorHandler.onError(e);
            }
        }
        else
        {
            if (!claim(srcLength))
            {
                return ABORT;
            }

            try
            {
                final MutableDirectBuffer claimedBuffer = writeBuffer();
                final int claimOffset = writeOffset();
                claimedBuffer.putBytes(claimOffset, srcBuffer, messageOffset, messageLength);
                setPossDupFlag(possDupSrcOffset, messageOffset, claimOffset, claimedBuffer);
                updateSendingTime(messageOffset);

                commit();
            }
            catch (Exception e)
            {
                abort();
                errorHandler.onError(e);
            }
        }

        return CONTINUE;
    }

    private void abort()
    {
        if (isProcessingFragmentedMessage())
        {
            fragmentedMessageLength = 0;
        }
        else
        {
            bufferClaim.abort();
        }
    }

    private boolean claim(final int newLength)
    {
        if (newLength > maxPayloadLength)
        {
            fragmentedMessageBuffer.checkLimit(newLength);
            fragmentedMessageLength = newLength;
            return true;
        }
        else
        {
            return claimer.test(newLength);
        }
    }

    private void commit()
    {
        if (isProcessingFragmentedMessage())
        {
            int fragmentOffset = FRAGMENTED_MESSAGE_BUFFER_OFFSET;
            onPreCommit.onPreCommit(fragmentedMessageBuffer, fragmentOffset);

            DebugLogger.log(
                CATCHUP,
                "Resending: %s%n",
                fragmentedMessageBuffer,
                fragmentOffset + FRAME_LENGTH,
                fragmentedMessageLength - FRAME_LENGTH);

            while (fragmentedMessageLength > 0)
            {
                final int fragmentLength = Math.min(maxPayloadLength, fragmentedMessageLength);
                if (claimer.test(fragmentLength))
                {
                    if (fragmentOffset == FRAGMENTED_MESSAGE_BUFFER_OFFSET)
                    {
                        bufferClaim.flags((byte) BEGIN_FLAG);
                    }
                    else if (fragmentedMessageLength == fragmentLength)
                    {
                        bufferClaim.flags((byte) END_FLAG);
                    }
                    else
                    {
                        bufferClaim.flags((byte) 0);
                    }

                    final MutableDirectBuffer destBuffer = bufferClaim.buffer();
                    final int destOffset = bufferClaim.offset();

                    destBuffer.putBytes(destOffset, fragmentedMessageBuffer, fragmentOffset, fragmentLength);
                    bufferClaim.commit();

                    fragmentOffset += fragmentLength;
                    fragmentedMessageLength -= fragmentLength;
                }
                else
                {
                    // TODO: ABORT
                }
            }
        }
        else
        {
            final MutableDirectBuffer buffer = bufferClaim.buffer();
            final int offset = bufferClaim.offset();

            DebugLogger.log(
                CATCHUP,
                "Resending: %s%n",
                buffer,
                offset + FRAME_LENGTH,
                bufferClaim.length() - FRAME_LENGTH);

            onPreCommit.onPreCommit(buffer, offset);
            bufferClaim.commit();
        }
    }

    private MutableDirectBuffer writeBuffer()
    {
        return isProcessingFragmentedMessage() ? fragmentedMessageBuffer : bufferClaim.buffer();
    }

    private int writeOffset()
    {
        return isProcessingFragmentedMessage() ? FRAGMENTED_MESSAGE_BUFFER_OFFSET : bufferClaim.offset();
    }

    private boolean addFields(
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength,
        final int messageOffset,
        final int messageLength,
        final MutableDirectBuffer claimBuffer,
        final int claimOffset,
        final int totalLengthDelta,
        final int newBodyLength,
        final int newLength)
    {
        // Sending time is a required field just before the poss dup field
        final int sendingTimeSrcEnd = possDupFinder.sendingTimeEnd();
        if (sendingTimeSrcEnd == NO_ENTRY)
        {
            return false;
        }

        // Put messages up to the end of sending time
        final int lengthToPossDup = sendingTimeSrcEnd - srcOffset;
        claimBuffer.putBytes(claimOffset, srcBuffer, srcOffset, lengthToPossDup);

        // Insert Poss Dup Field
        final int possDupClaimOffset = claimOffset + lengthToPossDup;
        claimBuffer.putBytes(possDupClaimOffset, POSS_DUP_FIELD);

        // Insert Orig Sending Time Field
        final int origSendingTimePrefixClaimOffset = possDupClaimOffset + POSS_DUP_FIELD.length;
        claimBuffer.putBytes(origSendingTimePrefixClaimOffset, ORIG_SENDING_TIME_PREFIX);

        final int origSendingTimeValueClaimOffset = origSendingTimePrefixClaimOffset + ORIG_SENDING_TIME_PREFIX.length;
        final int sendingTimeOffset = possDupFinder.sendingTimeOffset();
        final int sendingTimeLength = possDupFinder.sendingTimeLength();
        claimBuffer.putBytes(origSendingTimeValueClaimOffset, srcBuffer, sendingTimeOffset, sendingTimeLength);

        final int separatorClaimOffset = origSendingTimeValueClaimOffset + sendingTimeLength;
        claimBuffer.putByte(separatorClaimOffset, SEPARATOR);

        // Insert the rest of the message
        final int remainingClaimOffset = separatorClaimOffset + SEPARATOR_LENGTH;
        final int remainingLength = srcLength - lengthToPossDup;
        claimBuffer.putBytes(remainingClaimOffset, srcBuffer, sendingTimeSrcEnd, remainingLength);

        // Update the sending time
        updateSendingTime(srcOffset);

        updateFrameBodyLength(messageLength, claimBuffer, claimOffset, totalLengthDelta);
        final int messageClaimOffset = srcToClaim(messageOffset, srcOffset, claimOffset);
        updateBodyLengthAndChecksum(
            srcOffset, messageClaimOffset, claimBuffer, claimOffset, newBodyLength, claimOffset + newLength);

        return true;
    }

    private void updateSendingTime(final int srcOffset)
    {
        final MutableDirectBuffer claimBuffer = writeBuffer();
        final int claimOffset = writeOffset();
        final int sendingTimeOffset = possDupFinder.sendingTimeOffset();
        final int sendingTimeLength = possDupFinder.sendingTimeLength();

        final int sendingTimeClaimOffset = srcToClaim(sendingTimeOffset, srcOffset, claimOffset);
        utcTimestampEncoder.encode(clock.time());
        claimBuffer.putBytes(sendingTimeClaimOffset, utcTimestampEncoder.buffer(), 0, sendingTimeLength);
    }

    private void updateFrameBodyLength(
        final int messageLength, final MutableDirectBuffer claimBuffer, final int claimOffset, final int lengthDelta)
    {
        final int frameBodyLengthOffset =
            claimOffset + MessageHeaderDecoder.ENCODED_LENGTH + FixMessageDecoder.BLOCK_LENGTH;
        final short frameBodyLength = (short) (messageLength + lengthDelta);
        claimBuffer.putShort(frameBodyLengthOffset, frameBodyLength, LITTLE_ENDIAN);
    }

    private void updateBodyLengthAndChecksum(
        final int srcOffset,
        final int messageClaimOffset,
        final MutableDirectBuffer claimBuffer,
        final int claimOffset,
        final int newBodyLength,
        final int messageEndOffset)
    {
        mutableAsciiFlyweight.wrap(claimBuffer);

        // BEGIN Update body length
        final int bodyLengthClaimOffset = srcToClaim(possDupFinder.bodyLengthOffset(), srcOffset, claimOffset);
        final int lengthOfOldBodyLength = possDupFinder.lengthOfBodyLength();
        final int lengthOfNewBodyLength = MutableAsciiBuffer.lengthInAscii(newBodyLength);

        final int lengthChange = lengthOfNewBodyLength - lengthOfOldBodyLength;
        if (lengthChange > 0)
        {
            final int index = bodyLengthClaimOffset + lengthChange;
            mutableAsciiFlyweight.putBytes(
                index,
                mutableAsciiFlyweight,
                bodyLengthClaimOffset,
                messageEndOffset - index);
        }
        // Max to avoid special casing the prefixing of the field with zeros
        final int lengthOfUpdatedBodyLengthField = Math.max(lengthOfOldBodyLength, lengthOfNewBodyLength);
        mutableAsciiFlyweight.putNatural(
            bodyLengthClaimOffset, lengthOfUpdatedBodyLengthField, newBodyLength);
        // END Update body length

        final int beforeChecksum =
            bodyLengthClaimOffset + lengthOfUpdatedBodyLengthField + newBodyLength;
        updateChecksum(messageClaimOffset, beforeChecksum, messageEndOffset);
    }

    private void updateChecksum(final int messageClaimOffset, final int beforeChecksum, final int messageEndOffset)
    {
        final int lengthOfSeparator = 1;
        final int checksumEnd = beforeChecksum + lengthOfSeparator;
        final int checksum = mutableAsciiFlyweight.computeChecksum(messageClaimOffset, checksumEnd);
        final int checksumValueOffset = messageEndOffset - (CHECKSUM_VALUE_LENGTH + SEPARATOR_LENGTH);
        mutableAsciiFlyweight.putNatural(checksumValueOffset, CHECKSUM_VALUE_LENGTH, checksum);
        mutableAsciiFlyweight.putSeparator(checksumValueOffset + CHECKSUM_VALUE_LENGTH);
    }

    private void setPossDupFlag(
        final int possDupSrcOffset,
        final int messageOffset,
        final int claimOffset,
        final MutableDirectBuffer claimBuffer)
    {
        final int possDupClaimOffset = srcToClaim(possDupSrcOffset, messageOffset, claimOffset);
        mutableAsciiFlyweight.wrap(claimBuffer);
        mutableAsciiFlyweight.putChar(possDupClaimOffset, 'Y');
    }

    private int srcToClaim(final int srcIndexedOffset, final int srcOffset, final int claimOffset)
    {
        return srcIndexedOffset - srcOffset + claimOffset;
    }

    private boolean isProcessingFragmentedMessage()
    {
        return fragmentedMessageLength > 0;
    }

    public interface PreCommit
    {
        void onPreCommit(MutableDirectBuffer buffer, int offset);
    }
}
