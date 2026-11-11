package com.carrun.caragent.brain.jev

import com.carrun.caragent.device.PortalNode
import com.carrun.caragent.device.PortalState
import com.carrun.caragent.device.PortalTools

data class JevBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun area(): Int = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
}

data class JevElement(
    val id: String,
    val text: String?,
    val label: String?,
    val hint: String?,
    val bounds: JevBounds,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val selected: Boolean,
    val password: Boolean,
)

data class JevPhone(
    val packageName: String?,
    val isEditable: Boolean,
    val inputElementId: String?,
)

data class JevObservation(
    val phone: JevPhone,
    val elements: List<JevElement>,
)

data class InstalledApp(
    val packageName: String,
    val label: String,
)

object JevObservationFactory {
    fun fromPortal(state: PortalState): JevObservation {
        val elements = state.nodes.mapNotNull { it.toJevElement() }
        val focused = elements.firstOrNull { it.focused && it.editable }
            ?: elements.firstOrNull { it.editable && it.focused }
        val anyEditableFocused = elements.any { it.enabled && it.editable && it.focused }
        return JevObservation(
            phone = JevPhone(
                packageName = state.packageName,
                isEditable = anyEditableFocused || elements.any { it.enabled && it.editable },
                inputElementId = focused?.id,
            ),
            elements = elements,
        )
    }

    private fun PortalNode.toJevElement(): JevElement? {
        val rect = PortalTools.boundsRect(bounds) ?: return null
        val label = contentDescription?.takeIf { it.isNotBlank() }
        return JevElement(
            id = "n$index",
            text = text,
            label = label,
            hint = hint,
            bounds = JevBounds(rect[0], rect[1], rect[2], rect[3]),
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            checkable = checkable,
            checked = checked,
            enabled = enabled,
            focused = focused,
            selected = selected,
            password = password,
        )
    }
}

fun textCandidatesFromGoal(goal: String, extra: List<String> = emptyList()): List<String> {
    val quoted = Regex("[\"“”']([^\"“”']+)[\"“”']").findAll(goal).map { it.groupValues[1].trim() }.toList()
    val extras = extra.map { it.trim() }.filter { it.isNotBlank() }
    val merged = (quoted + extras).distinct().filter { it.length in 1..120 }
    return merged.ifEmpty {
        // Fall back to short trailing tokens when no quotes — still exact spans only.
        goal.split(Regex("[\\s,，。！？；;]+"))
            .map { it.trim() }
            .filter { it.length in 2..40 }
            .distinct()
            .take(6)
    }
}
