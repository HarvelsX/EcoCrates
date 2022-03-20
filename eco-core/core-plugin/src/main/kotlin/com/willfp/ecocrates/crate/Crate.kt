package com.willfp.ecocrates.crate

import com.willfp.eco.core.EcoPlugin
import com.willfp.eco.core.config.interfaces.Config
import com.willfp.eco.core.data.keys.PersistentDataKey
import com.willfp.eco.core.data.keys.PersistentDataKeyType
import com.willfp.eco.core.data.profile
import com.willfp.eco.core.gui.menu
import com.willfp.eco.core.gui.menu.MenuBuilder
import com.willfp.eco.core.gui.slot
import com.willfp.eco.core.gui.slot.FillerMask
import com.willfp.eco.core.gui.slot.MaskItems
import com.willfp.eco.core.items.CustomItem
import com.willfp.eco.core.items.Items
import com.willfp.eco.core.items.builder.ItemStackBuilder
import com.willfp.eco.core.placeholder.PlayerPlaceholder
import com.willfp.eco.util.NumberUtils
import com.willfp.eco.util.StringUtils
import com.willfp.eco.util.formatEco
import com.willfp.eco.util.savedDisplayName
import com.willfp.ecocrates.crate.placed.HologramFrame
import com.willfp.ecocrates.crate.placed.particle.ParticleAnimations
import com.willfp.ecocrates.crate.placed.particle.ParticleData
import com.willfp.ecocrates.crate.reroll.ReRollGUI
import com.willfp.ecocrates.crate.roll.Roll
import com.willfp.ecocrates.crate.roll.RollOptions
import com.willfp.ecocrates.crate.roll.Rolls
import com.willfp.ecocrates.event.CrateOpenEvent
import com.willfp.ecocrates.event.CrateRewardEvent
import com.willfp.ecocrates.reward.Reward
import com.willfp.ecocrates.reward.Rewards
import com.willfp.ecocrates.util.ConfiguredFirework
import com.willfp.ecocrates.util.ConfiguredSound
import com.willfp.ecocrates.util.PlayableSound
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.util.Vector
import java.util.Objects
import java.util.UUID

