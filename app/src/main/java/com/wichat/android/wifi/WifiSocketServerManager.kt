package com.wichat.android.wifi

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.util.Log
import com.wichat.android.protocol.BitchatPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * Manages Wifi P2P server operations, listening for incoming connections.
 */
class WifiSocketServerManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: WifiConnectionTracker,
    private val permissionManager: WifiPermissionManager,
    private val delegate: WifiConnectionManagerDelegate?
) {

    companion object {
        private const val TAG = "WifiSocketServerManager"
        const val SERVER_PORT = 8888
    }

    private var serverSocket: ServerSocket? = null
    private var isActive = false

    fun start(): Boolean {
        if (!permissionManager.hasWifiPermissions()) {
            Log.e(TAG, "Missing Wifi permissions")
            return false
        }

        isActive = true
        connectionScope.launch {
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                Log.i(TAG, "Server: Socket opened on port $SERVER_PORT")

                while (isActive) {
                    try {
                        val clientSocket = serverSocket!!.accept() // Blocking call
                        Log.i(TAG, "Server: Accepted connection from ${clientSocket.inetAddress.hostAddress}")
                        handleNewConnection(clientSocket)
                    } catch (e: IOException) {
                        if (isActive) {
                            Log.e(TAG, "Server: Error accepting connection", e)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Server: Could not listen on port $SERVER_PORT", e)
            }
        }
        return true
    }

    fun stop() {
        isActive = false
        try {
            serverSocket?.close()
            Log.i(TAG, "Server: Socket closed")
        } catch (e: IOException) {
            Log.e(TAG, "Server: Error closing server socket", e)
        }
    }

    private fun handleNewConnection(socket: Socket) {
        connectionScope.launch {
            val deviceAddress = socket.inetAddress.hostAddress
            // We need a way to map the IP address to a WifiP2pDevice.
            // This is a significant challenge with Wi-Fi Direct.
            // For now, we'll create a placeholder device.
            val placeholderDevice = WifiP2pDevice().apply { this.deviceAddress = deviceAddress }

            val deviceConn = WifiConnectionTracker.DeviceConnection(
                device = placeholderDevice,
                socket = socket,
                isClient = false
            )
            connectionTracker.addDeviceConnection(deviceAddress, deviceConn)
            delegate?.onDeviceConnected(placeholderDevice)

            listenForPackets(socket, placeholderDevice)
        }
    }

    private fun listenForPackets(socket: Socket, device: WifiP2pDevice) {
        connectionScope.launch {
            try {
                val inputStream = socket.getInputStream()
                val buffer = ByteArray(1024)
                var bytes: Int

                while (isActive && socket.isConnected) {
                    bytes = inputStream.read(buffer)
                    if (bytes > 0) {
                        val receivedData = buffer.copyOf(bytes)
                        Log.i(TAG, "Server: Received packet from ${device.deviceAddress}, size: $bytes bytes")
                        val packet = BitchatPacket.fromBinaryData(receivedData)
                        if (packet != null) {
                            val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                            Log.d(TAG, "Server: Parsed packet type ${packet.type} from $peerID")
                            delegate?.onPacketReceived(packet, peerID, device)
                        } else {
                            Log.w(TAG, "Server: Failed to parse packet from ${device.deviceAddress}")
                        }
                    } else {
                        break
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Server: Error reading from socket for ${device.deviceAddress}", e)
            } finally {
                Log.i(TAG, "Server: Disconnected from ${device.deviceAddress}")
                connectionTracker.cleanupDeviceConnection(device.deviceAddress)
            }
        }
    }
    
    fun getSocketServer(): ServerSocket? = serverSocket
    
    fun getCharacteristic(): Any? = null

    fun restartAdvertising() {
        // Not applicable to Wi-Fi Direct in the same way as BLE
    }
}
