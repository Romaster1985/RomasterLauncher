package com.romaster.launcher.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Visual animation / reaction system for IMG/GIF widgets.
 * Flow: TRIGGER → (AND/OR conditions) → DO actions / assigns.
 */

enum class ReactionBlockType {
    TRIGGER,
    CONDITION,
    ACTION,
    ASSIGN,
    /** Visual / logical connector row (AND / OR / DO). */
    CONNECTOR
}

enum class ConnectorKind {
    AND,
    OR,
    DO
}

enum class TriggerKind {
    TAP,
    DOUBLE_TAP,
    LONG_PRESS,
    SWIPE_UP,
    SWIPE_DOWN,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    /** Fires when a shared global bool equals the configured value (typically true). */
    GLOBAL_BOOL
}

enum class ConditionKind {
    GLOBAL_BOOL_EQ,
    GLOBAL_BOOL_NEQ,
    PAGE_EQ,
    PAGE_GTE,
    PAGE_LTE,
    BATTERY_GTE,
    ALWAYS_TRUE
}

enum class ActionKind {
    TRANSLATE_X,
    TRANSLATE_Y,
    ROTATE,
    SCALE,
    ALPHA,
    HUE_ROLL,
    RESET_TRANSFORM,
    OPEN_APP,
    /** Open a specific app shortcut (deep link / pinned shortcut). */
    LAUNCH_SHORTCUT
}

enum class AssignKind {
    GLOBAL_BOOL,
    /** Flip current value of a global bool (true↔false). */
    TOGGLE_GLOBAL_BOOL,
    GLOBAL_INT,
    GLOBAL_FLOAT
}

