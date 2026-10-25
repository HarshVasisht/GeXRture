package com.gexrture.glasses

/**
 * Known XREAL device identifiers.
 *
 * Sources: XRLinuxDriver v2.11.3 xreal.c (2026-10-25).
 * ⚠️ UNVERIFIED on physical hardware — these are upstream-reported values.
 * The owner's actual USB descriptors have not been captured.
 */
object XrealDeviceIds {
    const val VENDOR_ID = 0x3318 // 13080 decimal

    /** Map of USB product ID → human-readable model name. */
    val PRODUCT_IDS: Map<Int, String> = mapOf(
        0x0424 to "XREAL Air",
        0x0428 to "XREAL Air 2",
        0x0432 to "XREAL Air 2 Pro",
        0x0426 to "XREAL Air 2 Ultra",
        0x0435 to "XREAL One Pro",
        0x0436 to "XREAL One Pro (variant 2)",
        0x0437 to "XREAL One",
        0x0438 to "XREAL One (variant 2)",
        0x043e to "XREAL One S",
        0x043d to "XREAL One S (variant 2)",
        0x0440 to "XREAL xbx a01",
        0x0442 to "XREAL xbx a01+",
    )

    /** The target product IDs for this project. */
    val XBX_PRODUCT_IDS = setOf(0x0440, 0x0442)

    /** Diagonal FOV in degrees, by product ID. */
    val FOV_DEGREES: Map<Int, Float> = mapOf(
        0x0424 to 45.0f,
        0x0428 to 45.0f,
        0x0432 to 45.0f,
        0x0426 to 52.0f,
        0x0435 to 57.0f,
        0x0436 to 57.0f,
        0x0437 to 50.0f,
        0x0438 to 50.0f,
        0x043e to 52.0f,
        0x043d to 52.0f,
        0x0440 to 50.0f,
        0x0442 to 50.0f,
    )

    fun isXrealDevice(vendorId: Int): Boolean = vendorId == VENDOR_ID
    fun isXbxDevice(vendorId: Int, productId: Int): Boolean =
        vendorId == VENDOR_ID && productId in XBX_PRODUCT_IDS

    fun modelName(vendorId: Int, productId: Int): String =
        if (vendorId == VENDOR_ID) {
            PRODUCT_IDS[productId] ?: "XREAL Unknown (pid=0x${productId.toString(16)})"
        } else {
            "Non-XREAL (vid=0x${vendorId.toString(16)}, pid=0x${productId.toString(16)})"
        }
}
