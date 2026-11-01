package com.romaster.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import com.romaster.launcher.data.ActionKind
import com.romaster.launcher.data.AssignKind
import com.romaster.launcher.data.ConditionKind
import com.romaster.launcher.data.ConnectorKind
import com.romaster.launcher.data.ReactionBlock
import com.romaster.launcher.data.ReactionBlockType
import com.romaster.launcher.data.ReactionGlobalsStore
import com.romaster.launcher.data.ReactionRecipe
import com.romaster.launcher.data.TriggerKind
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runtime for IMG/GIF widget reactions.
 *
 * Sequencing rules:
 * - A [ConnectorKind.DO] between two action/assign cards means sequential:
 *   the next card starts only after the previous stage finishes.
 * - Without a DO connector between action/assign cards, they share a stage and
 *   run in parallel; the following stage waits for all of them.
 */
class ReactionEngine {

    private val runningTargets: MutableSet<Int> =
        Collections.synchronizedSet(mutableSetOf())

    private var pendingAfterRun: MutableList<() -> Unit> = mutableListOf()

    fun isBusy(target: View): Boolean =
        runningTargets.contains(System.identityHashCode(target))

    fun clearAllRunning() {
        runningTargets.clear()
    }

    fun runWhenIdle(block: () -> Unit) {
        if (runningTargets.isEmpty()) {
            block()
        } else {
            synchronized(pendingAfterRun) { pendingAfterRun.add(block) }
        }
    }

    private fun flushPending() {
        if (runningTargets.isNotEmpty()) return
        val jobs: List<() -> Unit>
        synchronized(pendingAfterRun) {
            jobs = pendingAfterRun.toList()
            pendingAfterRun.clear()
        }
        jobs.forEach {
            try { it() } catch (_: Exception) {}
        }
    }

    fun handleTrigger(
        context: Context,
        recipes: List<ReactionRecipe>,
        trigger: TriggerKind,
        target: View,
        currentPage: Int,
        batteryPct: Int,
        openApp: (packageName: String, activityName: String) -> Unit,
        openShortcut: (packageName: String, shortcutId: String) -> Unit,
        onTransformSettled: ((View) -> Unit)? = null
    ) {
        val key = System.identityHashCode(target)
        if (!runningTargets.add(key)) return

        var started = false
        try {
            for (recipe in recipes) {
                if (!recipe.enabled) continue
                val triggerBlock = recipe.blocks.firstOrNull { it.type == ReactionBlockType.TRIGGER }
                    ?: continue
                if (triggerBlock.triggerKind != trigger) continue
                if (trigger == TriggerKind.GLOBAL_BOOL) {
                    val gKey = triggerBlock.conditionGlobalKey
                    if (gKey.isBlank()) continue
                    val expected = triggerBlock.conditionBoolValue
                    if (ReactionGlobalsStore.getBool(context, gKey, false) != expected) continue
                }
                if (!evalLogic(context, recipe.blocks, currentPage, batteryPct)) continue

                val stages = buildStages(recipe.blocks)
                if (stages.isEmpty()) continue
                started = true
                runStages(context, stages, 0, target, openApp, openShortcut) {
                    runningTargets.remove(key)
                    try { onTransformSettled?.invoke(target) } catch (_: Exception) {}
                    flushPending()
                }
                return
            }
        } finally {
            if (!started) {
                runningTargets.remove(key)
                flushPending()
            }
        }
    }

    /**
     * Groups ACTION/ASSIGN into stages separated by DO connectors.
     * Example: Fade -DO- Reset -DO- Assign → 3 sequential stages.
     * Fade + Reset (no DO) → 1 parallel stage with both.
     */
    private fun buildStages(blocks: List<ReactionBlock>): List<List<ReactionBlock>> {
        val stages = mutableListOf<MutableList<ReactionBlock>>()
        var current = mutableListOf<ReactionBlock>()
        var forceNewStage = false
        for (b in blocks) {
            when (b.type) {
                ReactionBlockType.ACTION, ReactionBlockType.ASSIGN -> {
                    if (forceNewStage && current.isNotEmpty()) {
                        stages.add(current)
                        current = mutableListOf()
                    }
                    forceNewStage = false
                    current.add(b)
                }
                ReactionBlockType.CONNECTOR -> {
                    if (b.connectorKind == ConnectorKind.DO && current.isNotEmpty()) {
                        // DO after actions: close stage; next action starts a new stage
                        stages.add(current)
                        current = mutableListOf()
                        forceNewStage = true
                    }
                }
                else -> { /* triggers / conditions ignored for stages */ }
            }
        }
        if (current.isNotEmpty()) stages.add(current)
        return stages
    }

