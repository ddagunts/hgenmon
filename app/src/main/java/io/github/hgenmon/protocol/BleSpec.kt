package io.github.hgenmon.protocol

import java.util.UUID

object BleSpec {

    object Service {
        val GENERATOR_DATA      = uuid("01B60001-875A-4C56-B8BF-5103CAFAEEC7")
        val REMOTE_CONTROL      = uuid("066B0001-5D90-4939-A7BA-7B9222F53E81")
        val BT_UNIT_CONTROL     = uuid("92CD0001-4F59-4599-A73C-C92C4AC7AADE")
        val DIAGNOSTIC_CONTROL  = uuid("B4EF0001-62D2-483C-8293-119E2A99A82B")
    }

    object Char {
        // Generator Data Service
        val GENERATOR_DATA_REQUEST    = uuid("01B60002-875A-4C56-B8BF-5103CAFAEEC7")
        val GENERATOR_DATA_RESPONSE   = uuid("01B60003-875A-4C56-B8BF-5103CAFAEEC7")
        val CAN_DATA_DRIP             = uuid("01B60004-875A-4C56-B8BF-5103CAFAEEC7")
        val ERROR_AND_WARNING_INFO    = uuid("01B60005-875A-4C56-B8BF-5103CAFAEEC7")

        // Remote Control Service
        val ENGINE_CONTROL            = uuid("066B0002-5D90-4939-A7BA-7B9222F53E81")
        val ENGINE_DRIVE_STATUS       = uuid("066B0003-5D90-4939-A7BA-7B9222F53E81")
        val CONTROL_SEQUENCE_CONFIG   = uuid("066B0004-5D90-4939-A7BA-7B9222F53E81")
        val FRAME_NUMBER              = uuid("066B0005-5D90-4939-A7BA-7B9222F53E81")
        val UNLOCK_PROTECT            = uuid("066B0006-5D90-4939-A7BA-7B9222F53E81")
        val CHANGE_PASSWORD           = uuid("066B0007-5D90-4939-A7BA-7B9222F53E81")

        // BT Unit Control Service
        val BT_UNIT_UNLOCK_PROTECT    = uuid("92CD0002-4F59-4599-A73C-C92C4AC7AADE")
        val BT_UNIT_CHANGE_PASSWORD   = uuid("92CD0003-4F59-4599-A73C-C92C4AC7AADE")
        val BT_UNIT_UNIT_OPERATION    = uuid("92CD0004-4F59-4599-A73C-C92C4AC7AADE")
        val BT_FIRMWARE_VERSION       = uuid("92CD0005-4F59-4599-A73C-C92C4AC7AADE")
        val CAN_FIRMWARE_VERSION      = uuid("92CD0006-4F59-4599-A73C-C92C4AC7AADE")
        val BT_UNIT_FRAME_NUMBER      = uuid("92CD0007-4F59-4599-A73C-C92C4AC7AADE")

        // Diagnostic Control Service — the primary command/response channel
        val DIAGNOSTIC_COMMAND        = uuid("B4EF0002-62D2-483C-8293-119E2A99A82B")
        val DIAGNOSTIC_RESPONSE       = uuid("B4EF0003-62D2-483C-8293-119E2A99A82B")
        val FIRMWARE_VERSION          = uuid("B4EF0004-62D2-483C-8293-119E2A99A82B")
        val UNIT_OPERATION            = uuid("B4EF0005-62D2-483C-8293-119E2A99A82B")
    }

    /** Standard Bluetooth Client Characteristic Configuration Descriptor (CCCD). */
    val CCCD: UUID = uuid("00002902-0000-1000-8000-00805f9b34fb")

    /** Default unlock password — any all-zero password normalizes to this. */
    const val DEFAULT_PASSWORD = "00000000"
}

private fun uuid(s: String): UUID = UUID.fromString(s)
