package com.romaster.launcher.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.romaster.launcher.R
import com.romaster.launcher.data.ActionKind
import com.romaster.launcher.data.AssignKind
import com.romaster.launcher.data.ConditionKind
import com.romaster.launcher.data.ConnectorKind
import com.romaster.launcher.data.ReactionBlock
import com.romaster.launcher.data.ReactionBlockType
import com.romaster.launcher.data.ReactionGlobalsStore
import com.romaster.launcher.data.ReactionRecipe
import com.romaster.launcher.data.TriggerKind

/**
 * Two-stage Animations Editor:
 *  1) Main — list of named animation recipes (Build / Globals)
 *  2) Flow — block cascade for one recipe (Trigger / Connect / Globals / Anims / Actions)
 */
class ReactionEditorDialog(
    context: Context,
    private val initial: List<ReactionRecipe>,
    private val onSave: (List<ReactionRecipe>) -> Unit
) : Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {

    private val density = context.resources.displayMetrics.density
    private val recipes = initial.map {
        it.copy(blocks = it.blocks.map { b -> b.copy() }.toMutableList())
    }.toMutableList()

    /** -1 = main list screen; >=0 = editing recipes[index] */
    private var editingIndex = -1

    private lateinit var rootPanel: LinearLayout
    private lateinit var headerTitle: TextView
    private lateinit var headerAction: TextView
    private lateinit var contentHost: FrameLayout
    private lateinit var bottomHost: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val root = FrameLayout(context)
        root.addView(ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.animator_bg)
        })

        rootPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(14), dp(30), dp(14), dp(12))
        }

        // Header
        val header = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(4), dp(2), dp(4), dp(12))
        }
        headerTitle = TextView(context).apply {
            text = "Animations Editor"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START
            )
        }
        header.addView(headerTitle)
        headerAction = TextView(context).apply {
            text = "Done"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply {
                setColor(0xDD111111.toInt())
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.END
            )
        }
        header.addView(headerAction)
        rootPanel.addView(header)

        contentHost = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        rootPanel.addView(contentHost)

        bottomHost = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        rootPanel.addView(bottomHost)

        root.addView(rootPanel)
        setContentView(root)
        showMainScreen()
    }

    // ─── Screen 1: Main list ───────────────────────────────────────────

    private fun showMainScreen() {
        editingIndex = -1
        headerTitle.text = "Animations Editor"
        headerAction.text = "Done"
        headerAction.setOnClickListener {
            onSave(recipes)
            dismiss()
        }

        contentHost.removeAllViews()
        bottomHost.removeAllViews()

        val scroll = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(20))
        }
        scroll.addView(col)

        if (recipes.isEmpty()) {
            col.addView(TextView(context).apply {
                text = "Tocá Build para crear una animación."
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(40), dp(12), dp(12))
            })
        } else {
            recipes.forEachIndexed { index, recipe ->
                col.addView(mainRecipeCard(index, recipe))
            }
        }
        contentHost.addView(scroll)

        // Bottom: Build + Globals
        val barWrap = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        barWrap.addView(ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)
            )
            scaleType = ImageView.ScaleType.FIT_XY
            setImageResource(R.drawable.animator_bts_bar)
        })
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)
            )
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        bar.addView(assetBtn(R.drawable.animator_build_bt, 1.1f) { createNewRecipe() })
        bar.addView(assetBtn(R.drawable.animator_globals_bt, 1.1f) { showGlobalsMenuMain() })
        barWrap.addView(bar)
        bottomHost.addView(barWrap)
    }

    private fun mainRecipeCard(index: Int, recipe: ReactionRecipe): View {
        val card = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)
            ).apply {
                marginStart = dp(2); marginEnd = dp(2)
                topMargin = dp(6); bottomMargin = dp(6)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showFlowScreen(index) }
            setOnLongClickListener {
                recipeLongPress(index)
                true
            }
        }
        // Card background (icon on the right is baked into the PNG)
        card.addView(ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_XY
            setImageResource(R.drawable.animator_build_card)
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Right padding leaves room for the decorative icon on the card PNG
            setPadding(dp(10), dp(6), dp(40), dp(6))
        }
        // Leading icon (same pattern as flow cards)
        row.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.animator_build_card_icon)
        })
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Line 1: auto index "Animation N"
        texts.addView(TextView(context).apply {
            text = "Animation ${index + 1}"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        })
        // Line 2: user-defined name
        texts.addView(TextView(context).apply {
            text = recipe.name.ifBlank { "Sin nombre" }
            setTextColor(0xEEFFFFFF.toInt())
            textSize = 12f
            maxLines = 1
        })
        row.addView(texts)
        card.addView(row)
        return card
    }

    private fun createNewRecipe() {
        val input = EditText(context).apply {
            hint = "Nombre de la animación"
            setText("Animation ${recipes.size + 1}")
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(context)
            .setTitle("Nueva animación")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Animation ${recipes.size + 1}" }
                recipes.add(ReactionRecipe(name = name, blocks = mutableListOf()))
                showMainScreen()
                // Open flow editor for the new blank recipe
                showFlowScreen(recipes.lastIndex)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun recipeLongPress(index: Int) {
        val recipe = recipes.getOrNull(index) ?: return
        AlertDialog.Builder(context)
            .setTitle(recipe.name)
            .setItems(arrayOf("Renombrar", "Eliminar")) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(context).apply {
                            setText(recipe.name)
                            setSelectAllOnFocus(true)
                        }
                        AlertDialog.Builder(context)
                            .setTitle("Renombrar")
                            .setView(input)
                            .setPositiveButton("OK") { _, _ ->
                                recipe.name = input.text.toString().trim().ifBlank { recipe.name }
                                showMainScreen()
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                    1 -> {
                        AlertDialog.Builder(context)
                            .setTitle("Eliminar ${recipe.name}")
                            .setMessage("Se borrará toda la animación. Las variables globales no se eliminan.")
                            .setPositiveButton("Eliminar") { _, _ ->
                                recipes.removeAt(index)
                                showMainScreen()
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showGlobalsMenuMain() {
        AlertDialog.Builder(context)
            .setTitle("Globals")
            .setItems(arrayOf("NEW Global bool", "Globals List")) { _, which ->
                when (which) {
                    0 -> promptNewGlobal()
                    1 -> showGlobalsList()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ─── Screen 2: Flow editor ─────────────────────────────────────────

    private fun showFlowScreen(index: Int) {
        if (index !in recipes.indices) return
        editingIndex = index
        val recipe = recipes[index]
        headerTitle.text = recipe.name.ifBlank { "Animation ${index + 1}" }
        headerAction.text = "←"
        headerAction.setOnClickListener { showMainScreen() }

        contentHost.removeAllViews()
        bottomHost.removeAllViews()

        val scroll = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val flowColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(20))
            tag = "flow_column"
        }
        scroll.addView(flowColumn)
        contentHost.addView(scroll)
        rebuildFlow(flowColumn)

        // 5-button bar: Trigger | Connect | Globals | Anims | Actions (taller for 5 buttons)
        val barWrap = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        barWrap.addView(ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)
            )
            scaleType = ImageView.ScaleType.FIT_XY
            setImageResource(R.drawable.animator_bts_bar)
        })
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)
            )
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        fun smallBtn(resId: Int, onClick: () -> Unit) = ImageView(context).apply {
            setImageResource(resId)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(3); marginEnd = dp(3)
            }
            setOnClickListener { onClick() }
        }
        bar.addView(smallBtn(R.drawable.animator_trigger_bt) { addBlock(ReactionBlockType.TRIGGER) })
        bar.addView(smallBtn(R.drawable.animator_connect_bt) { addConnector() })
        bar.addView(smallBtn(R.drawable.animator_globals_bt) { showGlobalsMenuFlow() })
        bar.addView(smallBtn(R.drawable.animator_anims_bt) { addAnimAction() })
        bar.addView(smallBtn(R.drawable.animator_actions_bt) { addAppAction() })
        barWrap.addView(bar)

        // Helpers: Ejemplo + Clear All
        val helpers = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(2))
        }
        val bottomCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        bottomHost.removeAllViews()
        bottomCol.addView(barWrap)
        helpers.addView(smallTextBtn("Ejemplo") {
            ReactionGlobalsStore.addBool(context, "Anim1", false)
            ReactionGlobalsStore.addBool(context, "Anim2", false)
            val example = ReactionRecipe.exampleTapTranslate()
            example.name = recipes[editingIndex].name
            recipes[editingIndex] = example
            rebuildFlow(flowColumn)
            Toast.makeText(context, "Ejemplo cargado", Toast.LENGTH_SHORT).show()
        })
        helpers.addView(smallTextBtn("Clear All") {
            AlertDialog.Builder(context)
                .setTitle("Clear All")
                .setMessage("Confirma borrar todo el flujo de la animación?")
                .setPositiveButton("Aceptar") { _, _ ->
                    val name = recipes[editingIndex].name
                    recipes[editingIndex] = ReactionRecipe(
                        id = recipes[editingIndex].id,
                        name = name,
                        blocks = mutableListOf()
                    )
                    rebuildFlow(flowColumn)
                    Toast.makeText(context, "Flujo limpiado", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        })
        bottomCol.addView(helpers)
        bottomHost.addView(bottomCol)
    }

    private fun flowColumn(): LinearLayout? {
        val scroll = contentHost.getChildAt(0) as? ScrollView ?: return null
        return scroll.getChildAt(0) as? LinearLayout
    }

    private fun currentRecipe(): ReactionRecipe = recipes[editingIndex]

    private fun rebuildFlow(col: LinearLayout? = flowColumn()) {
        val flowColumn = col ?: return
        flowColumn.removeAllViews()
        val blocks = currentRecipe().blocks
        if (blocks.isEmpty()) {
            flowColumn.addView(TextView(context).apply {
                text = "Agregá un Trigger, conexiones y acciones con la barra inferior."
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 14f
                setPadding(dp(12), dp(28), dp(12), dp(12))
                gravity = Gravity.CENTER
            })
            return
        }
        blocks.forEachIndexed { index, block ->
            if (block.type == ReactionBlockType.CONNECTOR) {
                flowColumn.addView(connectorView(index, block))
            } else {
                flowColumn.addView(cardView(index, block))
            }
        }
    }

    // ─── Add blocks ────────────────────────────────────────────────────

    private fun addConnector() {
        AlertDialog.Builder(context)
            .setTitle("Conexión")
            .setItems(arrayOf("AND", "OR", "DO")) { _, which ->
                val kind = when (which) {
                    0 -> ConnectorKind.AND
                    1 -> ConnectorKind.OR
                    else -> ConnectorKind.DO
                }
                currentRecipe().blocks.add(
                    ReactionBlock(type = ReactionBlockType.CONNECTOR, connectorKind = kind)
                )
                rebuildFlow()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun addBlock(type: ReactionBlockType) {
        val block = when (type) {
            ReactionBlockType.TRIGGER -> ReactionBlock(
                type = type, triggerKind = TriggerKind.TAP, triggerCount = 1
            )
            ReactionBlockType.CONDITION -> ReactionBlock(
                type = type,
                conditionKind = ConditionKind.GLOBAL_BOOL_EQ,
                conditionGlobalKey = "",
                conditionBoolValue = false
            )
            ReactionBlockType.ACTION -> ReactionBlock(
                type = type,
                actionKind = ActionKind.TRANSLATE_Y,
                actionValue = -300f,
                actionSpeed = 80f,
                actionAccel = -80f
            )
            ReactionBlockType.ASSIGN -> ReactionBlock(
                type = type,
                assignKind = AssignKind.GLOBAL_BOOL,
                assignKey = "",
                assignBoolValue = true
            )
            ReactionBlockType.CONNECTOR -> ReactionBlock(
                type = type, connectorKind = ConnectorKind.DO
            )
        }
        currentRecipe().blocks.add(block)
        rebuildFlow()
        if (type != ReactionBlockType.CONNECTOR) {
            editBlock(currentRecipe().blocks.lastIndex)
        }
    }

    /** Anims button: visual transforms only (no app launch). */
    private fun addAnimAction() {
        val options = arrayOf(
            "Translate X",
            "Translate Y",
            "Rotate",
            "Scale / Zoom",
            "Fade (Alpha)",
            "Hue roll",
            "Reset transform"
        )
        AlertDialog.Builder(context)
            .setTitle("Animación")
            .setItems(options) { _, which ->
                val block = ReactionBlock(type = ReactionBlockType.ACTION)
                when (which) {
                    0 -> {
                        block.actionKind = ActionKind.TRANSLATE_X
                        block.actionValue = -600f
                        block.actionSpeed = 600f
                        block.actionAccel = 0f
                    }
                    1 -> {
                        block.actionKind = ActionKind.TRANSLATE_Y
                        block.actionValue = -300f
                        block.actionSpeed = 600f
                        block.actionAccel = 0f
                    }
                    2 -> {
                        block.actionKind = ActionKind.ROTATE
                        block.actionValue = 45f
                        block.actionSpeed = 180f
                        block.actionAccel = 0f
                    }
                    3 -> {
                        block.actionKind = ActionKind.SCALE
                        block.actionValue = 1.2f
                        block.actionSpeed = 1f
                        block.actionAccel = 0f
                    }
                    4 -> {
                        block.actionKind = ActionKind.ALPHA
                        block.actionValue = 0f
                        block.actionSpeed = 1f
                        block.actionAccel = 0f
                    }
                    5 -> {
                        block.actionKind = ActionKind.HUE_ROLL
                        block.actionLoopCount = 1
                        block.actionSpeed = 180f
                        block.actionAccel = 0f
                    }
                    6 -> {
                        block.actionKind = ActionKind.RESET_TRANSFORM
                        block.actionSpeed = 600f
                        block.actionAccel = 0f
                    }
                }
                currentRecipe().blocks.add(block)
                rebuildFlow()
                val idx = currentRecipe().blocks.lastIndex
                when (block.actionKind) {
                    ActionKind.TRANSLATE_X ->
                        promptAnimParams(idx, block, valueLabel = "X (px)", speedLabel = "Velocidad (px/s)", accelLabel = "Aceleración (px/s²)")
                    ActionKind.TRANSLATE_Y ->
                        promptAnimParams(idx, block, valueLabel = "Y (px)", speedLabel = "Velocidad (px/s)", accelLabel = "Aceleración (px/s²)")
                    ActionKind.ROTATE ->
                        promptAnimParams(idx, block, valueLabel = "Grados", speedLabel = "Velocidad (°/s)", accelLabel = "Aceleración (°/s²)")
                    ActionKind.SCALE ->
                        promptAnimParams(idx, block, valueLabel = "Escala", speedLabel = "Velocidad (escala/s)", accelLabel = "Aceleración (escala/s²)")
                    ActionKind.ALPHA ->
                        promptAnimParams(idx, block, valueLabel = "Alpha 0..1", speedLabel = "Velocidad (alpha/s)", accelLabel = "Aceleración (alpha/s²)")
                    ActionKind.HUE_ROLL -> {
                        // loops first, then speed/accel
                        AlertDialog.Builder(context)
                            .setTitle("Hue roll — repeticiones")
                            .setItems(arrayOf("1 vez", "2 veces", "3 veces", "Infinito")) { _, w ->
                                block.actionLoopCount = when (w) {
                                    0 -> 1; 1 -> 2; 2 -> 3; else -> -1
                                }
                                currentRecipe().blocks[idx] = block
                                rebuildFlow()
                                promptAnimParams(
                                    idx, block,
                                    valueLabel = null,
                                    speedLabel = "Velocidad (°/s)",
                                    accelLabel = "Aceleración (°/s²)"
                                )
                            }
                            .setNegativeButton("Cerrar") { _, _ ->
                                promptAnimParams(
                                    idx, block,
                                    valueLabel = null,
                                    speedLabel = "Velocidad (°/s)",
                                    accelLabel = "Aceleración (°/s²)"
                                )
                            }
                            .show()
                    }
                    ActionKind.RESET_TRANSFORM ->
                        promptAnimParams(
                            idx, block,
                            valueLabel = null,
                            speedLabel = "Velocidad (px/s)",
                            accelLabel = "Aceleración (px/s²)"
                        )
                    else -> {}
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /**
     * Sequential prompts: value (optional) → speed → accel.
     * Updates [block] in the current recipe and rebuilds the flow.
     */
    private fun promptAnimParams(
        index: Int,
        block: ReactionBlock,
        valueLabel: String?,
        speedLabel: String,
        accelLabel: String
    ) {
        fun askAccel() {
            promptNumber(accelLabel, block.actionAccel) { a ->
                block.actionAccel = a
                if (index in currentRecipe().blocks.indices) {
                    currentRecipe().blocks[index] = block
                }
                rebuildFlow()
            }
        }
        fun askSpeed() {
            promptNumber(speedLabel, block.actionSpeed) { s ->
                block.actionSpeed = s.coerceAtLeast(0.01f)
                if (index in currentRecipe().blocks.indices) {
                    currentRecipe().blocks[index] = block
                }
                rebuildFlow()
                askAccel()
            }
        }
        if (valueLabel != null) {
            promptNumber(valueLabel, block.actionValue) { v ->
                block.actionValue = when (block.actionKind) {
                    ActionKind.ALPHA -> v.coerceIn(0f, 1f)
                    else -> v
                }
                if (index in currentRecipe().blocks.indices) {
                    currentRecipe().blocks[index] = block
                }
                rebuildFlow()
                askSpeed()
            }
        } else {
            askSpeed()
        }
    }

    /** Actions button: open app / launch shortcut. */
    private fun addAppAction() {
        AlertDialog.Builder(context)
            .setTitle("Acción")
            .setItems(arrayOf("Open app", "Launch action (shortcut)")) { _, which ->
                val block = ReactionBlock(type = ReactionBlockType.ACTION)
                when (which) {
                    0 -> {
                        block.actionKind = ActionKind.OPEN_APP
                        currentRecipe().blocks.add(block)
                        rebuildFlow()
                        pickInstalledApp(currentRecipe().blocks.lastIndex, block, shortcuts = false)
                    }
                    1 -> {
                        block.actionKind = ActionKind.LAUNCH_SHORTCUT
                        currentRecipe().blocks.add(block)
                        rebuildFlow()
                        pickInstalledApp(currentRecipe().blocks.lastIndex, block, shortcuts = true)
                    }
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showGlobalsMenuFlow() {
        AlertDialog.Builder(context)
            .setTitle("Globals")
            .setItems(
                arrayOf(
                    "NEW Global bool",
                    "Globals List",
                    "Agregar Switch",
                    "Agregar Assign",
                    "Agregar Condición"
                )
            ) { _, which ->
                when (which) {
                    0 -> promptNewGlobal()
                    1 -> showGlobalsList()
                    2 -> addSwitchBlock()
                    3 -> addBlock(ReactionBlockType.ASSIGN)
                    4 -> addBlock(ReactionBlockType.CONDITION)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Adds a TOGGLE_GLOBAL_BOOL assign block and opens global picker. */
    private fun addSwitchBlock() {
        val list = ReactionGlobalsStore.listBools(context)
        if (list.isEmpty()) {
            Toast.makeText(context, "No hay variables globales", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { "${it.name}  (actual: ${it.value})" }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("G Switch — elegir variable")
            .setItems(labels) { _, which ->
                val g = list[which]
                val block = ReactionBlock(
                    type = ReactionBlockType.ASSIGN,
                    assignKind = AssignKind.TOGGLE_GLOBAL_BOOL,
                    assignKey = g.name
                )
                currentRecipe().blocks.add(block)
                rebuildFlow()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ─── Cards / connectors UI ─────────────────────────────────────────

    private fun connectorView(index: Int, block: ReactionBlock): View {
        val res = when (block.connectorKind) {
            ConnectorKind.AND -> R.drawable.animator_connect_and
            ConnectorKind.OR -> R.drawable.animator_connect_or
            ConnectorKind.DO -> R.drawable.animator_connect_do
        }
        return ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply {
                marginStart = dp(8); marginEnd = dp(8)
                topMargin = dp(2); bottomMargin = dp(2)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(res)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                block.connectorKind = when (block.connectorKind) {
                    ConnectorKind.AND -> ConnectorKind.OR
                    ConnectorKind.OR -> ConnectorKind.DO
                    ConnectorKind.DO -> ConnectorKind.AND
                }
                currentRecipe().blocks[index] = block
                rebuildFlow()
            }
            setOnLongClickListener {
                confirmRemove(index)
                true
            }
        }
    }

    private fun cardView(index: Int, block: ReactionBlock): View {
        val cardBg = when (block.type) {
            ReactionBlockType.TRIGGER -> R.drawable.animator_trigger_card
            ReactionBlockType.CONDITION, ReactionBlockType.ASSIGN -> R.drawable.animator_globals_card
            ReactionBlockType.ACTION -> when (block.actionKind) {
                ActionKind.OPEN_APP, ActionKind.LAUNCH_SHORTCUT -> R.drawable.animator_actions_card
                else -> R.drawable.animator_anims_card
            }
            else -> R.drawable.animator_anims_card
        }
        val card = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)
            ).apply {
                marginStart = dp(2); marginEnd = dp(2)
                topMargin = dp(2); bottomMargin = dp(2)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { editBlock(index) }
            setOnLongClickListener {
                confirmRemove(index)
                true
            }
        }
        card.addView(ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_XY
            setImageResource(cardBg)
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(10), dp(6), dp(40), dp(6))
        }
        row.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(leftIconFor(block))
        })
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(context).apply {
            text = block.titleLine()
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        })
        texts.addView(TextView(context).apply {
            text = block.detailLine()
            setTextColor(0xEEFFFFFF.toInt())
            textSize = 12f
            maxLines = 2
        })
        row.addView(texts)
        card.addView(row)
        return card
    }

    private fun leftIconFor(block: ReactionBlock): Int = when (block.type) {
        ReactionBlockType.TRIGGER -> when (block.triggerKind) {
            TriggerKind.TAP, TriggerKind.DOUBLE_TAP, TriggerKind.LONG_PRESS ->
                R.drawable.animator_trigger_tap
            TriggerKind.SWIPE_UP -> R.drawable.animator_trigger_swipe_up
            TriggerKind.SWIPE_DOWN -> R.drawable.animator_trigger_swipe_down
            TriggerKind.SWIPE_LEFT -> R.drawable.animator_trigger_swipe_left
            TriggerKind.SWIPE_RIGHT -> R.drawable.animator_trigger_swipe_right
            TriggerKind.GLOBAL_BOOL -> R.drawable.animator_trigger_global
        }
        ReactionBlockType.CONDITION -> R.drawable.animator_globals_conditions
        ReactionBlockType.ASSIGN ->
            if (block.assignKind == AssignKind.TOGGLE_GLOBAL_BOOL)
                R.drawable.animator_global_switch
            else
                R.drawable.animator_globals_assign
        ReactionBlockType.ACTION -> when (block.actionKind) {
            ActionKind.TRANSLATE_X, ActionKind.TRANSLATE_Y -> R.drawable.animator_anims_translate
            ActionKind.SCALE -> R.drawable.animator_anims_zoom
            ActionKind.ALPHA -> R.drawable.animator_anims_fade
            ActionKind.HUE_ROLL -> R.drawable.animator_anims_hue_roll
            ActionKind.ROTATE -> R.drawable.animator_anims_rotate
            ActionKind.RESET_TRANSFORM -> R.drawable.animator_anims_reset_transform
            ActionKind.OPEN_APP -> R.drawable.animator_action_app_open
            ActionKind.LAUNCH_SHORTCUT -> R.drawable.animator_action_app_action
        }
        else -> R.drawable.animator_anims_translate
    }

    private fun confirmRemove(index: Int) {
        AlertDialog.Builder(context)
            .setTitle("Quitar")
            .setMessage("¿Eliminar este elemento?")
            .setPositiveButton("Quitar") { _, _ ->
                if (index in currentRecipe().blocks.indices) {
                    currentRecipe().blocks.removeAt(index)
                    rebuildFlow()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun editBlock(index: Int) {
        val block = currentRecipe().blocks.getOrNull(index) ?: return
        when (block.type) {
            ReactionBlockType.TRIGGER -> editTrigger(index, block)
            ReactionBlockType.CONDITION -> editCondition(index, block)
            ReactionBlockType.ACTION -> editAction(index, block)
            ReactionBlockType.ASSIGN -> editAssign(index, block)
            ReactionBlockType.CONNECTOR -> {}
        }
    }

    private fun editTrigger(index: Int, block: ReactionBlock) {
        // LONG_PRESS is not offered: it would block the widget options menu.
        val kinds = TriggerKind.values().filter { it != TriggerKind.LONG_PRESS }
        val labels = kinds.map {
            when (it) {
                TriggerKind.TAP -> "Tap"
                TriggerKind.DOUBLE_TAP -> "Double tap"
                TriggerKind.LONG_PRESS -> "Long press"
                TriggerKind.SWIPE_UP -> "Swipe up"
                TriggerKind.SWIPE_DOWN -> "Swipe down"
                TriggerKind.SWIPE_LEFT -> "Swipe left"
                TriggerKind.SWIPE_RIGHT -> "Swipe right"
                TriggerKind.GLOBAL_BOOL -> "Trigger Global bool"
            }
        }.toTypedArray()
        val checked = kinds.indexOf(block.triggerKind).let { if (it >= 0) it else 0 }
        AlertDialog.Builder(context)
            .setTitle("Trigger")
            .setSingleChoiceItems(labels, checked) { d, which ->
                val kind = kinds[which]
                block.triggerKind = kind
                block.triggerCount = 1
                currentRecipe().blocks[index] = block
                rebuildFlow()
                d.dismiss()
                if (kind == TriggerKind.GLOBAL_BOOL) {
                    pickGlobalForTrigger(index, block)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun pickGlobalForTrigger(index: Int, block: ReactionBlock) {
        val list = ReactionGlobalsStore.listBools(context)
        if (list.isEmpty()) {
            Toast.makeText(context, "No hay variables globales", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { "${it.name}  (actual: ${it.value})" }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Variable global")
            .setItems(labels) { _, which ->
                val g = list[which]
                AlertDialog.Builder(context)
                    .setTitle("Disparar cuando ${g.name} sea…")
                    .setItems(arrayOf("true", "false")) { _, w ->
                        block.triggerKind = TriggerKind.GLOBAL_BOOL
                        block.conditionGlobalKey = g.name
                        block.conditionBoolValue = (w == 0) // 0=true, 1=false
                        currentRecipe().blocks[index] = block
                        rebuildFlow()
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun editCondition(index: Int, block: ReactionBlock) {
        val options = arrayOf(
            "Global bool == …",
            "Global bool != …",
            "Siempre (true)",
            "Página == N",
            "Página >= N",
            "Batería >= N%"
        )
        AlertDialog.Builder(context)
            .setTitle("Condición")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickGlobalForCondition(index, block, equals = true)
                    1 -> pickGlobalForCondition(index, block, equals = false)
                    2 -> {
                        block.conditionKind = ConditionKind.ALWAYS_TRUE
                        currentRecipe().blocks[index] = block
                        rebuildFlow()
                    }
                    3 -> {
                        block.conditionKind = ConditionKind.PAGE_EQ
                        promptNumber("Número de página", block.conditionNumber) {
                            block.conditionNumber = it
                            currentRecipe().blocks[index] = block
                            rebuildFlow()
                        }
                    }
                    4 -> {
                        block.conditionKind = ConditionKind.PAGE_GTE
                        promptNumber("Página mínima", block.conditionNumber) {
                            block.conditionNumber = it
                            currentRecipe().blocks[index] = block
                            rebuildFlow()
                        }
                    }
                    5 -> {
                        block.conditionKind = ConditionKind.BATTERY_GTE
                        promptNumber("Batería mínima %", if (block.conditionNumber == 0f) 15f else block.conditionNumber) {
                            block.conditionNumber = it
                            currentRecipe().blocks[index] = block
                            rebuildFlow()
                        }
                    }
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun pickGlobalForCondition(index: Int, block: ReactionBlock, equals: Boolean) {
        val list = ReactionGlobalsStore.listBools(context)
        if (list.isEmpty()) {
            Toast.makeText(context, "No hay variables globales", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { "${it.name}  (actual: ${it.value})" }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Elegir global")
            .setItems(labels) { _, which ->
                val g = list[which]
                AlertDialog.Builder(context)
                    .setTitle("${g.name} ${if (equals) "==" else "!="}")
                    .setItems(arrayOf("false", "true")) { _, w ->
                        block.conditionKind =
                            if (equals) ConditionKind.GLOBAL_BOOL_EQ else ConditionKind.GLOBAL_BOOL_NEQ
                        block.conditionGlobalKey = g.name
                        block.conditionBoolValue = w == 1
                        currentRecipe().blocks[index] = block
                        rebuildFlow()
                    }
                    .show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun editAssign(index: Int, block: ReactionBlock) {
        val list = ReactionGlobalsStore.listBools(context)
        if (list.isEmpty()) {
            Toast.makeText(context, "No hay variables globales", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { "${it.name}  (actual: ${it.value})" }.toTypedArray()
        if (block.assignKind == AssignKind.TOGGLE_GLOBAL_BOOL) {
            AlertDialog.Builder(context)
                .setTitle("G Switch — variable")
                .setItems(labels) { _, which ->
                    block.assignKind = AssignKind.TOGGLE_GLOBAL_BOOL
                    block.assignKey = list[which].name
                    currentRecipe().blocks[index] = block
                    rebuildFlow()
                }
                .setNegativeButton("Cerrar", null)
                .show()
            return
        }
        AlertDialog.Builder(context)
            .setTitle("Asignar global")
            .setItems(labels) { _, which ->
                val g = list[which]
                AlertDialog.Builder(context)
                    .setTitle("${g.name} =")
                    .setItems(arrayOf("false", "true")) { _, w ->
                        block.assignKind = AssignKind.GLOBAL_BOOL
                        block.assignKey = g.name
                        block.assignBoolValue = w == 1
                        currentRecipe().blocks[index] = block
                        rebuildFlow()
                    }
                    .show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun editAction(index: Int, block: ReactionBlock) {
        when (block.actionKind) {
            ActionKind.OPEN_APP -> pickInstalledApp(index, block, shortcuts = false)
            ActionKind.LAUNCH_SHORTCUT -> pickInstalledApp(index, block, shortcuts = true)
            ActionKind.HUE_ROLL -> {
                AlertDialog.Builder(context)
                    .setTitle("Hue roll — repeticiones")
                    .setItems(arrayOf("1 vez", "2 veces", "3 veces", "Infinito")) { _, w ->
                        block.actionLoopCount = when (w) {
                            0 -> 1; 1 -> 2; 2 -> 3; else -> -1
                        }
                        currentRecipe().blocks[index] = block
                        rebuildFlow()
                        promptAnimParams(
                            index, block,
                            valueLabel = null,
                            speedLabel = "Velocidad (°/s)",
                            accelLabel = "Aceleración (°/s²)"
                        )
                    }
                    .setNeutralButton("Solo vel/acel") { _, _ ->
                        promptAnimParams(
                            index, block,
                            valueLabel = null,
                            speedLabel = "Velocidad (°/s)",
                            accelLabel = "Aceleración (°/s²)"
                        )
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
            ActionKind.RESET_TRANSFORM ->
                promptAnimParams(
                    index, block,
                    valueLabel = null,
                    speedLabel = "Velocidad (px/s)",
                    accelLabel = "Aceleración (px/s²)"
                )
            ActionKind.TRANSLATE_X ->
                promptAnimParams(index, block, "X (px)", "Velocidad (px/s)", "Aceleración (px/s²)")
            ActionKind.TRANSLATE_Y ->
                promptAnimParams(index, block, "Y (px)", "Velocidad (px/s)", "Aceleración (px/s²)")
            ActionKind.ROTATE ->
                promptAnimParams(index, block, "Grados", "Velocidad (°/s)", "Aceleración (°/s²)")
            ActionKind.SCALE ->
                promptAnimParams(index, block, "Escala", "Velocidad (escala/s)", "Aceleración (escala/s²)")
            ActionKind.ALPHA ->
                promptAnimParams(index, block, "Alpha 0..1", "Velocidad (alpha/s)", "Aceleración (alpha/s²)")
        }
    }

    // ─── Globals helpers ───────────────────────────────────────────────

    private fun promptNewGlobal() {
        val input = EditText(context).apply { hint = "Nombre (ej. Anim1)" }
        AlertDialog.Builder(context)
            .setTitle("NEW Global bool")
            .setView(input)
            .setPositiveButton("Siguiente") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(context, "Nombre vacío", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                AlertDialog.Builder(context)
                    .setTitle("Valor inicial de $name")
                    .setItems(arrayOf("false", "true")) { _, w ->
                        val ok = ReactionGlobalsStore.addBool(context, name, w == 1)
                        Toast.makeText(
                            context,
                            if (ok) "Creado $name = ${w == 1}" else "Ya existe $name",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showGlobalsList() {
        val list = ReactionGlobalsStore.listBools(context)
        if (list.isEmpty()) {
            Toast.makeText(context, "No hay variables globales", Toast.LENGTH_SHORT).show()
            return
        }
        val items = list.map { "${it.name} = ${it.value}" }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Globals List")
            .setItems(items) { _, which ->
                val g = list[which]
                AlertDialog.Builder(context)
                    .setTitle(g.name)
                    .setItems(arrayOf("true", "false", "Eliminar")) { _, w ->
                        when (w) {
                            0 -> {
                                ReactionGlobalsStore.setBool(context, g.name, true)
                                Toast.makeText(context, "${g.name} = true", Toast.LENGTH_SHORT).show()
                            }
                            1 -> {
                                ReactionGlobalsStore.setBool(context, g.name, false)
                                Toast.makeText(context, "${g.name} = false", Toast.LENGTH_SHORT).show()
                            }
                            2 -> {
                                ReactionGlobalsStore.deleteBool(context, g.name)
                                Toast.makeText(context, "Eliminado ${g.name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ─── App picker ────────────────────────────────────────────────────

    private data class AppRow(
        val label: String,
        val packageName: String,
        val activityName: String,
        val icon: android.graphics.drawable.Drawable?,
        val isSystem: Boolean
    )

    private fun loadApps(userOnly: Boolean): List<AppRow> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolves = pm.queryIntentActivities(intent, 0)
        return resolves.mapNotNull { ri ->
            val ai = try { ri.activityInfo.applicationInfo } catch (_: Exception) { null } ?: return@mapNotNull null
            val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (userOnly && isSys) return@mapNotNull null
            if (!userOnly && !isSys) return@mapNotNull null
            AppRow(
                label = ri.loadLabel(pm)?.toString() ?: ai.packageName,
                packageName = ai.packageName,
                activityName = ri.activityInfo.name ?: "",
                icon = try { ri.loadIcon(pm) } catch (_: Exception) { null },
                isSystem = isSys
            )
        }.sortedBy { it.label.lowercase() }
    }

    private fun pickInstalledApp(index: Int, block: ReactionBlock, shortcuts: Boolean) {
        AlertDialog.Builder(context)
            .setTitle("Filtro de apps")
            .setItems(arrayOf("Apps de usuario", "Apps de sistema", "Todas")) { _, filter ->
                val apps = when (filter) {
                    0 -> loadApps(userOnly = true)
                    1 -> loadApps(userOnly = false)
                    else -> (loadApps(true) + loadApps(false)).sortedBy { it.label.lowercase() }
                }
                if (apps.isEmpty()) {
                    Toast.makeText(context, "No hay apps", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                showAppListDialog(apps, index, block, shortcuts)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showAppListDialog(
        apps: List<AppRow>,
        index: Int,
        block: ReactionBlock,
        shortcuts: Boolean
    ) {
        val container = ScrollView(context)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        container.addView(col)
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (shortcuts) "App para acción" else "Abrir app")
            .setView(container)
            .setNegativeButton("Cerrar", null)
            .create()

        apps.forEach { app ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (shortcuts) {
                        dialog.dismiss()
                        pickShortcutForApp(index, block, app)
                    } else {
                        block.actionPackage = app.packageName
                        block.actionActivity = app.activityName
                        block.actionLabel = app.label
                        currentRecipe().blocks[index] = block
                        rebuildFlow()
                        dialog.dismiss()
                    }
                }
            }
            row.addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) }
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(context).apply {
                text = app.label
                textSize = 15f
                setTextColor(0xFF222222.toInt())
            })
            texts.addView(TextView(context).apply {
                text = app.packageName
                textSize = 11f
                setTextColor(0xFF666666.toInt())
            })
            row.addView(texts)
            col.addView(row)
        }
        dialog.show()
    }

    private fun pickShortcutForApp(index: Int, block: ReactionBlock, app: AppRow) {
        val shortcuts = mutableListOf<Pair<String, String>>()
        try {
            val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val list = la.getShortcuts(
                android.content.pm.LauncherApps.ShortcutQuery()
                    .setPackage(app.packageName)
                    .setQueryFlags(
                        android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                            android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                            android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                    ),
                Process.myUserHandle()
            )
            list?.forEach { s ->
                val id = s.id ?: return@forEach
                val label = s.shortLabel?.toString() ?: s.longLabel?.toString() ?: id
                shortcuts.add(id to label)
            }
        } catch (_: Exception) { }

        if (shortcuts.isEmpty()) {
            Toast.makeText(context, "Sin acciones; se abrirá la app", Toast.LENGTH_SHORT).show()
            block.actionKind = ActionKind.OPEN_APP
            block.actionPackage = app.packageName
            block.actionActivity = app.activityName
            block.actionLabel = app.label
            block.actionShortcutId = ""
            currentRecipe().blocks[index] = block
            rebuildFlow()
            return
        }
        val labels = shortcuts.map { it.second }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Acciones de ${app.label}")
            .setItems(labels) { _, which ->
                val (id, label) = shortcuts[which]
                block.actionKind = ActionKind.LAUNCH_SHORTCUT
                block.actionPackage = app.packageName
                block.actionActivity = app.activityName
                block.actionShortcutId = id
                block.actionLabel = "${app.label}: $label"
                currentRecipe().blocks[index] = block
                rebuildFlow()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ─── UI helpers ────────────────────────────────────────────────────

    private fun assetBtn(resId: Int, weight: Float = 1f, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(resId)
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
            marginStart = dp(6); marginEnd = dp(6)
        }
        setOnClickListener { onClick() }
    }

    private fun smallTextBtn(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
            setColor(0x66000000); cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(5); marginEnd = dp(5) }
        setOnClickListener { onClick() }
    }

    private fun promptNumber(title: String, initial: Float, onOk: (Float) -> Unit) {
        val input = EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (initial % 1f == 0f) initial.toInt().toString() else initial.toString())
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                onOk(input.text.toString().toFloatOrNull() ?: initial)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * density).toInt()
}