class Crate(
    private val config: Config,
    private val plugin: EcoPlugin
) {
    val id = config.getString("id")

    val name = config.getFormattedString("name")

    val hologramFrames = config.getSubsections("placed.hologram.frames")
        .map { HologramFrame(it.getInt("tick"), it.getFormattedStrings("lines")) }

    val hologramTicks = config.getInt("placed.hologram.ticks")

    val hologramHeight = config.getDouble("placed.hologram.height")

    val isShowingRandomReward = config.getBool("placed.random-reward.enabled")

    val randomRewardHeight = config.getDouble("placed.random-reward.height")

    val randomRewardDelay = config.getInt("placed.random-reward.delay")

    val randomRewardName = config.getFormattedString("placed.random-reward.name")

    val particles = config.getSubsections("placed.particles").map {
        ParticleData(
            Particle.valueOf(it.getString("particle").uppercase()),
            ParticleAnimations.getByID(it.getString("animation")) ?: ParticleAnimations.SPIRAL
        )
    }

    val key = CustomItem(
        plugin.namespacedKeyFactory.create("${id}_key"),
        { it.getAsKey() == this },
        Items.lookup(config.getString("key.item")).item
            .clone().apply { setAsKeyFor(this@Crate) }
    ).apply { register() }

    val keyLore = config.getFormattedStrings("key.lore")

    val rewards = config.getStrings("rewards").mapNotNull { Rewards.getByID(it) }

    val permission: Permission =
        Bukkit.getPluginManager().getPermission("ecocrates.open.$id") ?: Permission(
            "ecocrates.open.$id",
            "Allows opening the $id crate",
            PermissionDefault.TRUE
        ).apply {
            addParent(Bukkit.getPluginManager().getPermission("ecocrates.open.*")!!, true)
            Bukkit.getPluginManager().addPermission(this)
        }

    val canReroll = config.getBool("can-reroll")

    private val keysKey: PersistentDataKey<Int> = PersistentDataKey(
        plugin.namespacedKeyFactory.create("${id}_keys"),
        PersistentDataKeyType.INT,
        0
    ).player()

    private val opensKey: PersistentDataKey<Int> = PersistentDataKey(
        plugin.namespacedKeyFactory.create("${id}_opens"),
        PersistentDataKeyType.INT,
        0
    ).player()

    private val rollFactory = Rolls.getByID(config.getString("roll"))!!

    private val previewGUI = menu(config.getInt("preview.rows")) {
        setMask(
            FillerMask(
                MaskItems.fromItemNames(config.getStrings("preview.mask.items")),
                *config.getStrings("preview.mask.pattern").toTypedArray()
            )
        )

        setTitle(config.getFormattedString("preview.title"))

        for (reward in rewards) {
            setSlot(
                reward.displayRow,
                reward.displayColumn,
                slot(reward.getDisplay()) {
                    setUpdater { player, _, _ -> reward.getDisplay(player, this@Crate) }
                }
            )
        }
    }

    private val openSound = PlayableSound(
        config.getSubsections("open.sounds")
            .map { ConfiguredSound.fromConfig(it) }
    )

    private val openMessages = config.getStrings("open.messages")

    private val openBroadcasts = config.getStrings("open.broadcasts")

    private val openCommands = config.getStrings("open.commands")

    private val finishSound = PlayableSound(
        config.getSubsections("finish.sounds")
            .map { ConfiguredSound.fromConfig(it) }
    )

    private val finishFireworks = config.getSubsections("finish.fireworks")
        .map { ConfiguredFirework.fromConfig(it) }

    private val finishMessages = config.getStrings("finish.messages")

    private val finishBroadcasts = config.getStrings("finish.broadcasts")

    private val finishCommands = config.getStrings("finish.commands")

    init {
        PlayerPlaceholder(
            plugin,
            "${id}_keys",
        ) { getKeys(it).toString() }.register()

        PlayerPlaceholder(
            plugin,
            "${id}_opens",
        ) { getOpens(it).toString() }.register()
    }

    private fun makeRoll(player: Player, location: Location, reward: Reward, isReroll: Boolean = false): Roll {
        val display = mutableListOf<Reward>()

        // Add three to the scroll times so that it lines up
        for (i in 0..(35 + 3)) {
            display.add(getRandomReward(player, displayWeight = true)) // Fill roll with display weight items
        }

        return rollFactory.create(
            RollOptions(
                reward,
                this,
                this.plugin,
                player,
                location,
                isReroll
            )
        )
    }

    private fun hasRanOutOfRewardsAndNotify(player: Player): Boolean {
        val ranOut = rewards.all { it.getWeight(player) <= 0 || it.getDisplayWeight(player) <= 0 }

        if (ranOut) {
            player.sendMessage(plugin.langYml.getMessage("all-rewards-used"))
        }

        return ranOut
    }

    private fun getRandomReward(player: Player, displayWeight: Boolean = false): Reward {
        val selection = rewards.toList().shuffled()

        // Limit to 1024 in case RNG breaks.
        for (i in 0..1024) {
            val reward = selection[i % rewards.size]
            if (NumberUtils.randFloat(0.0, 100.0) < reward.getPercentageChance(player, selection, displayWeight)) {
                return reward
            }
        }

        return selection.first()
    }

    private fun hasKeysAndNotify(player: Player, physicalKey: Boolean = false): Boolean {
        if (getKeys(player) == 0) {
            return if (!physicalKey) {
                player.sendMessage(plugin.langYml.getMessage("not-enough-keys").replace("%crate%", this.name))
                false
            } else {
                val physical = hasPhysicalKey(player)
                if (!physical) {
                    player.sendMessage(plugin.langYml.getMessage("not-enough-keys").replace("%crate%", this.name))
                }

                physical
            }
        }

        return true
    }

    private fun hasPermissionAndNotify(player: Player): Boolean {
        val hasPermission = player.hasPermission(permission)

        if (!hasPermission) {
            player.sendMessage(plugin.langYml.getMessage("no-crate-permission").replace("%crate%", this.name))
        }

        return hasPermission
    }

    private fun usePhysicalKey(player: Player) {
        val itemStack = player.inventory.itemInMainHand
        if (key.matches(itemStack)) {
            itemStack.amount -= 1
            if (itemStack.amount == 0) {
                itemStack.type = Material.AIR
            }
        }
    }

    internal fun addToKeyGUI(builder: MenuBuilder) {
        builder.setSlot(
            config.getInt("keygui.row"),
            config.getInt("keygui.column"),
            slot(
                ItemStackBuilder(Items.lookup(config.getString("keygui.item"))).build()
            ) {
                onLeftClick { event, _, _ ->
                    if (config.getBool("keygui.left-click-opens")) {
                        val player = event.whoClicked as Player
                        player.closeInventory()
                        openWithKey(player)
                    }
                }

                onRightClick { event, _, _ ->
                    if (config.getBool("keygui.right-click-previews")) {
                        val player = event.whoClicked as Player
                        player.closeInventory()
                        previewForPlayer(player)
                    }
                }

                onShiftLeftClick { event, _, _ ->
                    event.whoClicked.closeInventory()
                    config.getFormattedStrings("keygui.shift-left-click-message")
                        .forEach { event.whoClicked.sendMessage(it) }
                }

                setUpdater { player, _, previous ->
                    previous.apply {
                        itemMeta = itemMeta?.apply {
                            lore = config.getStrings("keygui.lore")
                                .map { it.replace("%keys%", getKeys(player).toString()) }
                                .map { it.formatEco(player) }
                        }
                    }
                    previous
                }
            }
        )
    }

    fun getRandomRewards(player: Player, amount: Int, displayWeight: Boolean = false): List<Reward> {
        return (0..amount).map { getRandomReward(player, displayWeight) }
    }

    fun openPhysical(player: Player, location: Location, physicalKey: Boolean) {
        val nicerLocation = location.clone().add(0.5, 1.5, 0.5)

        if (!hasKeysAndNotify(player, physicalKey = physicalKey)) {
            val vector = player.location.clone().subtract(nicerLocation.toVector())
                .toVector()
                .normalize()
                .add(Vector(0.0, 1.0, 0.0))
                .multiply(plugin.configYml.getDouble("no-key-velocity"))

            player.velocity = vector

            return
        }

        openWithKey(player, nicerLocation, physicalKey)
    }

    fun openWithKey(player: Player, location: Location? = null, physicalKey: Boolean = false) {
        if (!hasKeysAndNotify(player, physicalKey = true)) {
            return
        }

        // Goes here rather than open() to keep force opening working
        if (!hasPermissionAndNotify(player)) {
            return
        }

        if (open(player, location, physicalKey)) {
            if (physicalKey) {
                usePhysicalKey(player)
            } else {
                adjustKeys(player, -1)
            }
        }
    }

    fun open(
        player: Player,
        location: Location? = null,
        physicalKey: Boolean = false,
        isReroll: Boolean = false
    ): Boolean {
        /* Prevent server crashes */
        if (hasRanOutOfRewardsAndNotify(player)) {
            return false
        }

        if (player.isOpeningCrate) {
            return false
        }

        val loc = location ?: player.eyeLocation

        val event = CrateOpenEvent(player, this, physicalKey, getRandomReward(player), isReroll)
        Bukkit.getPluginManager().callEvent(event)

        if (!isReroll) {
            openSound.play(loc)

            openCommands.map { it.replace("%player%", player.name) }
                .forEach { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), it) }

            openMessages.map { it.replace("%reward%", event.reward.displayName) }
                .map { plugin.langYml.prefix + StringUtils.format(it, player) }
                .forEach { player.sendMessage(it) }

            openBroadcasts.map { it.replace("%reward%", event.reward.displayName) }
                .map { it.replace("%player%", player.savedDisplayName) }
                .map { plugin.langYml.prefix + StringUtils.format(it, player) }
                .forEach { Bukkit.broadcastMessage(it) }
        }

        val roll = makeRoll(player, loc, event.reward, isReroll = isReroll)
        var tick = 0

        plugin.runnableFactory.create {
            roll.tick(tick)

            tick++
            if (!roll.shouldContinueTicking(tick) || !player.isOpeningCrate) {
                it.cancel()
                roll.onFinish()
                player.isOpeningCrate = false
                if (!canReroll || roll.isReroll) handleFinish(roll) else ReRollGUI.open(roll)
            }
        }.runTaskTimer(1, 1)

        player.isOpeningCrate = true
        player.profile.write(opensKey, getOpens(player) + 1)
        roll.roll()

        return true
    }

    fun previewForPlayer(player: Player) {
        previewGUI.open(player)
    }

    fun handleFinish(roll: Roll) {
        val player = roll.player
        val location = roll.location

        val event = CrateRewardEvent(player, this, roll.reward)
        Bukkit.getPluginManager().callEvent(event)

        event.reward.giveTo(player)
        finishSound.play(location)
        finishFireworks.forEach { it.launch(location) }

        finishCommands.map { it.replace("%player%", player.name) }
            .forEach { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), it) }

        finishMessages.map { it.replace("%reward%", event.reward.displayName) }
            .map { plugin.langYml.prefix + StringUtils.format(it, player) }
            .forEach { player.sendMessage(it) }

        finishBroadcasts.map { it.replace("%reward%", event.reward.displayName) }
            .map { it.replace("%player%", player.savedDisplayName) }
            .map { plugin.langYml.prefix + StringUtils.format(it, player) }
            .forEach { Bukkit.broadcastMessage(it) }
    }

    fun adjustKeys(player: OfflinePlayer, amount: Int) {
        player.profile.write(keysKey, player.profile.read(keysKey) + amount)
    }

    fun getKeys(player: OfflinePlayer): Int {
        return player.profile.read(keysKey)
    }

    fun hasPhysicalKey(player: Player): Boolean {
        return key.matches(player.inventory.itemInMainHand)
    }

    fun getOpens(player: OfflinePlayer): Int {
        return player.profile.read(opensKey)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Crate) {
            return false
        }

        return this.id == other.id
    }

    override fun hashCode(): Int {
        return Objects.hash(this.id)
    }

    override fun toString(): String {
        return "Crate{id=$id}"
    }
}

private val openingCrates = mutableSetOf<UUID>()

var Player.isOpeningCrate: Boolean
    get() = openingCrates.contains(this.uniqueId)
    set(value) {
        if (value) {
            openingCrates.add(this.uniqueId)
        } else {
            openingCrates.remove(this.uniqueId)
        }
    }
