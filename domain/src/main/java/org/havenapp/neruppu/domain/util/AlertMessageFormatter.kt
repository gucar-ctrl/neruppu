/*
 * Copyright (C) 2026 thamizh-root
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.havenapp.neruppu.domain.util

import org.havenapp.neruppu.domain.model.AlertPayload
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AlertMessageFormatter {
    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss 'del' dd/MM/yyyy")
        .withZone(ZoneId.systemDefault())

    fun format(payload: AlertPayload): String {
        val time = TIME_FORMATTER.format(Instant.ofEpochMilli(payload.timestamp))
        return "\uD83D\uDEA8 Faro: ${payload.message}, alle $time.\nSe è un'intrusione, chiama il 112."
    }
}
