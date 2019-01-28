package gg.rsmod.game.model.entity

import com.google.common.base.MoreObjects
import gg.rsmod.game.fs.def.VarpDef
import gg.rsmod.game.message.Message
import gg.rsmod.game.message.impl.*
import gg.rsmod.game.model.*
import gg.rsmod.game.model.container.ContainerStackType
import gg.rsmod.game.model.container.ItemContainer
import gg.rsmod.game.model.interf.ComponentSet
import gg.rsmod.game.service.game.ItemStatsService
import gg.rsmod.game.sync.block.UpdateBlockType
import java.util.*

/**
 * A [Pawn] that represents a player.
 *
 * @author Tom <rspsmods@gmail.com>
 */
open class Player(override val world: World) : Pawn(world) {

    companion object {
        /**
         * How many tiles a player can 'see' at a time, normally.
         */
        const val NORMAL_VIEW_DISTANCE = 15

        /**
         * How many tiles a player can 'see' at a time when in a 'large' viewport.
         */
        const val LARGE_VIEW_DISTANCE = 127

        /**
         * How many tiles in each direction a player can see at a given time.
         * This should be as far as players can see entities such as ground items
         * and objects.
         */
        const val TILE_VIEW_DISTANCE = 32
    }

    /**
     * A persistent and unique id. This is <strong>not</strong> the index
     * of our [Player] when registered to the [World], it is a value determined
     * when the [Player] first registers their account.
     */
    var uid: Any? = null

    var username = ""

    var privilege = Privilege.DEFAULT

    /**
     * The base region [Tile] is the most bottom-left (south-west) tile where
     * the last known region for this player begins.
     */
    var lastKnownRegionBase: Tile? = null

    /**
     * A flag that indicates whether or not the [login] method has been executed.
     * This is currently used so that we don't send player updates when the player
     * hasn't been fully initialized. We can test later to see if this is even
     * necessary.
     */
    var initiated = false

    /**
     * The index that was assigned to a [Player] when they are first registered to the
     * [World]. This is needed to remove local players from the synchronization task
     * as once that logic is reached, the local player would have an index of [-1].
     */
    var lastIndex = -1

    /**
     * A flag which indicates the player is attempting to log out. There can be
     * certain circumstances where the player should not be unregistered from
     * the world.
     *
     * For example: when the player is in combat.
     */
    @Volatile private var pendingLogout = false

    /**
     * A flag which indicates that our [FORCE_DISCONNECTION_TIMER] must be set
     * when [pendingLogout] logic is handled.
     */
    @Volatile private var setDisconnectionTimer = false

    private val skillSet by lazy { SkillSet(maxSkills = world.gameContext.skillCount) }

    val inventory by lazy { ItemContainer(world.definitions, 28, ContainerStackType.NORMAL) }

    val equipment by lazy { ItemContainer(world.definitions, 14, ContainerStackType.NORMAL) }

    val bank by lazy { ItemContainer(world.definitions, 800, ContainerStackType.STACK) }

    val interfaces by lazy { ComponentSet(this) }

    val varps  by lazy { VarpSet(maxVarps = world.definitions.getCount(VarpDef::class.java)) }

    /**
     * Some areas have a 'large' viewport. Which means the player's client is
     * able to render more entities in a larger radius than normal.
     */
    private var largeViewport = false

    /**
     * The players in our viewport, including ourselves. This list should not
     * be used outside of our synchronization task.
     */
    val localPlayers = arrayListOf<Player>()

    /**
     * The npcs in our viewport. This list should not be used outside of our
     * synchronization task.
     */
    val localNpcs = arrayListOf<Npc>()

    val otherPlayerSkipFlags = IntArray(2048)

    /**
     * An array that holds the last-known [Tile.to30BitInteger] for every player
     * according to this [Player]. This can vary from player to player, since
     * on log-in, this array will be filled with [0]s for this [Player].
     */
    val otherPlayerTiles = IntArray(2048)

    /**
     * A flag that represents whether or not we want to remove our
     * [ComponentSet.currentMainScreenInterface] from our [ComponentSet.visible] map
     * near the end of the next available game cycle.
     *
     * It can't be removed immediately due to the [CloseMainInterfaceMessage]
     * being received before [ClickButtonMessage], which leads to the server
     * thinking that the player is trying to click a button on an interface
     * that's not in their [ComponentSet.visible] map.
     */
    var closeMainInterface = false

