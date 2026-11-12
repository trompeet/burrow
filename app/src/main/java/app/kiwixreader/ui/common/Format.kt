package app.kiwixreader.ui.common

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import java.text.NumberFormat
import java.util.Locale

object Format {
    fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun count(value: Int): String = NumberFormat.getIntegerInstance().format(value)

    /** ZIM/OPDS languages are ISO 639-3 codes, possibly comma separated ("eng" or "eng,fra"). */
    fun language(codes: String): String = codes.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(", ") { code ->
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).takeIf { it.isNotBlank() && it != code } ?: code
        }

    fun date(context: Context, millis: Long): String =
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

    fun joined(vararg parts: String?) = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")
}
