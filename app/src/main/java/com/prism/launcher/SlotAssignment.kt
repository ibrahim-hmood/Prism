package com.prism.launcher

import android.content.Context

enum class SlotIndex(val storageKey: String) {
    BROWSER("slot_browser"),
    DESKTOP("slot_desktop"),
    DRAWER("slot_drawer");

    companion object {
        fun fromHorizontalPage(page: Int): SlotIndex = entries[page.coerceIn(0, 2)]
    }
}

sealed class SlotAssignment {
    data object Default : SlotAssignment()
    data object Messaging : SlotAssignment()
    data object KineticHalo : SlotAssignment()
    data class Custom(val packageName: String, val viewClassName: String) : SlotAssignment()

    fun serialize(): String = when (this) {
        is Default -> "default"
        is Messaging -> "messaging"
        is KineticHalo -> "halo"
        is Custom -> "custom|$packageName|$viewClassName"
    }

    companion object {
        fun deserialize(raw: String?): SlotAssignment {
            if (raw.isNullOrBlank() || raw == "default") return Default
            if (raw == "messaging") return Messaging
            if (raw == "halo") return KineticHalo
            val parts = raw.split("|")
            if (parts.size == 3 && parts[0] == "custom") {
                return Custom(parts[1], parts[2])
            }
            return Default
        }
    }
}

class SlotPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("prism_slots", Context.MODE_PRIVATE)

    fun get(slot: SlotIndex): SlotAssignment =
        SlotAssignment.deserialize(prefs.getString(slot.storageKey, null))

    fun set(slot: SlotIndex, assignment: SlotAssignment) {
        prefs.edit().putString(slot.storageKey, assignment.serialize()).commit()
    }

    fun assignmentsInOrder(): List<SlotAssignment> =
        listOf(get(SlotIndex.BROWSER), get(SlotIndex.DESKTOP), get(SlotIndex.DRAWER))
}