data class ReactionBlock(
    var id: String = java.util.UUID.randomUUID().toString().take(8),
    var type: ReactionBlockType = ReactionBlockType.TRIGGER,
    /** Connector */
    var connectorKind: ConnectorKind = ConnectorKind.DO,
    /** Trigger */
    var triggerKind: TriggerKind = TriggerKind.TAP,
    var triggerCount: Int = 1,
    /** Condition */
    var conditionKind: ConditionKind = ConditionKind.ALWAYS_TRUE,
    var conditionGlobalKey: String = "",
    var conditionBoolValue: Boolean = false,
    var conditionNumber: Float = 0f,
    /** Action */
    var actionKind: ActionKind = ActionKind.TRANSLATE_X,
    var actionValue: Float = 0f,
    var actionSpeed: Float = 600f,
    var actionAccel: Float = 0f,
    var actionDurationMs: Long = 0L,
    /** Hue-roll / general loop: -1 = infinite, >=1 = N times */
    var actionLoopCount: Int = 1,
    var actionPackage: String = "",
    var actionActivity: String = "",
    /** Shortcut id for LAUNCH_SHORTCUT */
    var actionShortcutId: String = "",
    var actionLabel: String = "",
    /** Assign */
    var assignKind: AssignKind = AssignKind.GLOBAL_BOOL,
    var assignKey: String = "",
    var assignBoolValue: Boolean = true,
    var assignNumber: Float = 0f
) {
    fun titleLine(): String = when (type) {
        ReactionBlockType.TRIGGER ->
            if (triggerKind == TriggerKind.GLOBAL_BOOL) "Trigger Global bool" else "Touch Trigger"
        ReactionBlockType.CONDITION -> "G Condition"
        ReactionBlockType.ACTION -> when (actionKind) {
            ActionKind.TRANSLATE_X -> "Translate x"
            ActionKind.TRANSLATE_Y -> "Translate y"
            ActionKind.ROTATE -> "Rotate"
            ActionKind.SCALE -> "Scale"
            ActionKind.ALPHA -> "Fade"
            ActionKind.HUE_ROLL -> "Hue roll"
            ActionKind.RESET_TRANSFORM -> "Reset transform"
            ActionKind.OPEN_APP -> "Open app"
            ActionKind.LAUNCH_SHORTCUT -> "Launch action"
        }
        ReactionBlockType.ASSIGN -> when (assignKind) {
            AssignKind.TOGGLE_GLOBAL_BOOL -> "G Switch"
            else -> "G Assign"
        }
        ReactionBlockType.CONNECTOR -> connectorKind.name
    }

    fun detailLine(): String = when (type) {
        ReactionBlockType.TRIGGER -> {
            if (triggerKind == TriggerKind.GLOBAL_BOOL) {
                val key = conditionGlobalKey.ifBlank { "?" }
                "$key == $conditionBoolValue"
            } else when (triggerKind) {
                TriggerKind.TAP -> "Tap"
                TriggerKind.DOUBLE_TAP -> "Double Tap"
                TriggerKind.LONG_PRESS -> "Long Press"
                TriggerKind.SWIPE_UP -> "Swipe Up"
                TriggerKind.SWIPE_DOWN -> "Swipe Down"
                TriggerKind.SWIPE_LEFT -> "Swipe Left"
                TriggerKind.SWIPE_RIGHT -> "Swipe Right"
                TriggerKind.GLOBAL_BOOL -> "Global"
            }
        }
        ReactionBlockType.CONDITION -> when (conditionKind) {
            ConditionKind.ALWAYS_TRUE -> "Always"
            ConditionKind.GLOBAL_BOOL_EQ -> "${conditionGlobalKey.ifBlank { "?" }}==$conditionBoolValue"
            ConditionKind.GLOBAL_BOOL_NEQ -> "${conditionGlobalKey.ifBlank { "?" }}!=$conditionBoolValue"
            ConditionKind.PAGE_EQ -> "Page == ${conditionNumber.toInt()}"
            ConditionKind.PAGE_GTE -> "Page >= ${conditionNumber.toInt()}"
            ConditionKind.PAGE_LTE -> "Page <= ${conditionNumber.toInt()}"
            ConditionKind.BATTERY_GTE -> "Battery >= ${conditionNumber.toInt()}%"
        }
        ReactionBlockType.ACTION -> when (actionKind) {
            ActionKind.TRANSLATE_X ->
                "X: ${actionValue.toInt()} | V: ${actionSpeed.toInt()} px/s | A: ${actionAccel.toInt()}"
            ActionKind.TRANSLATE_Y ->
                "Y: ${actionValue.toInt()} | V: ${actionSpeed.toInt()} px/s | A: ${actionAccel.toInt()}"
            ActionKind.ROTATE ->
                "${actionValue.toInt()}° | V: ${actionSpeed.toInt()} °/s | A: ${actionAccel.toInt()}"
            ActionKind.SCALE ->
                "×$actionValue | V: $actionSpeed /s | A: $actionAccel"
            ActionKind.ALPHA ->
                "α $actionValue | V: $actionSpeed /s | A: $actionAccel"
            ActionKind.HUE_ROLL -> {
                val loops = if (actionLoopCount < 0) "∞" else actionLoopCount.toString()
                "×$loops | V: ${actionSpeed.toInt()} °/s | A: ${actionAccel.toInt()}"
            }
            ActionKind.RESET_TRANSFORM ->
                "Reset | V: ${actionSpeed.toInt()} | A: ${actionAccel.toInt()}"
            ActionKind.OPEN_APP -> actionLabel.ifBlank { actionPackage.ifBlank { "app" } }
            ActionKind.LAUNCH_SHORTCUT -> actionLabel.ifBlank {
                "${actionPackage.ifBlank { "app" }} / ${actionShortcutId.ifBlank { "shortcut" }}"
            }
        }
        ReactionBlockType.ASSIGN -> when (assignKind) {
            AssignKind.GLOBAL_BOOL -> "${assignKey.ifBlank { "?" }} = $assignBoolValue"
            AssignKind.TOGGLE_GLOBAL_BOOL -> "toggle ${assignKey.ifBlank { "?" }}"
            AssignKind.GLOBAL_INT -> "${assignKey.ifBlank { "?" }}: ${assignNumber.toInt()}"
            AssignKind.GLOBAL_FLOAT -> "${assignKey.ifBlank { "?" }}: $assignNumber"
        }
        ReactionBlockType.CONNECTOR -> connectorKind.name
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("connectorKind", connectorKind.name)
        put("triggerKind", triggerKind.name)
        put("triggerCount", triggerCount)
        put("conditionKind", conditionKind.name)
        put("conditionGlobalKey", conditionGlobalKey)
        put("conditionBoolValue", conditionBoolValue)
        put("conditionNumber", conditionNumber.toDouble())
        put("actionKind", actionKind.name)
        put("actionValue", actionValue.toDouble())
        put("actionSpeed", actionSpeed.toDouble())
        put("actionAccel", actionAccel.toDouble())
        put("actionDurationMs", actionDurationMs)
        put("actionLoopCount", actionLoopCount)
        put("actionPackage", actionPackage)
        put("actionActivity", actionActivity)
        put("actionShortcutId", actionShortcutId)
        put("actionLabel", actionLabel)
        put("assignKind", assignKind.name)
        put("assignKey", assignKey)
        put("assignBoolValue", assignBoolValue)
        put("assignNumber", assignNumber.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): ReactionBlock = ReactionBlock(
            id = o.optString("id", java.util.UUID.randomUUID().toString().take(8)),
            type = runCatching { ReactionBlockType.valueOf(o.optString("type", "TRIGGER")) }
                .getOrDefault(ReactionBlockType.TRIGGER),
            connectorKind = runCatching { ConnectorKind.valueOf(o.optString("connectorKind", "DO")) }
                .getOrDefault(ConnectorKind.DO),
            triggerKind = runCatching { TriggerKind.valueOf(o.optString("triggerKind", "TAP")) }
                .getOrDefault(TriggerKind.TAP),
            triggerCount = o.optInt("triggerCount", 1),
            conditionKind = runCatching { ConditionKind.valueOf(o.optString("conditionKind", "ALWAYS_TRUE")) }
                .getOrDefault(ConditionKind.ALWAYS_TRUE),
            conditionGlobalKey = o.optString("conditionGlobalKey", ""),
            conditionBoolValue = o.optBoolean("conditionBoolValue", false),
            conditionNumber = o.optDouble("conditionNumber", 0.0).toFloat(),
            actionKind = runCatching { ActionKind.valueOf(o.optString("actionKind", "TRANSLATE_X")) }
                .getOrDefault(ActionKind.TRANSLATE_X),
            actionValue = o.optDouble("actionValue", 0.0).toFloat(),
            actionSpeed = o.optDouble("actionSpeed", 80.0).toFloat(),
            actionAccel = o.optDouble("actionAccel", 0.0).toFloat(),
            actionDurationMs = o.optLong("actionDurationMs", 400L),
            actionLoopCount = o.optInt("actionLoopCount", 1),
            actionPackage = o.optString("actionPackage", ""),
            actionActivity = o.optString("actionActivity", ""),
            actionShortcutId = o.optString("actionShortcutId", ""),
            actionLabel = o.optString("actionLabel", ""),
            assignKind = runCatching { AssignKind.valueOf(o.optString("assignKind", "GLOBAL_BOOL")) }
                .getOrDefault(AssignKind.GLOBAL_BOOL),
            assignKey = o.optString("assignKey", ""),
            assignBoolValue = o.optBoolean("assignBoolValue", true),
            assignNumber = o.optDouble("assignNumber", 0.0).toFloat()
        )
    }
}

