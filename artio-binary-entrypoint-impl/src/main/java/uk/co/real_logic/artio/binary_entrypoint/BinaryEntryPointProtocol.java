/*
 * Copyright 2021 Monotonic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.binary_entrypoint;

import io.aeron.ExclusivePublication;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.fixp.FixPConnection;
import uk.co.real_logic.artio.fixp.FixPProtocol;
import uk.co.real_logic.artio.library.InternalFixPConnection;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.protocol.GatewayPublication;

public class BinaryEntryPointProtocol extends FixPProtocol
{
    public BinaryEntryPointProtocol()
    {
        super(FixPProtocolType.BINARY_ENTRYPOINT);
    }

    public BinaryEntryPointParser makeParser(final FixPConnection connection)
    {
        return new BinaryEntryPointParser();
    }

    public BinaryEntryPointProxy makeProxy(
        final ExclusivePublication publication, final EpochNanoClock epochNanoClock)
    {
        return new BinaryEntryPointProxy();
    }

    public BinaryEntryPointOffsets makeOffsets()
    {
        return new BinaryEntryPointOffsets();
    }

    public InternalFixPConnection makeAcceptorConnection(
        final long connectionId,
        final GatewayPublication outboundPublication,
        final GatewayPublication inboundPublication,
        final int libraryId,
        final Object libraryPoller,
        final long lastReceivedSequenceNumber,
        final long lastSentSequenceNumber,
        final long lastConnectPayload,
        final DirectBuffer buffer,
        final int offset,
        final int messageLength,
        final EpochNanoClock epochNanoClock)
    {
        return new InternalBinaryEntrypointConnection();
    }
}
