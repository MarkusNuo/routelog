package io.github.markusnuo.routelog.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

fun formatDateTime(epochMillis: Long): String =
    DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