    private fun runStages(
        context: Context,
        stages: List<List<ReactionBlock>>,
        index: Int,
        target: View,
        openApp: (String, String) -> Unit,
        openShortcut: (String, String) -> Unit,
        onDone: () -> Unit
    ) {
        if (index >= stages.size) {
            onDone()
            return
        }
        val stage = stages[index]
        if (stage.isEmpty()) {
            runStages(context, stages, index + 1, target, openApp, openShortcut, onDone)
            return
        }
        val remaining = AtomicInteger(stage.size)
        val proceed = {
            if (remaining.decrementAndGet() == 0) {
                runStages(context, stages, index + 1, target, openApp, openShortcut, onDone)
            }
        }
        for (step in stage) {
            when (step.type) {
                ReactionBlockType.ASSIGN -> {
                    applyAssign(context, step)
                    proceed()
                }
                ReactionBlockType.ACTION -> {
                    runAction(context, step, target, openApp, openShortcut) { proceed() }
                }
                else -> proceed()
            }
        }
    }

    private fun evalLogic(
        context: Context,
        blocks: List<ReactionBlock>,
        page: Int,
        battery: Int
    ): Boolean {
        val conditions = mutableListOf<Pair<ConnectorKind, ReactionBlock>>()
        var pendingConnector = ConnectorKind.AND
        for (b in blocks) {
            when (b.type) {
                ReactionBlockType.CONNECTOR -> {
                    if (b.connectorKind == ConnectorKind.AND || b.connectorKind == ConnectorKind.OR) {
                        pendingConnector = b.connectorKind
                    }
                }
                ReactionBlockType.CONDITION -> {
                    conditions.add(pendingConnector to b)
                    pendingConnector = ConnectorKind.AND
                }
                else -> {}
            }
        }
        if (conditions.isEmpty()) return true
        var acc = true
        var first = true
        for ((conn, block) in conditions) {
            val ok = evalCondition(context, block, page, battery)
            if (first) {
                acc = ok
                first = false
            } else when (conn) {
                ConnectorKind.OR -> acc = acc || ok
                ConnectorKind.AND, ConnectorKind.DO -> acc = acc && ok
            }
        }
        return acc
    }

    private fun evalCondition(
        context: Context,
        b: ReactionBlock,
        page: Int,
        battery: Int
    ): Boolean {
        return when (b.conditionKind) {
            ConditionKind.ALWAYS_TRUE -> true
            ConditionKind.GLOBAL_BOOL_EQ ->
                ReactionGlobalsStore.getBool(context, b.conditionGlobalKey, false) == b.conditionBoolValue
            ConditionKind.GLOBAL_BOOL_NEQ ->
                ReactionGlobalsStore.getBool(context, b.conditionGlobalKey, false) != b.conditionBoolValue
            ConditionKind.PAGE_EQ -> page == b.conditionNumber.toInt()
            ConditionKind.PAGE_GTE -> page >= b.conditionNumber.toInt()
            ConditionKind.PAGE_LTE -> page <= b.conditionNumber.toInt()
            ConditionKind.BATTERY_GTE -> battery >= b.conditionNumber.toInt()
        }
    }

    private fun applyAssign(context: Context, b: ReactionBlock) {
        when (b.assignKind) {
            AssignKind.GLOBAL_BOOL ->
                ReactionGlobalsStore.setBool(context, b.assignKey, b.assignBoolValue)
            AssignKind.TOGGLE_GLOBAL_BOOL -> {
                val key = b.assignKey
                if (key.isNotBlank()) {
                    val cur = ReactionGlobalsStore.getBool(context, key, false)
                    ReactionGlobalsStore.setBool(context, key, !cur)
                }
            }
            AssignKind.GLOBAL_INT, AssignKind.GLOBAL_FLOAT -> {}
        }
    }