    /**
     * Persistent attributes which must be saved from our system and loaded
     * when needed. This map does not support storing [Double]s as we convert
     * every double into an [Int] when loading. This is done because some
     * parsers can interpret [Number]s differently, so we want to force every
     * [Number] to an [Int], explicitly. If you wish to store a [Double], you
     * can multiply your value by [100] and then divide it on login as a work-
     * around.
     */
    private val persistentAttr: MutableMap<String, Any> = hashMapOf()

    val looks = intArrayOf(9, 14, 109, 26, 33, 36, 42)

    val lookColors = intArrayOf(0, 3, 2, 0, 0)

    var weight = 0.0

    var gender = Gender.MALE

    var skullIcon = -1

    var runEnergy = 100.0

    override fun getType(): EntityType = EntityType.PLAYER

    /**
     * Checks if the player is running. We assume that the [Varp] with id of
     * [173] is the running state varp.
     */
    override fun isRunning(): Boolean = varps[173].state != 0

    override fun getSize(): Int = 1

    override fun getCurrentHp(): Int = getSkills().getCurrentLevel(3)

    override fun getMaxHp(): Int = getSkills().getMaxLevel(3)

    override fun setCurrentHp(level: Int) {
        getSkills().setCurrentLevel(3, level)
    }

    override fun addBlock(block: UpdateBlockType) {
        val bits = world.playerUpdateBlocks.updateBlocks[block]!!
        blockBuffer.addBit(bits.bit)
    }

    override fun hasBlock(block: UpdateBlockType): Boolean {
        val bits = world.playerUpdateBlocks.updateBlocks[block]!!
        return blockBuffer.hasBit(bits.bit)
    }

    /**
     * Logic that should be executed every game cycle, before
     * [gg.rsmod.game.sync.task.PlayerSynchronizationTask].
     *
     * Note that this method may be handled in parallel, so be careful with race
     * conditions if any logic may modify other [Pawn]s.
     */
    override fun cycle() {
        var calculateWeight = false
        var calculateBonuses = false

        if (pendingLogout) {

            /**
             * If a channel is suddenly inactive (disconnected), we don't to 
             * immediately unregister the player. However, we do want to
             * unregister the player abruptly if a certain amount of time
             * passes since their channel disconnected.
             */
            if (setDisconnectionTimer) {
                timers[FORCE_DISCONNECTION_TIMER] = 250 // 2 mins 30 secs
                setDisconnectionTimer = false
            }

            /**
             * A player should only be unregistered from the world when they
             * do not have [ACTIVE_COMBAT_TIMER] or its cycles are <= 0, or if
             * their channel has been inactive for a while.
             *
             * We do allow players to disconnect even if they are in combat, but
             * only if the most recent damage dealt to them are by npcs.
             */
            val stopLogout = timers.has(ACTIVE_COMBAT_TIMER) && damageMap.getAll(type = EntityType.PLAYER, timeFrameMs = 10_000).isNotEmpty()
            val forceLogout = timers.exists(FORCE_DISCONNECTION_TIMER) && !timers.has(FORCE_DISCONNECTION_TIMER)

            if (!stopLogout || forceLogout) {
                if (lock.canLogout()) {
                    handleLogout()
                    return
                }
            }
        }

        val oldRegion = lastTile?.toRegionId() ?: -1
        if (oldRegion != tile.toRegionId()) {
            if (oldRegion != -1) {
                world.plugins.executeRegionExit(this, oldRegion)
            }
            world.plugins.executeRegionEnter(this, tile.toRegionId())
        }

        if (inventory.dirty) {
            write(SetItemContainerMessage(parent = 149, child = 0, containerKey = 93, items = inventory.getBackingArray()))
            inventory.dirty = false
            calculateWeight = true
        }

        if (equipment.dirty) {
            write(SetItemContainerMessage(containerKey = 94, items = equipment.getBackingArray()))
            equipment.dirty = false
            calculateWeight = true
            calculateBonuses = true

            addBlock(UpdateBlockType.APPEARANCE)
        }

        if (bank.dirty) {
            write(SetItemContainerMessage(containerKey = 95, items = bank.getBackingArray()))
            bank.dirty = false
        }

        if (calculateWeight || calculateBonuses) {
            calculateWeightAndBonus(weight = calculateWeight, bonuses = calculateBonuses)
        }

        timerCycle()

        hitsCycle()

        for (i in 0 until varps.maxVarps) {
            if (varps.isDirty(i)) {
                val varp = varps[i]
                val message = when {
                    varp.state >= -Byte.MAX_VALUE && varp.state <= Byte.MAX_VALUE -> SetSmallVarpMessage(varp.id, varp.state)
                    else -> SetBigVarpMessage(varp.id, varp.state)
                }
                write(message)
            }
        }
        varps.clean()

        for (i in 0 until getSkills().maxSkills) {
            if (getSkills().isDirty(i)) {
                write(SendSkillMessage(skill = i, level = getSkills().getCurrentLevel(i), xp = getSkills().getCurrentXp(i).toInt()))
            }
        }
        getSkills().clean()
    }

