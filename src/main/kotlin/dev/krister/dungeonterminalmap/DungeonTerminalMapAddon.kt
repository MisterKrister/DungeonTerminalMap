package dev.krister.dungeonterminalmap

import com.github.synnerz.devonian.Devonian
import com.github.synnerz.devonian.api.Location
import com.github.synnerz.devonian.api.events.ChatEvent
import com.github.synnerz.devonian.api.events.TickEvent
import com.github.synnerz.devonian.config.Categories
import com.github.synnerz.devonian.config.Config
import com.github.synnerz.devonian.config.ConfigData
import com.github.synnerz.devonian.hud.texthud.TextHudFeature
import com.github.synnerz.devonian.utils.BoundingBox
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.debug.DebugRenderer
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.phys.AABB
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object DungeonTerminalMapAddon : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("DungeonTerminalMap")
    private val logDirectory = FabricLoader.getInstance().configDir.resolve("DungeonTerminalMap").toFile()
    private val logSessionStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    private val debugLogFile = logDirectory.resolve("debug-$logSessionStamp.log")
    private var commandsRegistered = false
    private var registered = false
    private var feature: DungeonTerminalMapFeature? = null
    private var renderHookSeen = false
    private var lastMissingFeatureLog = 0L

    override fun onInitializeClient() {
        debug("Client entrypoint initializing")
        registerFabricChatHooks()
        registerCommands()
        registerWorldRenderHooks()
    }

    fun registerWithDevonian() {
        if (registered) return
        debug("Registering DungeonTerminalMap feature with Devonian")
        registerCommands()
        val category = Categories.GLOBAL
        val subcategory = ensureDevonianSubcategory(category, "DTM") ?: "Mod"
        val created = DungeonTerminalMapFeature(category, subcategory)
        feature = created
        Devonian.addFeatureInstance(created)
        registered = true
        debug("Devonian feature registration complete")
    }

    fun renderOverlay(graphics: GuiGraphics) {
        if (!renderHookSeen) {
            renderHookSeen = true
            debug("Gui render hook fired")
        }
        val current = feature
        if (current == null) {
            val now = System.currentTimeMillis()
            if (now - lastMissingFeatureLog >= 5_000) {
                lastMissingFeatureLog = now
                debug("Render skipped: feature is not registered")
            }
            return
        }
        current.renderHud(graphics)
    }

    fun renderWorldDebug(context: WorldRenderContext) {
        feature?.renderMatchBoxes(context)
    }

    fun onChatMessage(message: Component) {
        onChatMessage(message, "chat_component_mixin")
    }

    private fun registerFabricChatHooks() {
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            onChatMessage(message, "fabric_game overlay=$overlay")
        }
        ClientReceiveMessageEvents.CHAT.register { message, _, sender, _, _ ->
            onChatMessage(message, "fabric_chat sender=${sender?.name ?: "null"}")
        }
        ClientReceiveMessageEvents.GAME_CANCELED.register { message, overlay ->
            onChatMessage(message, "fabric_game_canceled overlay=$overlay")
        }
        ClientReceiveMessageEvents.CHAT_CANCELED.register { message, _, sender, _, _ ->
            onChatMessage(message, "fabric_chat_canceled sender=${sender?.name ?: "null"}")
        }
        debug("Registered Fabric chat receive hooks")
    }

    private fun registerWorldRenderHooks() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register { context ->
            renderWorldDebug(context)
        }
        debug("Registered world debug render hook")
    }

    fun onChatMessage(message: Component, source: String) {
        feature?.parseTerminalChat(message.string, source)
    }

    private fun registerCommands() {
        if (commandsRegistered) return
        commandsRegistered = true
        debug("Registering /dtm client commands")
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("dtm")
                    .executes {
                        withFeature { it.sendStatus() }
                        1
                    }
                    .then(literal("reset").executes {
                        withFeature { it.resetRun("command") }
                        1
                    })
                    .then(literal("debuglog").executes {
                        withFeature { it.sendDebugLogPath() }
                        1
                    })
                    .then(literal("role")
                        .then(argument("player", StringArgumentType.word())
                            .then(argument("class", StringArgumentType.word()).executes { context ->
                                val player = StringArgumentType.getString(context, "player")
                                val dungeonClass = StringArgumentType.getString(context, "class")
                                withFeature { it.assignPlayerRole(player, dungeonClass) }
                                1
                            })
                        )
                    )
                    .then(literal("unrole")
                        .then(argument("player", StringArgumentType.word()).executes { context ->
                            val player = StringArgumentType.getString(context, "player")
                            withFeature { it.clearPlayerRole(player) }
                            1
                        })
                    )
                    .then(literal("clearroles").executes {
                        withFeature { it.clearManualRoles() }
                        1
                    })
                    .then(literal("complete")
                        .then(argument("class", StringArgumentType.word()).executes { context ->
                            val dungeonClass = StringArgumentType.getString(context, "class")
                            withFeature { it.markClassComplete(dungeonClass, "command") }
                            1
                        })
                    )
                    .then(literal("pending")
                        .then(argument("class", StringArgumentType.word()).executes { context ->
                            val dungeonClass = StringArgumentType.getString(context, "class")
                            withFeature { it.markClassPending(dungeonClass) }
                            1
                        })
                    )
                    .then(literal("section")
                        .then(argument("section", StringArgumentType.word()).executes { context ->
                            val section = StringArgumentType.getString(context, "section")
                            withFeature { it.setSection(section) }
                            1
                        })
                    )
                    .then(literal("pos")
                        .then(argument("x", FloatArgumentType.floatArg())
                            .then(argument("y", FloatArgumentType.floatArg()).executes { context ->
                                val newX = FloatArgumentType.getFloat(context, "x")
                                val newY = FloatArgumentType.getFloat(context, "y")
                                withFeature { it.setHudPosition(newX, newY) }
                                1
                            })
                        )
                    )
                    .then(literal("move")
                        .then(argument("dx", FloatArgumentType.floatArg())
                            .then(argument("dy", FloatArgumentType.floatArg()).executes { context ->
                                val dx = FloatArgumentType.getFloat(context, "dx")
                                val dy = FloatArgumentType.getFloat(context, "dy")
                                withFeature { it.moveHud(dx, dy) }
                                1
                            })
                        )
                    )
                    .then(literal("scale")
                        .then(argument("scale", FloatArgumentType.floatArg(0.25f, 4.0f)).executes { context ->
                            val newScale = FloatArgumentType.getFloat(context, "scale")
                            withFeature { it.setHudScale(newScale) }
                            1
                        })
                    )
                    .then(literal("testfill").executes {
                        withFeature { it.fillTestParty() }
                        1
                    })
                    .then(literal("testdone").executes {
                        withFeature { it.markAllComplete("testdone command") }
                        1
                    })
                    .then(literal("testclear").executes {
                        withFeature {
                            it.resetRun("testclear command")
                            it.clearManualRoles()
                        }
                        1
                    })
                    .then(literal("boxes").executes {
                        withFeature { it.toggleMatchBoxes() }
                        1
                    })
                    .then(literal("fake")
                        .then(argument("player", StringArgumentType.word()).executes { context ->
                            val player = StringArgumentType.getString(context, "player")
                            withFeature { it.markPlayerComplete(player, "fake command") }
                            1
                        })
                    )
                    .then(literal("chat")
                        .then(argument("message", greedyString()).executes { context ->
                            val message = StringArgumentType.getString(context, "message")
                            withFeature { it.parseTerminalChat(message, "dtm_chat_command") }
                            1
                        })
                    )
            )
        }
    }

    private inline fun withFeature(action: (DungeonTerminalMapFeature) -> Unit) {
        val current = feature ?: return
        action(current)
    }

    @Suppress("UNCHECKED_CAST")
    private fun ensureDevonianSubcategory(category: Categories, subcategory: String): String? {
        return runCatching {
            val categoryField = category.javaClass.getDeclaredField("subcategories")
            categoryField.isAccessible = true
            val existingSubcategories = categoryField.get(category) as List<String>
            if (subcategory !in existingSubcategories) {
                categoryField.set(category, existingSubcategories + subcategory)
            }

            val configClass = Class.forName("com.github.synnerz.devonian.config.Config")
            val categoriesField = configClass.getDeclaredField("categories")
            categoriesField.isAccessible = true
            val categories = categoriesField.get(null) as MutableMap<Categories, MutableMap<String, MutableList<ConfigData<*>>>>
            categories.getValue(category).getOrPut(subcategory) { mutableListOf() }
            subcategory
        }.getOrElse {
            debug("Failed to register Devonian subcategory $subcategory, falling back to Mod: ${it::class.simpleName}: ${it.message}")
            null
        }
    }

    fun debug(message: String) {
        val line = "[${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}] $message"
        logger.info(line)
        runCatching {
            debugLogFile.parentFile.mkdirs()
            debugLogFile.appendText(line + System.lineSeparator(), Charsets.UTF_8)
        }
    }

    fun debugLogPath(): String = debugLogFile.absolutePath
}

