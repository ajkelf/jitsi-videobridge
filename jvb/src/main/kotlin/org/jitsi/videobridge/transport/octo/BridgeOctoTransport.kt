/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.videobridge.transport.octo

import org.jitsi.nlj.PacketHandler
import org.jitsi.rtp.Packet
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.MediaType
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.octo.OctoPacket
import org.jitsi.videobridge.octo.OctoPacket.OCTO_HEADER_LENGTH
import org.jitsi.videobridge.octo.OctoPacketInfo
import org.jitsi.videobridge.transport.octo.OctoUtils.Companion.JVB_EP_ID
import org.jitsi.videobridge.util.ByteBufferPool
import java.net.SocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.math.floor
import kotlin.math.log10

/**
 * [BridgeOctoTransport] handles *all* incoming and outgoing Octo traffic for a
 * given bridge.
 *
 * [relayId] is the Octo relay ID which will be advertised by this JVB.
 * Other bridges can use this ID in order to discover the socket address that
 * this bridge is accessible on.  With the current implementation the ID just
 * encodes a pre-configured IP address and port, e.g. "10.0.0.1:20000"
 */
class BridgeOctoTransport(
    val relayId: String,
    parentLogger: Logger
) {
    private val logger = createChildLogger(parentLogger, mapOf("relayId" to relayId))

    private val stats = Stats(logger)

    /**
     * Handlers for incoming Octo packets.  Packets will be routed to a handler based on the conference
     * ID in the Octo packet.
     */
    private val incomingPacketHandlers: MutableMap<Long, IncomingOctoPacketHandler> = ConcurrentHashMap()

    /**
     * Maps how many Octo packets have been received for unknown conference IDs, to avoid
     * spamming the error logs
     */
    private val unknownConferences: MutableMap<Long, AtomicLong> = ConcurrentHashMap()

    /**
     * The handler which will be invoked when this [BridgeOctoTransport] wants to
     * send data.
     */
    var outgoingDataHandler: OutgoingOctoPacketHandler? = null

    init {
        logger.info("Created OctoTransport")
    }

    /**
     * Registers a [PacketHandler] for the given [conferenceId]
     */
    fun addHandler(conferenceId: Long, handler: IncomingOctoPacketHandler) {
        if (conferenceId < 0 || conferenceId > 0xffff_ffff) {
            throw IllegalArgumentException("Invalid conference ID: $conferenceId")
        }
        logger.info("Adding handler for conference $conferenceId")

        synchronized(incomingPacketHandlers) {
            incomingPacketHandlers.put(conferenceId, handler)?.let {
                logger.warn("Replacing an existing packet handler for gid=$conferenceId")
            }
            unknownConferences.remove(conferenceId)
        }
    }

    /**
     * Removes the [PacketHandler] for the given [conferenceId] IFF the one
     * in the map is the same as [handler].
     */
    fun removeHandler(conferenceId: Long, handler: IncomingOctoPacketHandler) = synchronized(incomingPacketHandlers) {
        // If the Colibri conference for this GID was re-created, and the
        // original Conference object is expired after a new packet handler
        // was registered, the new packet handler should not be removed (as
        // this would break the new conference).
        incomingPacketHandlers[conferenceId]?.let {
            if (it == handler) {
                logger.info("Removing handler for conference $conferenceId")
                incomingPacketHandlers.remove(conferenceId)
            } else {
                logger.info(
                    "Tried to remove handler for conference $conferenceId but it wasn't the currently " +
                        "active one"
                )
            }
        }
    }

    fun stop() {
    }

    @Suppress("DEPRECATION")
    fun dataReceived(buf: ByteArray, off: Int, len: Int, receivedTime: Instant) {
        var conferenceId: Long
        var mediaType: MediaType
        var sourceEpId: String

        try {
            conferenceId = OctoPacket.readConferenceId(buf, off, len)
            mediaType = OctoPacket.readMediaType(buf, off, len)
            sourceEpId = OctoPacket.readEndpointId(buf, off, len)
        } catch (iae: IllegalArgumentException) {
            logger.warn("Invalid Octo packet, len=$len", iae)
            stats.invalidPacketReceived()
            return
        }

        val handler = incomingPacketHandlers[conferenceId] ?: run {
            stats.noHandlerFound()
            unknownConferences[conferenceId]?.let { unknownConfEventAdder ->
                val value = unknownConfEventAdder.incrementAndGet()
                // Only print log on exact powers of 10 packets received
                val logValue = log10(value.toFloat())
                if (logValue > 0 && logValue == floor(logValue)) {
                    logger.warn("Received $value Octo packets for unknown conference $conferenceId")
                }
            } ?: run {
                /* Potentially a race here if more than one packet arrives at once
                 * for the same unknown conference? This will just result in a few
                 * duplicate logs, though, so not a big deal.
                 */
                unknownConferences[conferenceId] = AtomicLong(1)
                logger.warn("Received an Octo packet for an unknown conference: $conferenceId")
            }
            return
        }
        when (mediaType) {
            MediaType.AUDIO, MediaType.VIDEO -> {
                handler.handleMediaPacket(createPacketInfo(sourceEpId, buf, off, len, receivedTime))
            }
            MediaType.DATA -> {
                handler.handleMessagePacket(createMessageString(buf, off, len), sourceEpId)
            }
            else -> {
                logger.warn("Unsupported media type $mediaType")
                stats.invalidPacketReceived()
            }
        }
    }

    fun sendMediaData(
        buf: ByteArray,
        off: Int,
        len: Int,
        targets: Collection<SocketAddress>,
        confId: Long,
        sourceEpId: String? = null
    ) {
        sendData(buf, off, len, targets, confId, MediaType.VIDEO, sourceEpId)
    }

    @Suppress("DEPRECATION")
    fun sendString(msg: String, targets: Collection<SocketAddress>, confId: Long) {
        val msgData = msg.toByteArray(StandardCharsets.UTF_8)
        sendData(msgData, 0, msgData.size, targets, confId, MediaType.DATA, null)
    }

    private fun sendData(
        buf: ByteArray,
        off: Int,
        len: Int,
        targets: Collection<SocketAddress>,
        confId: Long,
        mediaType: MediaType,
        sourceEpId: String? = null
    ) {
        val octoPacketLength = len + OCTO_HEADER_LENGTH

        // Not all packets originate from an endpoint (e.g. some come from the bridge)
        val epId = sourceEpId ?: JVB_EP_ID

        val (newBuf, newOff) = when {
            off >= OCTO_HEADER_LENGTH -> {
                // We can fit the Octo header into the room left at the beginning of the packet
                Pair(buf, off - OCTO_HEADER_LENGTH)
            }
            buf.size >= octoPacketLength -> {
                // The buffer is big enough to hold the Octo header as well, but we
                // need to shift it
                System.arraycopy(buf, off, buf, OCTO_HEADER_LENGTH, len)
                Pair(buf, 0)
            }
            else -> {
                // The buffer isn't big enough, we need a new one
                val newBuf = ByteBufferPool.getBuffer(octoPacketLength).apply {
                    System.arraycopy(buf, off, this, OCTO_HEADER_LENGTH, len)
                }
                Pair(newBuf, 0)
            }
        }
        OctoPacket.writeHeaders(
            newBuf, newOff,
            mediaType,
            confId,
            epId
        )
        if (octoPacketLength > 1500) {
            stats.largePacketSent(mediaType)
        }
        outgoingDataHandler?.sendData(newBuf, newOff, octoPacketLength, targets) ?: stats.noOutgoingHandler()
    }

    fun getStatsJson(): OrderedJsonObject = OrderedJsonObject().apply {
        put("relay_id", relayId)
        putAll(getStats().toJson())
    }

    fun getStats(): StatsSnapshot = stats.toSnapshot()

    private fun createPacketInfo(
        sourceEpId: String,
        buf: ByteArray,
        off: Int,
        len: Int,
        receivedTime: Instant
    ): OctoPacketInfo {
        val rtpLen = len - OCTO_HEADER_LENGTH
        val bufCopy = ByteBufferPool.getBuffer(
            rtpLen + RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET + Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET
        ).apply {
            System.arraycopy(
                buf, off + OCTO_HEADER_LENGTH,
                this, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET,
                rtpLen
            )
        }
        return OctoPacketInfo(UnparsedPacket(bufCopy, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, rtpLen)).apply {
            this.endpointId = sourceEpId
            this.receivedTime = receivedTime
        }
    }

    private fun createMessageString(buf: ByteArray, off: Int, len: Int): String {
        return String(
            buf,
            off + OCTO_HEADER_LENGTH,
            len - OCTO_HEADER_LENGTH
        ).also {
            logger.cdebug { "Received a message in an Octo data packet: $it" }
        }
    }

    private class Stats(val logger: Logger) {
        private val numInvalidPackets = LongAdder()
        private val numIncomingDroppedNoHandler = LongAdder()
        private val numOutgoingDroppedNoHandler = LongAdder()
        private val largePacketsSent = HashMap<MediaType, AtomicLong>()

        fun invalidPacketReceived() {
            numInvalidPackets.increment()
        }

        fun noHandlerFound() {
            numIncomingDroppedNoHandler.increment()
        }

        fun noOutgoingHandler() {
            numOutgoingDroppedNoHandler.increment()
        }

        fun largePacketSent(mediaType: MediaType) {
            val value = largePacketsSent.computeIfAbsent(mediaType) { AtomicLong() }.incrementAndGet()
            if (value == 1L || value % 1000 == 0L) {
                logger.warn("Sent $value large (>1500B) packets with type $mediaType")
            }
        }

        fun toSnapshot(): StatsSnapshot = StatsSnapshot(
            numInvalidPackets = numInvalidPackets.sum(),
            numIncomingDroppedNoHandler = numIncomingDroppedNoHandler.sum(),
            numOutgoingDroppedNoHandler = numOutgoingDroppedNoHandler.sum(),
            numLargeAudioPacketsSent = largePacketsSent[MediaType.AUDIO]?.get() ?: 0,
            numLargeVideoPacketsSent = largePacketsSent[MediaType.VIDEO]?.get() ?: 0,
            numLargeDataPacketsSent = largePacketsSent[MediaType.DATA]?.get() ?: 0
        )
    }

    class StatsSnapshot(
        val numInvalidPackets: Long,
        val numIncomingDroppedNoHandler: Long,
        val numOutgoingDroppedNoHandler: Long,
        val numLargeAudioPacketsSent: Long,
        val numLargeVideoPacketsSent: Long,
        val numLargeDataPacketsSent: Long
    ) {
        fun toJson(): OrderedJsonObject = OrderedJsonObject().apply {
            put("num_invalid_packets_rx", numInvalidPackets)
            put("num_incoming_packets_dropped_no_handler", numIncomingDroppedNoHandler)
            put("num_outgoing_packets_dropped_no_handler", numOutgoingDroppedNoHandler)
            put("num_large_audio_packets_sent", numLargeAudioPacketsSent)
            put("num_large_video_packets_sent", numLargeVideoPacketsSent)
            put("num_large_data_packets_sent", numLargeDataPacketsSent)
        }
    }

    /**
     * Handler for when Octo packets are received
     */
    interface IncomingOctoPacketHandler {
        /**
         * Notify that an Octo media packet has been received.  The handler
         * *does* own the packet buffer inside the [OctoPacketInfo]
         */
        fun handleMediaPacket(packetInfo: OctoPacketInfo)

        /**
         * Notify that a message packet has been received from remote endpoint
         * [sourceEpId]
         */
        fun handleMessagePacket(message: String, sourceEpId: String)
    }

    interface OutgoingOctoPacketHandler {
        fun sendData(data: ByteArray, off: Int, length: Int, remoteAddresses: Collection<SocketAddress>)
    }
}
