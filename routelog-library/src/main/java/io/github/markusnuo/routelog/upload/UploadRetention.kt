package io.github.markusnuo.routelog.upload

import java.util.concurrent.TimeUnit

/**
 * How long the route of an uploaded recording stays on the phone. A setting of the phone
 * ([io.github.markusnuo.routelog.Routelog.setUploadRetention]), not part of a link or an upload
 * request. Only the points are deleted: the recording stays in the list (date, duration, distance,
 * label, "uploaded") until it is deleted. Recordings without an upload are not affected.
 *
 * A route is deleted right after its upload if it is due by then, otherwise when the setting
 * changes or by the maintenance that runs daily and at app start.
 */
enum class UploadRetention(private val keepMillis: Long?) {
    /** Delete the points as soon as the upload succeeded (the default). */
    DELETE_RIGHT_AWAY(0),

    /** Keep the points for 7 days after the upload. */
    KEEP_7_DAYS(TimeUnit.DAYS.toMillis(7)),

    /** Keep the points for 30 days after the upload. */
    KEEP_30_DAYS(TimeUnit.DAYS.toMillis(30)),

    /** Keep the points until the user deletes the recording. */
    KEEP(null),
    ;

    /** Uploads finished at or before this instant are due for deletion; null if nothing is. */
    fun cutoff(nowMillis: Long): Long? = keepMillis?.let { nowMillis - it }

    companion object {
        val DEFAULT = DELETE_RIGHT_AWAY

        fun fromName(name: String?): UploadRetention = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