class DungeonTerminalMapFeature(
    configCategory: Categories,
    private val configTab: String,
) : TextHudFeature(
    "dungeonTerminalMap",
    "Shows terminal-phase class assignments and completion state.",
    configCategory,
    "catacombs",
    displayName = "Dungeon Terminal Map",
    subcategory = configTab,
) {
    private companion object {
        private const val FEATURE_CONFIG = "dungeonTerminalMap"
        private const val DEDUPE_MILLIS = 1_500L
        private const val MAP_IMAGE_WIDTH = 96
        private const val MAP_IMAGE_HEIGHT = 296
        private const val MARKER_RADIUS = 8
        private const val POSITION_MATCH_MAX_DISTANCE_SQ = 144.0
        private const val POSITION_MATCH_RADIUS = 12.0
        private val TAB_PLAYER_REGEX = Regex(
            "\\[\\d+]\\s+(?:\\[[A-Za-z0-9+]+]\\s+)?(?<name>[A-Za-z0-9_]{1,16})\\s+(?:.+\\s+)?\\((?<class>Healer|Mage|Berserk|Archer|Tank)\\s*[^)]*\\)",
            RegexOption.IGNORE_CASE,
        )
        private val CHAT_COMPLETION_REGEX = Regex(
            "(?:^|\\s)(?<player>[A-Za-z0-9_]{1,16})\\s+(?:completed|activated)\\s+a\\s+(?<kind>terminal|device|lever)!?\\s*(?:\\((?<done>\\d+)\\s*/\\s*(?<total>\\d+)\\))?",
            RegexOption.IGNORE_CASE,
        )

        private fun wp(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): List<WorldPoint> =
            listOf(WorldPoint(x1, y1, z1), WorldPoint(x2, y2, z2))
    }

    private val prefix = "&6[&bDTM&6]&r "
    private val mc: Minecraft get() = Minecraft.getInstance()
    private val classOptions = DungeonClass.entries.map { it.displayName }
    private val sectionOptions = TermSection.entries.map { it.displayName }

    private val displayDivider = addDivider("10", "DISPLAY")
    private val renderHud = addSwitch("11_renderHud", true, "Draw the terminal map during normal gameplay.", "Render HUD", emptySet(), false, configTab)
    private val showEverywhere = addSwitch("12_showEverywhere", false, "Render outside Catacombs.", "Show Everywhere", emptySet(), false, configTab)
    private val bossRoomOnly = addSwitch("13_bossRoomOnly", false, "Only render inside the Floor 7 boss room bounds.", "Boss Room Only", emptySet(), false, configTab)
    private val backgroundOpacity = addTextInput("14_backgroundOpacity", "128", "Background opacity from 0 to 255.", "Background Opacity", emptySet(), configTab)
    private val mapWidth = addTextInput("15_mapWidth", "96", "Map image width in pixels before HUD scale.", "Map Width", emptySet(), configTab)
    private val selectedSection = addSelection("16_selectedSection", 0, sectionOptions, "Terminal section assignment preset.", "Section", emptySet(), configTab)

    private var runtimeStarted = false
    private var wasInDungeon = false
    private var lastPlayerScan = 0L
    private var lastVisibilityCheckAt = 0L
    private var lastVisibilityResult = false
    private var lastRenderStateLog = 0L
    private var renderedOnce = false
    private var recentChatCompletions: MutableMap<String, Long> = linkedMapOf()
    private var detectedPlayers: Map<String, DungeonClass> = emptyMap()
    private var manualAssignments: MutableMap<String, DungeonClass> = linkedMapOf()
    private var completions: MutableMap<DungeonClass, Completion> = mutableMapOf()
    private var completedTasks: MutableMap<String, Completion> = mutableMapOf()
    private var runtimeSection: TermSection? = null
    private var bossRoomSeen = false
    private var terminalPhaseDone = false
    private var showMatchBoxes = false

    override fun getEditText(): List<String> = listOf(
        "&bDungeon Terminal Map",
        "&7Drag this box in Devonian's HUD editor.",
        "&aCompleted terminals turn green.",
    )

    override fun initialize() {
        startRuntime("devonian initialize")

        on<TickEvent> {
            clientTick()
        }

        on<ChatEvent> { event ->
            parseTerminalChat(event.message)
        }
    }

    private fun startRuntime(reason: String) {
        if (runtimeStarted) return
        runtimeStarted = true
        log("Feature runtime started by $reason")
    }

    private fun clientTick() {
        val inDungeon = isDungeonArea()
        if (inDungeon && !wasInDungeon) {
            resetRun("entered dungeon")
        } else if (!inDungeon && wasInDungeon) {
            resetRun("left dungeon")
            detectedPlayers = manualAssignments.toMap()
        }
        wasInDungeon = inDungeon

        val now = System.currentTimeMillis()
        if (inDungeon && now - lastPlayerScan >= 100L) {
            lastPlayerScan = now
            detectedPlayers = scanDungeonPlayers()
        }

        setLines(buildStatusLines())
    }

    fun renderHud(graphics: GuiGraphics) {
        startRuntime("gui render")

        val enabled = isEnabled()
        val visible = shouldRenderCached()
        if (!renderHud.get() || !visible || terminalPhaseDone) {
            logRenderState("Render blocked renderHud=${renderHud.get()} enabled=$enabled shouldRender=$visible showEverywhere=${showEverywhere.get()}")
            return
        }

        drawImpl(graphics)
        if (!renderedOnce) {
            renderedOnce = true
            log("HUD rendered x=$x y=$y scale=$scale players=${detectedPlayers.size} completions=${completions.size}")
        }
    }

    override fun getWidth(): Double = mapWidthValue().toDouble()

    override fun getHeight(): Double = mapHeightValue().toDouble()

    override fun getBounds(): BoundingBox = BoundingBox(
        if (x.isFinite()) x else 10.0,
        if (y.isFinite()) y else 10.0,
        getWidth() * scale,
        getHeight() * scale,
    )

    override fun drawImpl(ctx: GuiGraphics) {
        if (!renderHud.get()) return
        if (terminalPhaseDone) return
        if (!shouldRenderLocation()) return
        drawTerminalMap(ctx, if (x.isFinite()) x.toFloat() else 10f, if (y.isFinite()) y.toFloat() else 10f, scale)
    }

    override fun sampleDraw(ctx: GuiGraphics, mx: Int, my: Int, selected: Boolean) {
        drawTerminalMap(ctx, if (x.isFinite()) x.toFloat() else 10f, if (y.isFinite()) y.toFloat() else 10f, scale)
        val bounds = getBounds()
        val color = if (selected) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt()
        ctx.fill(bounds.x.toInt(), bounds.y.toInt(), (bounds.x + bounds.w).toInt(), bounds.y.toInt() + 1, color)
        ctx.fill(bounds.x.toInt(), (bounds.y + bounds.h).toInt() - 1, (bounds.x + bounds.w).toInt(), (bounds.y + bounds.h).toInt(), color)
        ctx.fill(bounds.x.toInt(), bounds.y.toInt(), bounds.x.toInt() + 1, (bounds.y + bounds.h).toInt(), color)
        ctx.fill((bounds.x + bounds.w).toInt() - 1, bounds.y.toInt(), (bounds.x + bounds.w).toInt(), (bounds.y + bounds.h).toInt(), color)
    }

    fun toggleMatchBoxes() {
        showMatchBoxes = !showMatchBoxes
        send("Terminal match boxes ${if (showMatchBoxes) "shown" else "hidden"}.")
        log("Terminal match boxes toggled show=$showMatchBoxes section=${currentSection().displayName}")
    }

    fun renderMatchBoxes(context: WorldRenderContext) {
        if (!showMatchBoxes || terminalPhaseDone || !shouldRenderLocation()) return
        val cameraPosition = context.gameRenderer().mainCamera.position
        val matrices = context.matrices()
        matrices.pushPose()
        matrices.translate(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z)
        currentSection().tasks
            .filter { it.worldPositions.isNotEmpty() }
            .forEach { task ->
                val complete = completedTasks.containsKey(task.id)
                val color = if (task.isLever()) {
                    DebugBoxColor(1.0f, 0.76f, 0.18f)
                } else {
                    DebugBoxColor.fromArgb(displayRoleFor(task).argb)
                }
                task.worldPositions.forEach { point ->
                    DebugRenderer.renderFilledBox(
                        matrices,
                        context.consumers(),
                        point.matchAabb(),
                        color.red,
                        color.green,
                        color.blue,
                        if (complete) 0.08f else 0.18f,
                    )
                }
            }
        matrices.popPose()
    }

    fun sendStatus() {
        refreshPlayersNow()
        val lines = buildStatusLines()
        if (lines.isEmpty()) {
            send("No terminal map state yet.")
            return
        }
        lines.forEach { send(it) }
    }

    fun sendDebugLogPath() {
        send("Debug log: ${DungeonTerminalMapAddon.debugLogPath()}")
    }

    fun resetRun(reason: String) {
        if (completions.isNotEmpty()) {
            log("Reset terminal completions reason=$reason count=${completions.size}")
        }
        completions = mutableMapOf()
        completedTasks = mutableMapOf()
        runtimeSection = TermSection.S1
        bossRoomSeen = false
        terminalPhaseDone = false
        recentChatCompletions = linkedMapOf()
    }

    fun assignPlayerRole(playerName: String, className: String): Boolean {
        val dungeonClass = DungeonClass.from(className)
        if (dungeonClass == null) {
            send("Unknown class '$className'. Use Healer, Mage, Berserk, Archer, or Tank.")
            return false
        }
        manualAssignments.entries.removeIf { it.key.equals(playerName, true) || it.value == dungeonClass }
        manualAssignments[playerName] = dungeonClass
        detectedPlayers = scanDungeonPlayers()
        send("Assigned $playerName to ${dungeonClass.displayName}.")
        log("Manual role assigned player=$playerName class=${dungeonClass.displayName}")
        return true
    }

    fun clearPlayerRole(playerName: String): Boolean {
        val removed = manualAssignments.entries.removeIf { it.key.equals(playerName, true) }
        detectedPlayers = scanDungeonPlayers()
        send(if (removed) "Cleared manual role for $playerName." else "No manual role for $playerName.")
        return removed
    }

    fun clearManualRoles() {
        val count = manualAssignments.size
        manualAssignments.clear()
        detectedPlayers = scanDungeonPlayers()
        send("Cleared $count manual role assignments.")
    }

    fun markClassComplete(className: String, source: String): Boolean {
        val dungeonClass = DungeonClass.from(className)
        if (dungeonClass == null) {
            send("Unknown class '$className'.")
            return false
        }
        val playerName = detectedPlayers.entries.firstOrNull { it.value == dungeonClass }?.key ?: dungeonClass.displayName
        completions[dungeonClass] = Completion(playerName, System.currentTimeMillis(), source)
        markTaskCompleteForClass(dungeonClass, playerName, source, TerminalCompletionKind.TERMINAL)
        send("Marked ${dungeonClass.displayName} complete.")
        log("Marked terminal complete class=${dungeonClass.displayName} player=$playerName source=$source")
        return true
    }

    fun markClassPending(className: String): Boolean {
        val dungeonClass = DungeonClass.from(className)
        if (dungeonClass == null) {
            send("Unknown class '$className'.")
            return false
        }
        val removed = completions.remove(dungeonClass) != null
        currentSection().tasksFor(dungeonClass).forEach { completedTasks.remove(it.id) }
        send(if (removed) "Marked ${dungeonClass.displayName} pending." else "${dungeonClass.displayName} was already pending.")
        return removed
    }

    fun setSection(sectionName: String): Boolean {
        val section = TermSection.from(sectionName)
        if (section == null) {
            send("Unknown section '$sectionName'. Use s1, s2, s3core/s3, or s4core/s4.")
            return false
        }
        selectedSection.set(sectionOptions.indexOf(section.displayName))
        runtimeSection = section
        terminalPhaseDone = false
        send("Section set to ${section.displayName}.")
        log("Selected terminal section=${section.displayName}")
        return true
    }

    private fun advanceSectionFromChatCounter(done: Int, total: Int) {
        val current = currentSection()
        if (current == TermSection.S4_NO_CORE && done >= total && total == current.terminalCount) {
            terminalPhaseDone = true
            log("Terminal phase done from chat counter $done/$total")
            return
        }
        if (done < total || total != current.terminalCount) {
            if (done >= total) {
                log("Section advance skipped current=${current.displayName} counter=$done/$total expected=${current.terminalCount}")
            }
            return
        }
        val next = when (current) {
            TermSection.S1 -> TermSection.S2
            TermSection.S2 -> TermSection.S3_NO_CORE
            TermSection.S3_NO_CORE -> TermSection.S4_NO_CORE
            TermSection.S4_NO_CORE -> null
        } ?: return
        selectedSection.set(sectionOptions.indexOf(next.displayName))
        runtimeSection = next
        log("Auto advanced terminal section ${current.displayName} -> ${next.displayName} from chat counter $done/$total")
    }

    fun setHudPosition(newX: Float, newY: Float) {
        x = newX.toDouble()
        y = newY.toDouble()
        send("HUD position set to x=${"%.1f".format(Locale.US, newX)}, y=${"%.1f".format(Locale.US, newY)}.")
        log("HUD position set x=$x y=$y")
    }

    fun moveHud(dx: Float, dy: Float) {
        val currentX = if (x.isFinite()) x else 10.0
        val currentY = if (y.isFinite()) y else 10.0
        setHudPosition((currentX + dx).toFloat(), (currentY + dy).toFloat())
    }

    fun setHudScale(newScale: Float) {
        scale = newScale.coerceIn(0.25f, 4.0f)
        send("HUD scale set to ${"%.2f".format(Locale.US, scale)}.")
        log("HUD scale set scale=$scale")
    }

    fun fillTestParty() {
        manualAssignments = linkedMapOf(
            "TestMage" to DungeonClass.MAGE,
            "TestBers" to DungeonClass.BERSERK,
            "TestArch" to DungeonClass.ARCHER,
            "TestTank" to DungeonClass.TANK,
            "TestHeal" to DungeonClass.HEALER,
        )
        detectedPlayers = scanDungeonPlayers()
        send("Filled test party roles.")
    }

    fun markAllComplete(source: String) {
        refreshPlayersNow()
        DungeonClass.entries.forEach { dungeonClass ->
            val playerName = detectedPlayers.entries.firstOrNull { it.value == dungeonClass }?.key ?: dungeonClass.displayName
            completions[dungeonClass] = Completion(playerName, System.currentTimeMillis(), source)
            currentSection().tasksFor(dungeonClass).forEach {
                completedTasks[it.id] = Completion(playerName, System.currentTimeMillis(), source)
            }
        }
        send("Marked all classes complete.")
    }

    fun parseTerminalChat(message: String, source: String = "unknown") {
        val cleaned = message.cleanMc()
        if (cleaned.isBossRoomSignal()) bossRoomSeen = true
        cleaned.lines().forEachIndexed { index, rawLine ->
            parseTerminalLine(rawLine.trim(), source, index)
        }
    }

    fun markPlayerComplete(playerName: String, source: String): Boolean {
        refreshPlayersNow()
        val dungeonClass = detectedPlayers.entries.firstOrNull { it.key.equals(playerName, true) }?.value
        if (dungeonClass == null) {
            send("No detected dungeon class for $playerName.")
            log("Ignored completion for unknown player=$playerName source=$source detected=${detectedPlayersForLog()} manual=${manualAssignmentsForLog()}")
            return false
        }

        completions.putIfAbsent(dungeonClass, Completion(playerName, System.currentTimeMillis(), source))
        markTaskCompleteForClass(dungeonClass, playerName, source)
        return true
    }

    private fun markTaskCompleteForClass(
        dungeonClass: DungeonClass,
        playerName: String,
        source: String,
        completionKind: TerminalCompletionKind = TerminalCompletionKind.fromSource(source),
    ) {
        val section = currentSection()
        val completion = Completion(playerName, System.currentTimeMillis(), source)
        if (section == TermSection.S1 && dungeonClass == DungeonClass.BERSERK && completionKind != TerminalCompletionKind.LEVER) {
            completedTasks["s1_i4"] = completion
            completedTasks["s4_ss"] = completion.copy(source = "$source:s1_i4")
            log("Task completed section=${section.displayName} player=$playerName class=${dungeonClass.displayName} kind=$completionKind task=s1_i4 carry=s4_ss")
            return
        }

        val task = selectTaskNearPlayer(section, playerName, completionKind)
            ?: selectTaskForCompletion(section, dungeonClass, completionKind)
        if (task == null) {
            log("Task mark skipped no_open_task section=${section.displayName} class=${dungeonClass.displayName} kind=$completionKind")
            return
        }

        completedTasks[task.id] = completion
        log("Task completed section=${section.displayName} player=$playerName class=${dungeonClass.displayName} kind=$completionKind task=${task.id} label=${task.label}")
    }

    private fun selectTaskNearPlayer(
        section: TermSection,
        playerName: String,
        completionKind: TerminalCompletionKind,
    ): TerminalTask? {
        if (completionKind == TerminalCompletionKind.DEVICE) return null
        val player = mc.level?.players()?.firstOrNull { it.name.string.equals(playerName, true) } ?: return null
        val candidates = section.tasks.filterNot { completedTasks.containsKey(it.id) }
            .filter { task ->
                when (completionKind) {
                    TerminalCompletionKind.LEVER -> task.isLever()
                    TerminalCompletionKind.TERMINAL -> task.isNumberedTerminal()
                    TerminalCompletionKind.DEVICE -> false
                }
            }
            .filter { it.worldPositions.isNotEmpty() }
        val nearest = candidates
            .mapNotNull { task ->
                task.worldPositions.minOfOrNull { it.distanceSq(player.x, player.y, player.z) }?.let { task to it }
            }
            .minByOrNull { it.second }
        if (nearest == null || nearest.second > POSITION_MATCH_MAX_DISTANCE_SQ) {
            log("Position match skipped player=$playerName kind=$completionKind nearest=${nearest?.second ?: "null"}")
            return null
        }
        log("Position matched player=$playerName kind=$completionKind task=${nearest.first.id} distanceSq=${"%.2f".format(Locale.ROOT, nearest.second)}")
        return nearest.first
    }

    private fun selectTaskForCompletion(
        section: TermSection,
        dungeonClass: DungeonClass,
        completionKind: TerminalCompletionKind,
    ): TerminalTask? {
        val classTasks = eligibleTasksForClass(section, dungeonClass).filterNot { completedTasks.containsKey(it.id) }
        return when (completionKind) {
            TerminalCompletionKind.LEVER ->
                classTasks.firstOrNull { it.isLever() }
                    ?: section.tasks.firstOrNull { it.isLever() && !completedTasks.containsKey(it.id) }
                    ?: classTasks.firstOrNull()
            TerminalCompletionKind.DEVICE ->
                classTasks.firstOrNull { it.isDevice() }
                    ?: section.tasks.firstOrNull { it.isDevice() && !completedTasks.containsKey(it.id) }
            TerminalCompletionKind.TERMINAL ->
                classTasks.firstOrNull { it.isNumberedTerminal() }
                    ?: classTasks.firstOrNull()
        }
    }

    private fun eligibleTasksForClass(section: TermSection, dungeonClass: DungeonClass): List<TerminalTask> {
        if (section == TermSection.S2 && s1LeversOwner() == DungeonClass.MAGE) {
            return section.tasks.filter { task ->
                if (task.id == "s2_ss") dungeonClass == DungeonClass.MAGE else dungeonClass in task.roles || task.defaultRole == dungeonClass
            }
        }
        if (section != TermSection.S1) return section.tasksFor(dungeonClass)
        return section.tasks.filter { task ->
            displayRoleFor(task) == dungeonClass || (!task.isNumberedTerminal() && dungeonClass in task.roles)
        }
    }

    private fun parseTerminalLine(line: String, source: String, lineIndex: Int): Boolean {
        if (line.isBlank()) return false
        val normalized = line.normalizeChatLine()
        val match = CHAT_COMPLETION_REGEX.find(normalized)
        if (match == null) {
            if (normalized.looksLikeTerminalCompletion()) {
                log("Parse miss source=$source line=$lineIndex normalized='${normalized.safeLog()}'")
            }
            return false
        }
        val playerName = match.groups["player"]?.value ?: return false
        val done = match.groups["done"]?.value?.toIntOrNull()
        val total = match.groups["total"]?.value?.toIntOrNull()
        val kind = match.groups["kind"]?.value?.lowercase(Locale.ROOT) ?: "terminal"
        val completionKind = TerminalCompletionKind.fromKind(kind)
        bossRoomSeen = true
        val dedupeKey = "${playerName.lowercase(Locale.ROOT)}:$kind:${done ?: "?"}/${total ?: "?"}"
        val now = System.currentTimeMillis()
        recentChatCompletions.entries.removeIf { now - it.value > DEDUPE_MILLIS }
        if (recentChatCompletions[dedupeKey]?.let { now - it < DEDUPE_MILLIS } == true) {
            return false
        }
        recentChatCompletions[dedupeKey] = now
        log("Completion parsed source=$source player=$playerName kind=$kind counter=${done ?: "null"}/${total ?: "null"} section=${currentSection().displayName}")
        val marked = markPlayerComplete(playerName, "chat:${completionKind.logName}")
        if (marked && completionKind == TerminalCompletionKind.DEVICE) {
            markEe2DeviceCarryover(playerName)
        }
        if (done != null && total != null) {
            advanceSectionFromChatCounter(done, total)
        }
        return marked
    }

    private fun markEe2DeviceCarryover(playerName: String) {
        if (currentSection() != TermSection.S1) return
        val dungeonClass = detectedPlayers.entries.firstOrNull { it.key.equals(playerName, true) }?.value ?: return
        if (dungeonClass != s1LeversOwner()) return
        if (dungeonClass != DungeonClass.MAGE && dungeonClass != DungeonClass.ARCHER) return
        completedTasks["s2_ss"] = Completion(playerName, System.currentTimeMillis(), "chat:ee2_device")
        log("Marked S2 SS complete from S1 EE2 device player=$playerName class=${dungeonClass.displayName}")
    }

    private fun refreshPlayersNow() {
        val now = System.currentTimeMillis()
        if (now - lastPlayerScan < 100L) return
        lastPlayerScan = now
        detectedPlayers = scanDungeonPlayers()
    }

    private fun scanDungeonPlayers(): Map<String, DungeonClass> {
        val found = linkedMapOf<String, DungeonClass>()
        found.putAll(manualAssignments)
        val connection = mc.connection ?: return found
        for (entry in connection.onlinePlayers) {
            val text = entry.tabListDisplayName?.string ?: continue
            val match = TAB_PLAYER_REGEX.find(text) ?: continue
            val playerName = match.groups["name"]?.value ?: continue
            val dungeonClass = DungeonClass.from(match.groups["class"]?.value)
            if (dungeonClass != null) found[playerName] = dungeonClass
        }
        return found
    }

    private fun detectedPlayersForLog(): String =
        detectedPlayers.entries.joinToString(prefix = "[", postfix = "]") { "${it.key}:${it.value.displayName}" }

    private fun manualAssignmentsForLog(): String =
        manualAssignments.entries.joinToString(prefix = "[", postfix = "]") { "${it.key}:${it.value.displayName}" }

    private fun buildStatusLines(): List<String> {
        val assignments = currentSection().tasks.map { task ->
            val dungeonClass = displayRoleFor(task)
            val playerName = detectedPlayers.entries.firstOrNull { it.value == dungeonClass }?.key ?: "?"
            val done = completedTasks[task.id]?.playerName ?: ""
            val state = if (done.isNotBlank()) "&aDONE" else "&7..."
            "${dungeonClass.chatColor}${dungeonClass.shortName}&7/$playerName: &f${task.label} $state"
        }
        return if (assignments.isEmpty()) {
            listOf("&bTerminal Map: &7No enabled slots")
        } else {
            listOf("&b${currentSection().displayName}") + assignments
        }
    }

    private fun drawTerminalMap(graphics: GuiGraphics, drawX: Float, drawY: Float, drawScale: Float) {
        val section = currentSection()
        val width = mapWidthValue()
        val height = mapHeightValue()

        graphics.pose().pushMatrix()
        graphics.pose().translate(drawX, drawY)
        graphics.pose().scale(drawScale, drawScale)
        graphics.fill(-4, -4, width + 4, height + 4, backgroundColor())
        graphics.blit(RenderPipelines.GUI_TEXTURED, section.texture, 0, 0, 0f, 0f, width, height, width, height)

        section.tasks.forEach { task ->
            val dungeonClass = displayRoleFor(task)
            val complete = completedTasks.containsKey(task.id)
            val markerX = (task.x * width) / MAP_IMAGE_WIDTH
            val markerY = (task.y * height) / MAP_IMAGE_HEIGHT
            drawMarker(graphics, markerX, markerY, task, dungeonClass, complete)
        }

        graphics.pose().popMatrix()
    }

    private fun drawMarker(
        graphics: GuiGraphics,
        x: Int,
        y: Int,
        task: TerminalTask,
        dungeonClass: DungeonClass,
        complete: Boolean,
    ) {
        val size = MARKER_RADIUS * 2 + 1
        val left = x - MARKER_RADIUS
        val top = y - MARKER_RADIUS
        val border = if (complete) 0xFF39D353.toInt() else dungeonClass.argb
        val fill = if (complete) 0xD039D353.toInt() else 0xD0101014.toInt()
        graphics.fill(left, top, left + size, top + size, border)
        graphics.fill(left + 2, top + 2, left + size - 2, top + size - 2, fill)
        drawCentered(graphics, if (complete) "✓" else task.label, left, top + 2, size, 0xFFFFFFFF.toInt())

    }

    private fun drawCentered(graphics: GuiGraphics, text: String, x: Int, y: Int, width: Int, color: Int) {
        val fitted = fitText(text, width - 4)
        val textWidth = mc.font.width(fitted)
        graphics.drawString(mc.font, fitted, x + (width - textWidth) / 2, y, color, true)
    }

    private fun fitText(text: String, maxWidth: Int): String {
        if (mc.font.width(text) <= maxWidth) return text
        var fitted = text
        while (fitted.length > 1 && mc.font.width("$fitted.") > maxWidth) {
            fitted = fitted.dropLast(1)
        }
        return "$fitted."
    }

    private fun currentSection(): TermSection = runtimeSection ?: TermSection.from(selectedSection.getCurrent()) ?: TermSection.S1

    private fun displayRoleFor(task: TerminalTask): DungeonClass {
        if (task.id == "s1_ll" || task.id == "s1_rl") return s1LeversOwner()
        if (task.id == "s2_ss" && s1LeversOwner() == DungeonClass.MAGE) return DungeonClass.MAGE
        if (currentSection() == TermSection.S1 && task.id in setOf("s1_3", "s1_4")) {
            return if (s1LeversOwner() == DungeonClass.MAGE) DungeonClass.ARCHER else DungeonClass.MAGE
        }
        return task.defaultRole
    }

    private fun s1LeversOwner(): DungeonClass {
        val archer = completions[DungeonClass.ARCHER]?.timestamp ?: Long.MAX_VALUE
        val mage = completions[DungeonClass.MAGE]?.timestamp ?: Long.MAX_VALUE
        return if (mage < archer) DungeonClass.MAGE else DungeonClass.ARCHER
    }

    private fun shouldRenderCached(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastVisibilityCheckAt <= 50L) return lastVisibilityResult
        lastVisibilityCheckAt = now
        lastVisibilityResult = isEnabled() && !terminalPhaseDone && shouldRenderLocation()
        return lastVisibilityResult
    }

    private fun shouldRenderLocation(): Boolean {
        if (showEverywhere.get()) return true
        if (!isDungeonArea()) return false
        return !bossRoomOnly.get() || bossRoomSeen || isFloorSevenBossRoom()
    }

    private fun isDungeonArea(): Boolean {
        val area = (Location.area ?: "").lowercase(Locale.ROOT)
        val subarea = (Location.subarea ?: "").lowercase(Locale.ROOT)
        if (subarea.contains("dungeon hub") || subarea.contains("the catacombs") || subarea.contains("catacombs")) return true
        if (area.contains("dungeon hub") || area.contains("the catacombs") || area.contains("catacombs")) return true

        val text = getScoreboardText().lowercase(Locale.ROOT)
        return text.contains("dungeon hub") || text.contains("the catacombs") || text.contains("catacombs")
    }

    private fun isFloorSevenBossRoom(): Boolean {
        val player = mc.player ?: return false
        val x = player.x
        val y = player.y
        val z = player.z
        return x in -8.0..134.0 && y in 0.0..260.0 && z in -8.0..156.0
    }

    private fun getScoreboardText(): String {
        val scoreboard = mc.level?.scoreboard ?: return ""
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return ""
        val lines = scoreboard.listPlayerScores(objective)
            .sortedByDescending { it.value }
            .take(15)
            .map { score ->
                val name = score.ownerName().string
                val team = scoreboard.getPlayersTeam(name)
                PlayerTeam.formatNameForTeam(team, Component.literal(name)).string
            }

        return (listOf(objective.displayName.string) + lines).joinToString("\n")
    }

    private fun addDivider(sortKey: String, label: String): ConfigData.Switch =
        addSwitch("${sortKey}_divider$label", false, "", "__________ $label __________", emptySet(), false, configTab)

    private fun mapWidthValue(): Int = mapWidth.get().toIntOrNull()?.coerceIn(64, 256) ?: 96

    private fun mapHeightValue(): Int = (mapWidthValue() * MAP_IMAGE_HEIGHT) / MAP_IMAGE_WIDTH

    private fun backgroundColor(): Int {
        val alpha = backgroundOpacity.get().toIntOrNull()?.coerceIn(0, 255) ?: 128
        return (alpha shl 24) or 0x101014
    }

    private fun send(message: String) {
        mc.player?.displayClientMessage(Component.literal((prefix + message).colorize()), false)
    }

    private fun logRenderState(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastRenderStateLog < 5_000) return
        lastRenderStateLog = now
        log(message)
    }

    private fun String.cleanMc(): String = replace(Regex("\u00a7."), "").trim()

    private fun String.normalizeChatLine(): String =
        replace(Regex("\\s+"), " ")
            .replace(Regex("^[>»\\s]+"), "")
            .trim()

    private fun String.colorize(): String = replace("&", "\u00a7")

    private fun log(message: String) {
        DungeonTerminalMapAddon.debug(message)
    }

    private data class Completion(
        val playerName: String,
        val timestamp: Long,
        val source: String,
    )

    private enum class DungeonClass(
        val displayName: String,
        val shortName: String,
        val chatColor: String,
        val argb: Int,
    ) {
        HEALER("Healer", "HEA", "&5", 0xFF820DD1.toInt()),
        MAGE("Mage", "MAG", "&b", 0xFF36C6E3.toInt()),
        BERSERK("Berserk", "BER", "&c", 0xFFED240E.toInt()),
        ARCHER("Archer", "ARC", "&6", 0xFFFF9800.toInt()),
        TANK("Tank", "TNK", "&2", 0xFF138717.toInt());

        companion object {
            fun from(name: String?): DungeonClass? {
                if (name == null) return null
                return entries.firstOrNull { it.displayName.equals(name, true) || it.name.equals(name, true) }
            }
        }
    }

    private data class TerminalTask(
        val id: String,
        val label: String,
        val x: Int,
        val y: Int,
        val defaultRole: DungeonClass,
        val roles: Set<DungeonClass> = setOf(defaultRole),
        val worldPositions: List<WorldPoint> = emptyList(),
    ) {
        fun isLever(): Boolean = label.equals("LL", true) || label.equals("RL", true)

        fun isDevice(): Boolean = id.endsWith("_ss")

        fun isNumberedTerminal(): Boolean = !isLever() && !isDevice()
    }

    private data class WorldPoint(val x: Int, val y: Int, val z: Int) {
        fun distanceSq(playerX: Double, playerY: Double, playerZ: Double): Double {
            val dx = x + 0.5 - playerX
            val dy = y + 0.5 - playerY
            val dz = z + 0.5 - playerZ
            return dx * dx + dy * dy + dz * dz
        }

        fun matchAabb(): AABB =
            AABB(
                x + 0.5 - POSITION_MATCH_RADIUS,
                y + 0.5 - POSITION_MATCH_RADIUS,
                z + 0.5 - POSITION_MATCH_RADIUS,
                x + 0.5 + POSITION_MATCH_RADIUS,
                y + 0.5 + POSITION_MATCH_RADIUS,
                z + 0.5 + POSITION_MATCH_RADIUS,
            )
    }

    private data class DebugBoxColor(val red: Float, val green: Float, val blue: Float) {
        companion object {
            fun fromArgb(argb: Int): DebugBoxColor =
                DebugBoxColor(
                    ((argb shr 16) and 0xFF) / 255f,
                    ((argb shr 8) and 0xFF) / 255f,
                    (argb and 0xFF) / 255f,
                )
        }
    }

    private enum class TerminalCompletionKind(val logName: String) {
        TERMINAL("terminal"),
        DEVICE("device"),
        LEVER("lever");

        companion object {
            fun fromKind(kind: String): TerminalCompletionKind =
                when (kind.lowercase(Locale.ROOT)) {
                    "device" -> DEVICE
                    "lever" -> LEVER
                    else -> TERMINAL
                }

            fun fromSource(source: String): TerminalCompletionKind {
                val normalized = source.lowercase(Locale.ROOT)
                return when {
                    "lever" in normalized -> LEVER
                    "device" in normalized -> DEVICE
                    else -> TERMINAL
                }
            }
        }
    }

    private enum class TermSection(
        val displayName: String,
        val texture: ResourceLocation,
        private val aliases: Set<String>,
        val terminalCount: Int,
        val tasks: List<TerminalTask>,
    ) {
        S1(
            "S1",
            ResourceLocation.fromNamespaceAndPath("dungeonterminalmap", "textures/p3/p3s1.png"),
            setOf("s1", "1"),
            7,
            listOf(
                TerminalTask("s1_1", "1", 6, 204, DungeonClass.TANK, worldPositions = wp(111, 113, 73, 110, 113, 73)),
                TerminalTask("s1_2", "2", 6, 174, DungeonClass.TANK, worldPositions = wp(111, 119, 79, 110, 119, 79)),
                TerminalTask("s1_3", "3", 85, 120, DungeonClass.MAGE, worldPositions = wp(89, 112, 92, 90, 112, 92)),
                TerminalTask("s1_4", "4", 85, 87, DungeonClass.MAGE, worldPositions = wp(89, 122, 101, 90, 122, 101)),
                TerminalTask("s1_ss", "D", 6, 116, DungeonClass.HEALER),
                TerminalTask("s1_ll", "LL", 23, 35, DungeonClass.ARCHER, setOf(DungeonClass.ARCHER, DungeonClass.MAGE), wp(94, 124, 113, 94, 125, 113)),
                TerminalTask("s1_rl", "RL", 72, 35, DungeonClass.ARCHER, setOf(DungeonClass.ARCHER, DungeonClass.MAGE), wp(106, 124, 113, 106, 125, 113)),
            ),
        ),
        S2(
            "S2",
            ResourceLocation.fromNamespaceAndPath("dungeonterminalmap", "textures/p3/p3s2.png"),
            setOf("s2", "2"),
            8,
            listOf(
                TerminalTask("s2_1", "1", 85, 208, DungeonClass.TANK, worldPositions = wp(68, 109, 121, 68, 109, 122)),
                TerminalTask("s2_2", "2", 82, 168, DungeonClass.MAGE, worldPositions = wp(59, 120, 122, 59, 119, 123)),
                TerminalTask("s2_ss", "D", 8, 173, DungeonClass.HEALER),
                TerminalTask("s2_3", "3", 85, 129, DungeonClass.BERSERK, worldPositions = wp(47, 109, 121, 47, 109, 122)),
                TerminalTask("s2_4", "4", 15, 83, DungeonClass.ARCHER, worldPositions = wp(39, 108, 143, 39, 108, 142)),
                TerminalTask("s2_5", "5", 85, 129, DungeonClass.BERSERK, worldPositions = wp(40, 124, 122, 40, 124, 123)),
                TerminalTask("s2_ll", "LL", 22, 18, DungeonClass.HEALER, worldPositions = wp(23, 132, 138, 23, 133, 138)),
                TerminalTask("s2_rl", "RL", 68, 35, DungeonClass.ARCHER, worldPositions = wp(27, 124, 127, 27, 125, 127)),
            ),
        ),
        S3_NO_CORE(
            "S3 Core",
            ResourceLocation.fromNamespaceAndPath("dungeonterminalmap", "textures/p3/p3s3.png"),
            setOf("s3", "3", "s3core", "s3c", "3core", "3c"),
            7,
            listOf(
                TerminalTask("s3_1", "1", 12, 254, DungeonClass.TANK, worldPositions = wp(-3, 109, 112, -2, 109, 112)),
                TerminalTask("s3_2", "2", 14, 175, DungeonClass.HEALER, worldPositions = wp(-3, 119, 93, -2, 119, 93)),
                TerminalTask("s3_3", "3", 76, 158, DungeonClass.BERSERK, worldPositions = wp(19, 123, 93, 18, 123, 93)),
                TerminalTask("s3_ss", "D", 16, 122, DungeonClass.HEALER, setOf(DungeonClass.BERSERK, DungeonClass.HEALER, DungeonClass.TANK)),
                TerminalTask("s3_4", "4", 16, 95, DungeonClass.ARCHER, worldPositions = wp(-3, 109, 77, -2, 109, 77)),
                TerminalTask("s3_ll", "LL", 23, 19, DungeonClass.ARCHER, worldPositions = wp(2, 122, 55, 2, 123, 55)),
                TerminalTask("s3_rl", "RL", 73, 19, DungeonClass.ARCHER, worldPositions = wp(14, 122, 55, 14, 123, 55)),
            ),
        ),
        S4_NO_CORE(
            "S4 Core",
            ResourceLocation.fromNamespaceAndPath("dungeonterminalmap", "textures/p3/p3s4.png"),
            setOf("s4", "4", "s4core", "s4c", "4core", "4c"),
            7,
            listOf(
                TerminalTask("s4_1", "1", 10, 202, DungeonClass.TANK, worldPositions = wp(41, 109, 29, 41, 109, 30)),
                TerminalTask("s4_2", "2", 10, 185, DungeonClass.ARCHER, worldPositions = wp(44, 121, 29, 44, 121, 30)),
                TerminalTask("s4_3", "3", 14, 99, DungeonClass.BERSERK, worldPositions = wp(67, 109, 29, 67, 109, 30)),
                TerminalTask("s4_4", "4", 76, 72, DungeonClass.HEALER, worldPositions = wp(72, 115, 48, 72, 114, 47)),
                TerminalTask("s4_ll", "LL", 23, 23, DungeonClass.BERSERK, worldPositions = wp(84, 121, 34, 84, 122, 34)),
                TerminalTask("s4_rl", "RL", 73, 15, DungeonClass.HEALER, worldPositions = wp(86, 128, 46, 86, 129, 46)),
                TerminalTask("s4_ss", "D", 28, 110, DungeonClass.HEALER),
            ),
        );

        fun tasksFor(dungeonClass: DungeonClass): List<TerminalTask> =
            tasks.filter { dungeonClass in it.roles || it.defaultRole == dungeonClass }

        companion object {
            fun from(name: String?): TermSection? {
                if (name == null) return null
                val normalized = name.lowercase(Locale.ROOT).replace(Regex("[\\s_-]"), "")
                return entries.firstOrNull {
                    it.displayName.lowercase(Locale.ROOT).replace(Regex("[\\s_-]"), "") == normalized || normalized in it.aliases
                }
            }
        }
    }
}

private fun String.safeLog(): String =
    replace("\r", "\\r")
        .replace("\n", "\\n")
        .take(500)

private fun String.looksLikeTerminalCompletion(): Boolean {
    val normalized = lowercase(Locale.ROOT)
    val hasAction = "activated" in normalized || "completed" in normalized
    val hasObject = "terminal" in normalized || "device" in normalized || "lever" in normalized
    return hasAction && hasObject
}

private fun String.isBossRoomSignal(): Boolean {
    val normalized = lowercase(Locale.ROOT)
    return "[boss]" in normalized &&
        ("maxor:" in normalized || "storm:" in normalized || "goldor:" in normalized || "necron:" in normalized)
}