    /**
     * Logic that should be executed every game cycle, after
     * [gg.rsmod.game.sync.task.PlayerSynchronizationTask].
     *
     * Note that this method may be handled in parallel, so be careful with race
     * conditions if any logic may modify other [Pawn]s.
     */
    fun postCycle() {
        /**
         * Close the main interface if it's pending.
         */
        if (closeMainInterface) {
            interfaces.closeMain()
            closeMainInterface = false
        }

        /**
         * Flush the channel at the end.
         */
        channelFlush()
    }

    /**
     * Handles the logic that must be executed once a player has successfully
     * logged out. This means all the prerequisites have been met for the player
     * to log out of the [world].
     *
     * The [Client] implementation overrides this method and will handle saving
     * data for the player and call this super method at the end.
     */
    protected open fun handleLogout() {
        interruptPlugins()
        world.unregister(this)
    }

    /**
     * Requests for this player to log out. However, the player may not be able
     * to log out immediately under certain circumstances.
     */
    fun requestLogout() {
        pendingLogout = true
        setDisconnectionTimer = true
    }

    /**
     * Registers this player to the [world].
     */
    fun register(): Boolean {
        return world.register(this)
    }

    /**
     * Handles any logic that should be executed upon log in.
     */
    fun login() {
        if (getType().isHumanControlled()) {
            localPlayers.add(this)
            write(LoginRegionMessage(index, tile, world.xteaKeyService))
        }

        initiated = true
        world.plugins.executeLogin(this)
    }

    fun calculateWeightAndBonus(weight: Boolean, bonuses: Boolean = true) {
        world.getService(ItemStatsService::class.java).ifPresent { s ->

            if (weight) {
                val inventoryWeight = inventory.filterNotNull().sumByDouble { s.get(it.id)?.weight ?: 0.0 }
                val equipmentWeight = equipment.filterNotNull().sumByDouble { s.get(it.id)?.weight ?: 0.0 }
                this.weight = inventoryWeight + equipmentWeight
                write(WeightMessage(this.weight.toInt()))
            }

            if (bonuses) {
                Arrays.fill(equipmentBonuses, 0)
                for (i in 0 until equipment.capacity) {
                    val item = equipment[i] ?: continue
                    val stats = s.get(item.id) ?: continue
                    stats.bonuses.forEachIndexed { index, bonus -> equipmentBonuses[index] += bonus }
                }
            }
        }
    }

    fun setLargeViewport(largeViewport: Boolean) {
        this.largeViewport = largeViewport
    }

    fun hasLargeViewport(): Boolean = largeViewport

    /**
     * Checks if the player is registered to a [PawnList] as they should be
     * solely responsible for write access on the index. Being registered
     * to the list should essentially mean the player is registered to the
     * [world].
     *
     * @return [true] if the player is registered to a [PawnList].
     */
    fun isOnline(): Boolean = index > 0

    /**
     * Default method to handle any incoming [Message]s that won't be
     * handled unless the [Player] is controlled by a [Client] user.
     */
    open fun handleMessages() {

    }

    /**
     * Default method to write [Message]s to the attached channel that won't
     * be handled unless the [Player] is controlled by a [Client] user.
     */
    open fun write(vararg messages: Message) {

    }

    open fun write(vararg messages: Any) {

    }

    /**
     * Default method to flush the attached channel. Won't be handled unless
     * the [Player] is controlled by a [Client] user.
     */
    open fun channelFlush() {

    }

    /**
     * Default method to close the attached channel. Won't be handled unless
     * the [Player] is controlled by a [Client] user.
     */
    open fun channelClose() {

    }

    fun message(message: String) {
        write(SendChatboxTextMessage(type = 0, message = message, username = null))
    }

    fun getSkills(): SkillSet = skillSet

    override fun toString(): String = MoreObjects.toStringHelper(this)
            .add("name", username)
            .add("pid", index)
            .toString()
}