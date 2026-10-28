package com.lastasylum.game.engine

// Every entity lives in a pool that is allocated once, so the game loop never allocates.

class Player {
    var x = World.GATE_X
    var y = 14f
    var faceX = 0f
    var faceY = 1f
    var moving = false
    var walkPhase = 0f
    var coins = 0
    var capacity = 1
    val carried = IntArray(4) { -1 }
    var carriedCount = 0
    var pickCooldown = 0f
    var padTimer = 0f
    var padTick = 0f
    var pileTick = 0f
    var flameTarget = -1 // >= 0 rat index, -2 boss, -1 none
    var flameX = 0f
    var flameY = 0f

    /** Coins shown on the back of the doctor. */
    val stackHeight get() = minOf(45, (coins + 4) / 5)
}

object PState {
    const val ARRIVE = 0      // walking in from the road to a spot in the courtyard
    const val LYING = 1       // sick on the ground, waiting for the doctor
    const val CARRIED = 2
    const val BENCH = 3       // waiting room
    const val TO_STATION = 4  // walking from the bench to a free bed / cauldron
    const val FLY = 5         // dropped on a pad, jumping into the bed / cauldron
    const val TREAT = 6
    const val LEAVE_HAPPY = 7
    const val LEAVE_ANGRY = 8
    const val DEAD = 9
}

class Patient {
    var active = false
    var state = 0
    var x = 0f
    var y = 0f
    var z = 0f
    var startZ = 0f
    var faceX = 0f
    var faceY = 1f
    var health = 1f      // drops while lying in the courtyard
    var patience = 1f    // drops while waiting on the bench
    var timer = 0f
    var station = -1
    var bench = -1
    var reservedBy = -1  // orderly index heading for this patient
    var carrier = -1     // 0 = the doctor, i + 1 = orderly i
    var slot = 0         // height in the carried stack
    var look = 0         // visual variant
    var walkPhase = 0f
    var fromX = 0f
    var fromY = 0f
    var mood = 0         // 0 none, 1 happy, 2 angry
    val path = Path()
}

object StationKind {
    const val BATH = 0
    const val BED = 1
}

class Station(
    val kind: Int,
    val x: Float,          // the bed / cauldron itself
    val y: Float,
    val padX: Float,       // the dashed drop pad
    val padY: Float,
    val pile: Int,         // pile index where the cured patient pays
    val room: Int,         // -1 for the bath
) {
    var unlocked = false
    var nurse = false
    var occupant = -1
    var reserved = -1
    var progress = 0f
    var doctorNear = false
    val free get() = unlocked && occupant < 0 && reserved < 0
}

class Pile(val x: Float, val y: Float, val w: Float, val h: Float) {
    var value = 0
    var enabled = false
    fun contains(px: Float, py: Float) =
        px > x - w / 2 - 0.15f && px < x + w / 2 + 0.15f && py > y - h / 2 - 0.15f && py < y + h / 2 + 0.15f
}

object PadKind {
    const val ROOM = 0
    const val NURSE = 1
    const val BATH = 2
    const val CARRY = 3
    const val TOWER = 4
    const val ORDERLY = 5
    const val SPEED = 6
    const val FENCE = 7
    const val PRICE = 8
}

class PadDef(
    val kind: Int,
    val arg: Int,
    val x: Float,
    val y: Float,
    val cost: Int,
    val requires: Int, // pad index that must be bought first, -1 for none
    val label: String,
)

class Orderly {
    var active = false
    var x = 0f
    var y = 0f
    var faceX = 0f
    var faceY = 1f
    var state = 0       // 0 idle, 1 going to patient, 2 carrying
    var target = -1
    var carrying = -1
    var walkPhase = 0f
    var think = 0f
    val path = Path()
}

class Rat {
    var active = false
    var x = 0f
    var y = 0f
    var z = 0f
    var vx = 0f
    var vy = 0f
    var hp = 0f
    var maxHp = 0f
    var speed = 0f
    var hop = 0f        // > 0 while jumping over the fence
    var life = 0f       // seconds left before the rat runs back out
    var hit = 0f        // flash when damaged
    var phase = 0f
}

class Boss {
    var active = false
    var x = 0f
    var y = 0f
    var hp = 0f
    var maxHp = 0f
    var state = 0       // 0 walking in, 1 smashing the fence, 2 inside the courtyard, 3 leaving
    var timer = 0f      // time spent inside the courtyard
    var hit = 0f
    var phase = 0f
    var level = 0
}

class Tower(val x: Float, val y: Float) {
    var active = false
    var cooldown = 0f
    var aimX = 0f
    var aimY = 1f
}

class Arrow {
    var active = false
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var life = 0f
    var target = -1 // rat index or -2 for the boss
}

object FxKind {
    const val COIN = 0
    const val PLUS = 1
    const val FLAME = 2
    const val NUMBER = 3
    const val SMOKE = 4
    const val CONFETTI = 5
    const val SKULL = 6
}

object CoinDest {
    const val NONE = 0
    const val PLAYER = 1
    const val PILE = 2
}

class Fx {
    var active = false
    var kind = 0
    var x = 0f
    var y = 0f
    var z = 0f
    var vx = 0f
    var vy = 0f
    var vz = 0f
    var sx = 0f
    var sy = 0f
    var sz = 0f
    var tx = 0f
    var ty = 0f
    var tz = 0f
    var t = 0f
    var life = 0f
    var value = 0
    var dest = 0
    var destIndex = 0
    var color = 0
}
