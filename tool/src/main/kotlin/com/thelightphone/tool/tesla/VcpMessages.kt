package com.thelightphone.tool.tesla

/**
 * Tesla Vehicle Command Protocol message builders.
 * Constructs protobuf-encoded messages for vehicle commands
 * using the manual encoder (no protobuf dependency needed).
 *
 * Field numbers match Tesla's .proto definitions exactly.
 */
object VcpMessages {

    // ── Domain routing ──────────────────────────────────
    const val DOMAIN_VEHICLE_SECURITY = 2
    const val DOMAIN_INFOTAINMENT = 3

    // ── Flags ───────────────────────────────────────────
    const val FLAG_ENCRYPT_RESPONSE = 1

    // ── RKE Actions (vehicle security domain) ───────────
    private const val RKE_ACTION_UNLOCK = 0
    private const val RKE_ACTION_LOCK = 1
    private const val RKE_ACTION_REMOTE_DRIVE = 20
    private const val RKE_ACTION_WAKE = 30

    // ── Closure move types ──────────────────────────────
    private const val CLOSURE_MOVE_OPEN = 3
    private const val CLOSURE_MOVE_CLOSE = 4

    // ── RoutableMessage field numbers ───────────────────
    private const val RM_TO_DESTINATION = 6
    private const val RM_FROM_DESTINATION = 7
    private const val RM_PAYLOAD_BYTES = 10
    private const val RM_SESSION_INFO_REQUEST = 14
    private const val RM_SIGNED_MESSAGE_STATUS = 12
    private const val RM_SIGNATURE_DATA = 13
    private const val RM_REQUEST_UUID = 50
    private const val RM_UUID = 51
    private const val RM_FLAGS = 52

    // ── Destination field numbers ───────────────────────
    private const val DEST_DOMAIN = 1
    private const val DEST_ROUTING_ADDRESS = 2

    // ── SessionInfoRequest field numbers ────────────────
    private const val SIR_PUBLIC_KEY = 1
    private const val SIR_CHALLENGE = 2

    // ── SignatureData field numbers ─────────────────────
    private const val SD_SIGNER_IDENTITY = 1
    private const val SD_HMAC_DATA = 8

    // ── KeyIdentity field numbers ──────────────────────
    private const val KI_PUBLIC_KEY = 1

    // ── HMAC_Personalized_Signature_Data field numbers ──
    private const val HMAC_EPOCH = 1
    private const val HMAC_COUNTER = 2
    private const val HMAC_EXPIRES_AT = 3
    private const val HMAC_TAG = 4

    // ── VCSEC.UnsignedMessage field numbers ─────────────
    private const val UM_RKE_ACTION = 2
    private const val UM_CLOSURE_MOVE_REQUEST = 4

    // ── ClosureMoveRequest field numbers ────────────────
    private const val CMR_REAR_TRUNK = 5
    private const val CMR_FRONT_TRUNK = 6
    private const val CMR_CHARGE_PORT = 7

    // ── CarServer.Action field numbers ──────────────────
    private const val ACTION_VEHICLE_ACTION = 2

    // ── VehicleAction oneof field numbers ────────────────
    private const val VA_HVAC_AUTO = 10
    private const val VA_HVAC_PRECONDITION_MAX = 12
    private const val VA_FLASH_LIGHTS = 26
    private const val VA_HONK_HORN = 27
    private const val VA_SET_SENTRY = 30
    private const val VA_WINDOW_CONTROL = 34
    private const val VA_SET_OVERHEAT = 50
    private const val VA_CHARGE_PORT_CLOSE = 61
    private const val VA_CHARGE_PORT_OPEN = 62

    // ── Sub-message field numbers ───────────────────────
    private const val HVAC_POWER_ON = 1
    private const val SENTRY_ON = 1
    private const val OVERHEAT_ON = 1
    private const val OVERHEAT_FAN_ONLY = 2
    // WindowAction: vent = 3, close = 4 (oneof, type Void = empty message)
    private const val WINDOW_VENT = 3
    private const val WINDOW_CLOSE = 4

    // ══════════════════════════════════════════════════════
    //  Public API — build command payloads
    // ══════════════════════════════════════════════════════