    private fun runAction(
        context: Context,
        b: ReactionBlock,
        target: View,
        openApp: (String, String) -> Unit,
        openShortcut: (String, String) -> Unit,
        onEnd: () -> Unit
    ) {
        // Ensure callback always runs even if view is detached mid-animation
        val safeEnd = {
            try { onEnd() } catch (_: Exception) {}
        }
        when (b.actionKind) {
            ActionKind.OPEN_APP -> {
                if (b.actionPackage.isNotBlank()) openApp(b.actionPackage, b.actionActivity)
                safeEnd()
            }
            ActionKind.LAUNCH_SHORTCUT -> {
                if (b.actionPackage.isNotBlank() && b.actionShortcutId.isNotBlank()) {
                    openShortcut(b.actionPackage, b.actionShortcutId)
                }
                safeEnd()
            }
            ActionKind.RESET_TRANSFORM -> {
                if (!target.isAttachedToWindow) {
                    target.translationX = 0f
                    target.translationY = 0f
                    target.rotation = 0f
                    target.scaleX = 1f
                    target.scaleY = 1f
                    target.alpha = 1f
                    findImage(target)?.colorFilter = null
                    safeEnd()
                    return
                }
                target.animate().cancel()
                val resetDist = maxOf(
                    kotlin.math.abs(target.translationX),
                    kotlin.math.abs(target.translationY),
                    kotlin.math.abs(target.rotation),
                    kotlin.math.abs(target.scaleX - 1f) * 100f,
                    kotlin.math.abs(target.alpha - 1f) * 100f
                ).coerceAtLeast(1f)
                val resetDur = durationForDistance(resetDist, b)
                target.animate()
                    .translationX(0f).translationY(0f)
                    .rotation(0f).scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(resetDur)
                    .setInterpolator(interpolatorFor(b))
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            findImage(target)?.colorFilter = null
                            safeEnd()
                        }
                        override fun onAnimationCancel(animation: Animator) {
                            // Don't skip the chain if cancelled (e.g. lock screen)
                            findImage(target)?.colorFilter = null
                            safeEnd()
                        }
                    })
                    .start()
            }
            ActionKind.HUE_ROLL -> runHueRoll(target, b, safeEnd)
            ActionKind.TRANSLATE_X -> animateProp(target, "translationX", b.actionValue, b, safeEnd)
            ActionKind.TRANSLATE_Y -> animateProp(target, "translationY", b.actionValue, b, safeEnd)
            ActionKind.ROTATE -> animateProp(target, "rotation", b.actionValue, b, safeEnd)
            ActionKind.SCALE -> {
                val cur = target.scaleX
                val dist = kotlin.math.abs(b.actionValue - cur).coerceAtLeast(0.001f)
                val dur = durationForDistance(dist * 100f, b) // scale units *100 ≈ comparable
                target.animate().cancel()
                target.animate()
                    .scaleX(b.actionValue).scaleY(b.actionValue)
                    .setDuration(dur)
                    .setInterpolator(interpolatorFor(b))
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) = safeEnd()
                        override fun onAnimationCancel(animation: Animator) = safeEnd()
                    })
                    .start()
            }
            ActionKind.ALPHA -> animateProp(target, "alpha", b.actionValue.coerceIn(0f, 1f), b, safeEnd)
        }
    }

    private fun runHueRoll(target: View, b: ReactionBlock, onEnd: () -> Unit) {
        val image = findImage(target)
        if (image == null) {
            onEnd()
            return
        }
        // Full cycle 0→180→-180→0 ≈ 360 hue units; speed = units/s
        val speed = b.actionSpeed.coerceAtLeast(1f)
        val cycleMs = (360f / speed * 1000f).toLong().coerceIn(120L, 20000L)
        val loops = b.actionLoopCount
        val matrix = ColorMatrix()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = cycleMs
        anim.interpolator = interpolatorFor(b)
        if (loops < 0) {
            anim.repeatCount = ValueAnimator.INFINITE
        } else {
            anim.repeatCount = (loops - 1).coerceAtLeast(0)
        }
        anim.addUpdateListener { va ->
            val t = va.animatedFraction
            val hue = if (t <= 0.5f) t * 2f * 180f else -180f + (t - 0.5f) * 2f * 180f
            setHue(matrix, hue)
            image.colorFilter = ColorMatrixColorFilter(matrix)
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (loops >= 0) {
                    setHue(matrix, 0f)
                    image.colorFilter = ColorMatrixColorFilter(matrix)
                    onEnd()
                }
            }
            override fun onAnimationCancel(animation: Animator) = onEnd()
        })
        if (loops < 0) {
            anim.start()
            target.postDelayed({ onEnd() }, 16L)
        } else {
            anim.start()
        }
    }

    private fun setHue(cm: ColorMatrix, degrees: Float) {
        val cosVal = Math.cos(Math.toRadians(degrees.toDouble())).toFloat()
        val sinVal = Math.sin(Math.toRadians(degrees.toDouble())).toFloat()
        val mat = floatArrayOf(
            0.213f + cosVal * 0.787f - sinVal * 0.213f,
            0.715f - cosVal * 0.715f - sinVal * 0.715f,
            0.072f - cosVal * 0.072f + sinVal * 0.928f,
            0f, 0f,
            0.213f - cosVal * 0.213f + sinVal * 0.143f,
            0.715f + cosVal * 0.285f + sinVal * 0.140f,
            0.072f - cosVal * 0.072f - sinVal * 0.283f,
            0f, 0f,
            0.213f - cosVal * 0.213f - sinVal * 0.787f,
            0.715f - cosVal * 0.715f + sinVal * 0.715f,
            0.072f + cosVal * 0.928f + sinVal * 0.072f,
            0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        cm.set(mat)
    }

    private fun findImage(root: View): ImageView? {
        if (root is ImageView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findImage(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun animateProp(
        target: View,
        prop: String,
        value: Float,
        b: ReactionBlock,
        onEnd: () -> Unit
    ) {
        val current = when (prop) {
            "translationX" -> target.translationX
            "translationY" -> target.translationY
            "rotation" -> target.rotation
            "alpha" -> target.alpha
            "scaleX" -> target.scaleX
            else -> 0f
        }
        // Alpha/scale are small ranges — scale distance so speed units feel similar
        val rawDist = kotlin.math.abs(value - current)
        val dist = when (prop) {
            "alpha" -> rawDist * 100f
            "scaleX", "scaleY" -> rawDist * 100f
            else -> rawDist
        }.coerceAtLeast(0.001f)
        val dur = durationForDistance(dist, b)
        val anim = ObjectAnimator.ofFloat(target, prop, value)
        anim.duration = dur
        anim.interpolator = interpolatorFor(b)
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = onEnd()
            override fun onAnimationCancel(animation: Animator) = onEnd()
        })
        anim.start()
    }

    /**
     * Duration from distance + speed (+ optional accel).
     * speed: units/second (px/s, deg/s, …)
     * accel: units/s² — if |accel|≈0 uses t = d/v; else solves d = v0·t + ½·a·t² with v0 = speed.
     */
    private fun durationForDistance(distance: Float, b: ReactionBlock): Long {
        val d = kotlin.math.abs(distance).coerceAtLeast(0.001f)
        val v = kotlin.math.abs(b.actionSpeed).coerceAtLeast(1f)
        val a = b.actionAccel
        val tSec = if (kotlin.math.abs(a) < 0.01f) {
            d / v
        } else {
            // ½ a t² + v t - d = 0  →  t = (-v ± sqrt(v² + 2 a d)) / a
            val disc = v * v + 2f * a * d
            if (disc <= 0f) {
                d / v
            } else {
                val sqrtDisc = kotlin.math.sqrt(disc)
                val t1 = (-v + sqrtDisc) / a
                val t2 = (-v - sqrtDisc) / a
                val candidates = listOf(t1, t2).filter { it > 0.001f }
                candidates.minOrNull() ?: (d / v)
            }
        }
        return (tSec * 1000f).toLong().coerceIn(50L, 30000L)
    }

    private fun interpolatorFor(b: ReactionBlock): android.view.animation.Interpolator {
        val a = b.actionAccel
        return when {
            a > 0.01f -> {
                // Stronger accel → steeper accelerate curve
                val factor = (1f + kotlin.math.abs(a) / 500f).coerceIn(1f, 4f)
                AccelerateInterpolator(factor)
            }
            a < -0.01f -> {
                val factor = (1f + kotlin.math.abs(a) / 500f).coerceIn(1f, 4f)
                DecelerateInterpolator(factor)
            }
            else -> LinearInterpolator()
        }
    }
}
