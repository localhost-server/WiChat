package com.wichat.android.wifi

import android.util.Log
import com.wichat.android.model.RoutedPacket
import com.wichat.android.protocol.SpecialRecipients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import java.io.IOException
import java.net.Socket

/**
 * Handles packet broadcasting to connected devices using an actor pattern for serialization.
 */
class WifiPacketBroadcaster(
    private val connectionScope: CoroutineScope,
    private val connectionTracker: WifiConnectionTracker,
    private val fragmentManager: FragmentManager?
) {

    companion object {
        private const val TAG = "WifiPacketBroadcaster"
    }

    private data class BroadcastRequest(val routed: RoutedPacket)

    private val broadcasterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @OptIn(kotlinx.coroutines.ObsoleteCoroutinesApi::class)
    private val broadcasterActor = broadcasterScope.actor<BroadcastRequest>(
        capacity = Channel.UNLIMITED
    ) {
        for (request in channel) {
            broadcastSinglePacketInternal(request.routed)
        }
    }

    fun broadcastPacket(routed: RoutedPacket) {
        val packet = routed.packet
        if (fragmentManager != null) {
            val fragments = fragmentManager.createFragments(packet)
            if (fragments.size > 1) {
                Log.d(TAG, "Fragmenting packet into ${fragments.size} fragments")
                connectionScope.launch {
                    fragments.forEach { fragment ->
                        broadcastSinglePacket(RoutedPacket(fragment))
                        delay(50) // Small delay between fragments
                    }
                }
                return
            }
        }
        broadcastSinglePacket(routed)
    }

    private fun broadcastSinglePacket(routed: RoutedPacket) {
        broadcasterScope.launch {
            try {
                broadcasterActor.send(BroadcastRequest(routed))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send broadcast request to actor: ${e.message}")
            }
        }
    }

    private suspend fun broadcastSinglePacketInternal(routed: RoutedPacket) {
        val packet = routed.packet
        val data = packet.toBinaryData() ?: return

        if (packet.recipientID != SpecialRecipients.BROADCAST) {
            val recipientID = packet.recipientID?.let { String(it).replace("\u0000", "").trim() } ?: ""
            val targetDeviceConn = connectionTracker.getConnectedDevices().values
                .firstOrNull { connectionTracker.addressPeerMap[it.device.deviceAddress] == recipientID }

            if (targetDeviceConn != null) {
                Log.d(TAG, "Sending packet type ${packet.type} directly to recipient $recipientID: ${targetDeviceConn.device.deviceAddress}")
                writeToSocket(targetDeviceConn, data)
                return
            }
        }

        val connectedDevices = connectionTracker.getConnectedDevices()
        Log.i(TAG, "Broadcasting packet type ${packet.type} to ${connectedDevices.size} connections")

        val senderID = String(packet.senderID).replace("\u0000", "")

        connectedDevices.values.forEach { deviceConn ->
            if (deviceConn.device.deviceAddress == routed.relayAddress) {
                Log.d(TAG, "Skipping broadcast back to relayer: ${deviceConn.device.deviceAddress}")
                return@forEach
            }
            if (connectionTracker.addressPeerMap[deviceConn.device.deviceAddress] == senderID) {
                Log.d(TAG, "Skipping broadcast back to sender: ${deviceConn.device.deviceAddress}")
                return@forEach
            }
            writeToSocket(deviceConn, data)
        }
    }

    private fun writeToSocket(deviceConn: WifiConnectionTracker.DeviceConnection, data: ByteArray): Boolean {
        return try {
            deviceConn.socket?.let {
                if (it.isConnected && !it.isClosed) {
                    it.getOutputStream().write(data)
                    it.getOutputStream().flush()
                    true
                } else {
                    false
                }
            } ?: false
        } catch (e: IOException) {
            Log.w(TAG, "Error sending to connection ${deviceConn.device.deviceAddress}: ${e.message}")
            connectionScope.launch {
                connectionTracker.cleanupDeviceConnection(deviceConn.device.deviceAddress)
            }
            false
        }
    }

    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Broadcaster Debug Info ===")
            appendLine("Broadcaster Scope Active: ${broadcasterScope.coroutineContext[Job]?.isActive ?: false}")
            appendLine("Actor Channel Closed: ${broadcasterActor.isClosedForSend}")
            appendLine("Connection Scope Active: ${connectionScope.coroutineContext[Job]?.isActive ?: false}")
        }
    }

    fun shutdown() {
        Log.d(TAG, "Shutting down WifiPacketBroadcaster actor")
        broadcasterActor.close()
        broadcasterScope.cancel()
        Log.d(TAG, "WifiPacketBroadcaster shutdown complete")
    }
}
