package org.nahoft.nahoft.models

/**
 * Represents the status of a WSPR spot in the context of Nahoft message processing.
 *
 * Regular WSPR spots use [Spotted].
 * Nahoft message parts track their group, position, and processing state.
 */
sealed class NahoftSpotStatus
{

    /**
     * Regular WSPR spot that was decoded successfully.
     * No further processing — this is a standard WSPR beacon, not a Nahoft message.
     */
    object Spotted : NahoftSpotStatus()

    /**
     * Nahoft spot that is part of a message still being accumulated.
     *
     * @param groupId Unique identifier tying spots in the same message together
     * @param partNumber 1-based position of this spot within the message (1, 2, 3...)
     */
    data class Pending(
        val groupId: Int,
        val partNumber: Int
    ) : NahoftSpotStatus()

    /**
     * Nahoft spot that was part of a successfully decrypted message.
     *
     * @param groupId Unique identifier tying spots in the same message together
     * @param partNumber 1-based position of this spot within the message
     * @param totalParts Total number of parts in the completed message
     */
    data class Decrypted(
        val groupId: Int,
        val partNumber: Int,
        val totalParts: Int
    ) : NahoftSpotStatus()

    /**
     * Nahoft spot that could not be successfully processed.
     *
     * @param groupId Unique identifier tying spots in the same message together
     * @param partNumber 1-based position of this spot within the message
     * @param reason Why processing failed
     */
    data class Failed(
        val groupId: Int,
        val partNumber: Int,
        val reason: FailureReason
    ) : NahoftSpotStatus()
}

/**
 * Reasons why a Nahoft spot could not be processed successfully.
 */
enum class FailureReason
{
    /** Could not parse WSPR fields into a valid Nahoft message structure */
    PARSE_ERROR,

    /** Decryption failed (wrong key, corrupted data, or message integrity check failed) */
    DECRYPTION_ERROR,

    /** Session ended before all message parts were received */
    INCOMPLETE
}

/**
 * Position of a spot within a visual group (for rendering connector lines).
 */
enum class GroupPosition
{
    /** Not part of a group (regular WSPR spot) */
    NONE,
    /** First item in a group (top bracket) */
    FIRST,
    /** Middle item in a group (vertical line) */
    MIDDLE,
    /** Last item in a group (bottom bracket) */
    LAST,
    /** Only item in group so far (single bracket, may grow) */
    SINGLE
}

/**
 * A single WSPR spot as displayed in the spots list.
 *
 * Contains both the raw WSPR decode data and Nahoft-specific tracking info.
 */
data class WSPRSpotItem(
    /** Decoded callsign (e.g., "N5HIM" or "Q0ABC" for Nahoft) */
    val callsign: String,

    /** Maidenhead grid square (e.g., "EM89") */
    val gridSquare: String,

    /** Transmitted power level in dBm */
    val powerDbm: Int,

    /** Signal-to-noise ratio in dB */
    val snrDb: Float,

    /** Timestamp when this spot was decoded (System.currentTimeMillis()) */
    val timestamp: Long,

    /** Processing status for this spot */
    val nahoftStatus: NahoftSpotStatus,

    /**
     * Visual grouping position, computed when building the display list.
     * Determines which connector lines/brackets to draw.
     */
    val groupPosition: GroupPosition = GroupPosition.NONE
) {

    /**
     * Returns true if this is a Nahoft message spot (not a regular WSPR spot).
     */
    val isNahoftSpot: Boolean
        get() = nahoftStatus !is NahoftSpotStatus.Spotted

    /**
     * Returns true if this spot represents a failed Nahoft message part.
     */
    val isFailed: Boolean
        get() = nahoftStatus is NahoftSpotStatus.Failed

    /**
     * Returns display text for the part number annotation.
     *
     * Examples:
     * - Regular spot: null
     * - Pending part 2: "[2/?]"
     * - Decrypted part 2 of 3: "[2/3]"
     * - Failed part 2: "[2/?]"
     */
    val partNumberDisplay: String?
        get() = when (val status = nahoftStatus) {
            is NahoftSpotStatus.Spotted -> null
            is NahoftSpotStatus.Pending -> "[${status.partNumber}/?]"
            is NahoftSpotStatus.Decrypted -> "[${status.partNumber}/${status.totalParts}]"
            is NahoftSpotStatus.Failed -> "[${status.partNumber}/?]"
        }

    /**
     * Returns display text for the status annotation.
     *
     * Examples:
     * - Regular spot: null
     * - Pending: "Pending..."
     * - Decrypted: "✓ Decrypted"
     * - Failed: "✗ Parse error" / "✗ Decryption failed" / "✗ Incomplete"
     */
    val statusDisplay: String?
        get() = when (val status = nahoftStatus) {
            is NahoftSpotStatus.Spotted -> null
            is NahoftSpotStatus.Pending -> "Pending..."
            is NahoftSpotStatus.Decrypted -> "✓ Decrypted"
            is NahoftSpotStatus.Failed -> when (status.reason) {
                FailureReason.PARSE_ERROR -> "✗ Parse error"
                FailureReason.DECRYPTION_ERROR -> "✗ Decryption failed"
                FailureReason.INCOMPLETE -> "✗ Incomplete"
            }
        }
}