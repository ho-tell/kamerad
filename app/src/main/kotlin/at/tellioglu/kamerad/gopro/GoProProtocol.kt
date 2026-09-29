package at.tellioglu.kamerad.gopro

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Open GoPro BLE protocol (https://gopro.github.io/OpenGoPro/ble).
 */
object GoProUuids {
    /** 16-bit service UUID advertised by GoPro cameras. */
    val ADVERTISED_SERVICE: UUID = UUID.fromString("0000fea6-0000-1000-8000-00805f9b34fb")

    private fun gp(id: String): UUID = UUID.fromString("b5f9$id-aa8d-11e3-9046-0002a5d5c51b")

    val COMMAND: UUID = gp("0072")
    val COMMAND_RESPONSE: UUID = gp("0073")
    val SETTING: UUID = gp("0074")
    val SETTING_RESPONSE: UUID = gp("0075")
    val QUERY: UUID = gp("0076")
    val QUERY_RESPONSE: UUID = gp("0077")

    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

object GoProCommands {
    const val SET_SHUTTER = 0x01
    const val SLEEP = 0x05
    const val GET_HARDWARE_INFO = 0x3C

    const val QUERY_GET_STATUS = 0x13
    const val QUERY_REGISTER_STATUS = 0x53
    const val QUERY_STATUS_PUSH = 0x93

    fun shutter(on: Boolean): ByteArray = byteArrayOf(0x03, SET_SHUTTER.toByte(), 0x01, if (on) 0x01 else 0x00)

    val GET_HARDWARE_INFO_COMMAND: ByteArray = byteArrayOf(0x01, GET_HARDWARE_INFO.toByte())

    /** Puts the camera to sleep (off); it keeps advertising and wakes up when a client connects. */
    val SLEEP_COMMAND: ByteArray = byteArrayOf(0x01, SLEEP.toByte())

    /** Setting 91 (LED) = 66 is the documented BLE keep-alive; must be sent every few seconds. */
    val KEEP_ALIVE: ByteArray = byteArrayOf(0x03, 0x5B, 0x01, 0x42)

    fun registerStatusUpdates(vararg statusIds: Int): ByteArray =
        byteArrayOf((statusIds.size + 1).toByte(), QUERY_REGISTER_STATUS.toByte()) +
            statusIds.map { it.toByte() }.toByteArray()
}

object GoProStatus {
    const val BUSY = 8
    const val ENCODING = 10
    const val VIDEO_DURATION = 13
    const val BATTERY_PERCENT = 70
}

/** A complete response message: `id`, `status` and the remaining payload. */
class TlvResponse(val id: Int, val status: Int, val payload: ByteArray) {
    /** Payload of a status/setting query parsed as `id -> value` pairs. */
    fun values(): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        var i = 0
        while (i + 2 <= payload.size) {
            val id = payload[i].toInt() and 0xFF
            val len = payload[i + 1].toInt() and 0xFF
            if (i + 2 + len > payload.size) break
            result[id] = payload.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
        }
        return result
    }

    companion object {
        fun parse(message: ByteArray): TlvResponse? =
            if (message.size < 2) {
                null
            } else {
                TlvResponse(message[0].toInt() and 0xFF, message[1].toInt() and 0xFF, message.copyOfRange(2, message.size))
            }
    }
}

/** Big-endian unsigned value of a status/setting value. */
fun ByteArray.toUnsignedInt(): Long = fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

/**
 * Reassembles BLE packets (general 5-bit, extended 13/16-bit headers and continuation packets)
 * into complete messages. One instance per response characteristic.
 */
class PacketAccumulator {
    private val buffer = ByteArrayOutputStream()
    private var remaining = 0

    /** Returns the complete message once all packets have arrived, otherwise null. */
    fun accept(packet: ByteArray): ByteArray? {
        if (packet.isEmpty()) return null
        val header = packet[0].toInt() and 0xFF
        val payloadStart: Int
        if (header and 0x80 != 0) {
            // Continuation packet
            if (remaining <= 0) return null
            payloadStart = 1
        } else {
            buffer.reset()
            when ((header and 0x60) shr 5) {
                0 -> {
                    remaining = header and 0x1F
                    payloadStart = 1
                }
                1 -> {
                    if (packet.size < 2) return null
                    remaining = ((header and 0x1F) shl 8) or (packet[1].toInt() and 0xFF)
                    payloadStart = 2
                }
                2 -> {
                    if (packet.size < 3) return null
                    remaining = ((packet[1].toInt() and 0xFF) shl 8) or (packet[2].toInt() and 0xFF)
                    payloadStart = 3
                }
                else -> return null
            }
        }
        val chunk = packet.copyOfRange(minOf(payloadStart, packet.size), packet.size)
        buffer.write(chunk)
        remaining -= chunk.size
        if (remaining > 0) return null
        remaining = 0
        return buffer.toByteArray()
    }
}

/** Model name (e.g. "HERO8 Black") from a Get Hardware Info response payload, or null if malformed. */
fun parseModelName(payload: ByteArray): String? {
    // Length-prefixed fields: model number, model name, ...
    val numberLength = payload.getOrNull(0)?.toInt()?.and(0xFF) ?: return null
    val nameAt = 1 + numberLength
    val nameLength = payload.getOrNull(nameAt)?.toInt()?.and(0xFF) ?: return null
    if (nameAt + 1 + nameLength > payload.size) return null
    return payload.copyOfRange(nameAt + 1, nameAt + 1 + nameLength).decodeToString().trim()
}

/**
 * Whether a sleeping camera of this model wakes up when a client connects over BLE.
 * Documented for Open GoPro cameras (HERO9 and newer); a HERO8 stays asleep. Unknown models: assume yes.
 */
fun canWakeOverBle(modelName: String?): Boolean {
    val generation = modelName?.let { Regex("""HERO(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return true
    return generation >= 9
}