    /** Returns (domain, payload) for each Tesla command. */
    fun buildCommandPayload(command: TeslaCommand): Pair<Int, ByteArray> {
        return when (command) {
            // ── Vehicle Security domain ──
            TeslaCommand.Lock -> DOMAIN_VEHICLE_SECURITY to vcsecRkeAction(RKE_ACTION_LOCK)
            TeslaCommand.Unlock -> DOMAIN_VEHICLE_SECURITY to vcsecRkeAction(RKE_ACTION_UNLOCK)
            TeslaCommand.RemoteStart -> DOMAIN_VEHICLE_SECURITY to vcsecRkeAction(RKE_ACTION_REMOTE_DRIVE)

            TeslaCommand.OpenTrunk -> DOMAIN_VEHICLE_SECURITY to vcsecClosureMove(rearTrunk = CLOSURE_MOVE_OPEN)
            TeslaCommand.CloseTrunk -> DOMAIN_VEHICLE_SECURITY to vcsecClosureMove(rearTrunk = CLOSURE_MOVE_CLOSE)
            TeslaCommand.OpenFrunk -> DOMAIN_VEHICLE_SECURITY to vcsecClosureMove(frontTrunk = CLOSURE_MOVE_OPEN)
            TeslaCommand.ChargePortOpen -> DOMAIN_VEHICLE_SECURITY to vcsecClosureMove(chargePort = CLOSURE_MOVE_OPEN)
            TeslaCommand.ChargePortClose -> DOMAIN_VEHICLE_SECURITY to vcsecClosureMove(chargePort = CLOSURE_MOVE_CLOSE)

            // ── Infotainment domain ──
            TeslaCommand.ClimateOn -> DOMAIN_INFOTAINMENT to carServerAction {
                writeMessage(VA_HVAC_AUTO) { writeBool(HVAC_POWER_ON, true) }
            }
            TeslaCommand.ClimateOff -> DOMAIN_INFOTAINMENT to carServerAction {
                // power_on defaults to false, but we need the message present
                writeMessage(VA_HVAC_AUTO) { writeVarintForce(HVAC_POWER_ON, 0) }
            }

            TeslaCommand.DefrostOn -> DOMAIN_INFOTAINMENT to carServerAction {
                writeMessage(VA_HVAC_PRECONDITION_MAX) { writeBool(HVAC_POWER_ON, true) }
            }
            TeslaCommand.DefrostOff -> DOMAIN_INFOTAINMENT to carServerAction {
                writeMessage(VA_HVAC_PRECONDITION_MAX) { writeVarintForce(HVAC_POWER_ON, 0) }
            }

            TeslaCommand.OverheatOff -> DOMAIN_INFOTAINMENT to carServerAction {
                writeMessage(VA_SET_OVERHEAT) { writeVarintForce(OVERHEAT_ON, 0) }
            }
            TeslaCommand.OverheatFan -> DOMAIN_INFOTAINMENT to carServerAction {
                writeMessage(VA_SET_OVERHEAT) {
                    writeBool(OVERHEAT_ON, true)
                    writeBool(OVERHEAT_FAN_ONLY, true)
                }
            }
            TeslaCommand.OverheatAC -> DOMAIN_INFOTAINMENT to carServerAction {
                writeMessage(VA_SET_OVERHEAT) {
                    writeBool(OVERHEAT_ON, true)
                    // fan_only = false (default), so AC is used
                }
            }

            TeslaCommand.SentryOn -> DOMAIN_INFOTAINMENT to carServerAction {
                writeMessage(VA_SET_SENTRY) { writeBool(SENTRY_ON, true) }
            }
            TeslaCommand.SentryOff -> DOMAIN_INFOTAINMENT to carServerAction {
                writeMessage(VA_SET_SENTRY) { writeVarintForce(SENTRY_ON, 0) }
            }

            TeslaCommand.FlashLights -> DOMAIN_INFOTAINMENT to carServerAction {
                writeEmptyMessage(VA_FLASH_LIGHTS)
            }
            TeslaCommand.HonkHorn -> DOMAIN_INFOTAINMENT to carServerAction {
                writeEmptyMessage(VA_HONK_HORN)
            }

            TeslaCommand.VentWindows -> DOMAIN_INFOTAINMENT to carServerAction {
                writeMessage(VA_WINDOW_CONTROL) {
                    writeEmptyMessage(WINDOW_VENT) // Void = zero-length message
                }
            }
            TeslaCommand.CloseWindows -> DOMAIN_INFOTAINMENT to carServerAction {
                writeMessage(VA_WINDOW_CONTROL) {
                    writeEmptyMessage(WINDOW_CLOSE) // Void = zero-length message
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  RoutableMessage envelope
    // ══════════════════════════════════════════════════════

    /** Build a SessionInfoRequest wrapped in a RoutableMessage. */
    fun buildSessionInfoRequest(
        domain: Int,
        publicKey: ByteArray,
        uuid: ByteArray,
    ): ByteArray = protobuf {
        writeMessage(RM_TO_DESTINATION) {
            writeVarint(DEST_DOMAIN, domain)
        }
        writeMessage(RM_FROM_DESTINATION) {
            writeBytes(DEST_ROUTING_ADDRESS, uuid)
        }
        writeMessage(RM_SESSION_INFO_REQUEST) {
            writeBytes(SIR_PUBLIC_KEY, publicKey)
        }
        writeBytes(RM_UUID, uuid)
    }

    /** Wrap a signed command payload in a RoutableMessage. */
    fun buildSignedRoutableMessage(
        domain: Int,
        payload: ByteArray,
        signerPublicKey: ByteArray,
        epoch: ByteArray,
        counter: Int,
        expiresAt: Int,
        hmacTag: ByteArray,
        uuid: ByteArray,
    ): ByteArray = protobuf {
        writeMessage(RM_TO_DESTINATION) {
            writeVarint(DEST_DOMAIN, domain)
        }
        writeMessage(RM_FROM_DESTINATION) {
            writeBytes(DEST_ROUTING_ADDRESS, uuid)
        }
        writeBytes(RM_PAYLOAD_BYTES, payload)
        writeMessage(RM_SIGNATURE_DATA) {
            writeMessage(SD_SIGNER_IDENTITY) {
                writeBytes(KI_PUBLIC_KEY, signerPublicKey)
            }
            writeMessage(SD_HMAC_DATA) {
                writeBytes(HMAC_EPOCH, epoch)
                writeUint32(HMAC_COUNTER, counter)
                writeFixed32(HMAC_EXPIRES_AT, expiresAt)
                writeBytes(HMAC_TAG, hmacTag)
            }
        }
        writeBytes(RM_UUID, uuid)
        writeUint32(RM_FLAGS, FLAG_ENCRYPT_RESPONSE)
    }

    // ══════════════════════════════════════════════════════
    //  Parse session info response
    // ══════════════════════════════════════════════════════

    data class SessionInfoData(
        val counter: Int,
        val publicKey: ByteArray,
        val epoch: ByteArray,
        val clockTime: Int,
        val status: Int, // 0=OK, 1=KEY_NOT_ON_WHITELIST
        val handle: Int,
    )

    /** Parse SessionInfo from a RoutableMessage response. */
    fun parseSessionInfoResponse(responseBytes: ByteArray): SessionInfoData? {
        val rmFields = ProtobufDecoder(responseBytes).readAllFields()
        // session_info is field 15 (bytes — serialized SessionInfo)
        val sessionInfoBytes = rmFields[15] as? ByteArray ?: return null
        val siFields = ProtobufDecoder(sessionInfoBytes).readAllFields()

        return SessionInfoData(
            counter = (siFields[1] as? Number)?.toInt() ?: 0,
            publicKey = siFields[2] as? ByteArray ?: return null,
            epoch = siFields[3] as? ByteArray ?: return null,
            clockTime = (siFields[4] as? Number)?.toInt() ?: 0,  // fixed32 → Int, not Long
            status = (siFields[5] as? Number)?.toInt() ?: 0,     // Session_Info_Status
            handle = (siFields[6] as? Number)?.toInt() ?: 0,
        )
    }

    // ══════════════════════════════════════════════════════
    //  Internal builders
    // ══════════════════════════════════════════════════════

    /** Build a VCSEC.UnsignedMessage with an RKE action. */
    private fun vcsecRkeAction(action: Int): ByteArray = protobuf {
        if (action == 0) {
            writeVarintForce(UM_RKE_ACTION, action) // UNLOCK = 0
        } else {
            writeVarint(UM_RKE_ACTION, action)
        }
    }

    /** Build a VCSEC.UnsignedMessage with a ClosureMoveRequest. */
    private fun vcsecClosureMove(
        rearTrunk: Int = 0,
        frontTrunk: Int = 0,
        chargePort: Int = 0,
    ): ByteArray = protobuf {
        writeMessage(UM_CLOSURE_MOVE_REQUEST) {
            if (rearTrunk != 0) writeVarint(CMR_REAR_TRUNK, rearTrunk)
            if (frontTrunk != 0) writeVarint(CMR_FRONT_TRUNK, frontTrunk)
            if (chargePort != 0) writeVarint(CMR_CHARGE_PORT, chargePort)
        }
    }

    /** Build a CarServer.Action wrapping a VehicleAction. */
    private fun carServerAction(vehicleAction: ProtobufEncoder.() -> Unit): ByteArray = protobuf {
        writeMessage(ACTION_VEHICLE_ACTION) {
            vehicleAction()
        }
    }
}
