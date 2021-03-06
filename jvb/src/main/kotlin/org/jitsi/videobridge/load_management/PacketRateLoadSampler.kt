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

package org.jitsi.videobridge.load_management

import org.jitsi.videobridge.Videobridge

class PacketRateLoadSampler(
    private val videobridge: Videobridge,
    private val newMeasurementHandler: (PacketRateMeasurement) -> Unit
) : Runnable {

    override fun run() {
        var totalPacketRate: Long = 0
        videobridge.conferences.forEach { conf ->
            conf.localEndpoints.forEach { ep ->
                with(ep.transceiver.getTransceiverStats()) {
                    totalPacketRate += rtpReceiverStats.packetStreamStats.packetRate
                    totalPacketRate += outgoingPacketStreamStats.packetRate
                }
            }
            conf.relays.forEach { relay ->
                totalPacketRate += relay.incomingPacketRate
                totalPacketRate += relay.outgoingPacketRate
            }
        }
        newMeasurementHandler(PacketRateMeasurement(totalPacketRate))
    }
}