data class ReactionRecipe(
    var id: String = java.util.UUID.randomUUID().toString().take(8),
    var name: String = "Reaction",
    var enabled: Boolean = true,
    var blocks: MutableList<ReactionBlock> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        val arr = JSONArray()
        blocks.forEach { arr.put(it.toJson()) }
        put("blocks", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): ReactionRecipe {
            val blocks = mutableListOf<ReactionBlock>()
            val arr = o.optJSONArray("blocks")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    blocks.add(ReactionBlock.fromJson(arr.getJSONObject(i)))
                }
            }
            return ReactionRecipe(
                id = o.optString("id", java.util.UUID.randomUUID().toString().take(8)),
                name = o.optString("name", "Reaction"),
                enabled = o.optBoolean("enabled", true),
                blocks = blocks
            )
        }

        fun exampleTapTranslate(): ReactionRecipe = ReactionRecipe(
            name = "Tap slide",
            blocks = mutableListOf(
                ReactionBlock(
                    type = ReactionBlockType.TRIGGER,
                    triggerKind = TriggerKind.TAP,
                    triggerCount = 1
                ),
                ReactionBlock(
                    type = ReactionBlockType.CONNECTOR,
                    connectorKind = ConnectorKind.AND
                ),
                ReactionBlock(
                    type = ReactionBlockType.CONDITION,
                    conditionKind = ConditionKind.GLOBAL_BOOL_EQ,
                    conditionGlobalKey = "Anim2",
                    conditionBoolValue = false
                ),
                ReactionBlock(
                    type = ReactionBlockType.CONNECTOR,
                    connectorKind = ConnectorKind.DO
                ),
                ReactionBlock(
                    type = ReactionBlockType.ACTION,
                    actionKind = ActionKind.TRANSLATE_X,
                    actionValue = -600f,
                    actionSpeed = 600f,
                    actionAccel = 0f,
                    actionDurationMs = 0L
                ),
                ReactionBlock(
                    type = ReactionBlockType.CONNECTOR,
                    connectorKind = ConnectorKind.DO
                ),
                ReactionBlock(
                    type = ReactionBlockType.ASSIGN,
                    assignKind = AssignKind.GLOBAL_BOOL,
                    assignKey = "Anim1",
                    assignBoolValue = true
                )
            )
        )
    }
}

object ReactionJson {
    fun encodeList(list: List<ReactionRecipe>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun decodeList(json: String?): MutableList<ReactionRecipe> {
        if (json.isNullOrBlank()) return mutableListOf()
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<ReactionRecipe>()
            for (i in 0 until arr.length()) {
                out.add(ReactionRecipe.fromJson(arr.getJSONObject(i)))
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }
}
