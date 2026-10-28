package com.lastasylum.game.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object GameEvent {
    const val COIN = 1
    const val BUY = 2
    const val LEVEL = 3
    const val BOSS = 4
    const val CURED = 5
    const val DROP = 6
}

object Banner {
    const val NONE = 0
    const val LEVEL = 1
    const val WAVE = 2
    const val BOSS = 3
    const val FENCE = 4
    const val UNLOCK = 5
    const val BOSS_DOWN = 6
}

/**
 * The whole simulation. Pure Kotlin, no platform code, no allocation in [update].
 * The UI feeds the joystick through [inputX] / [inputY] and calls [update] once per frame.
 */
class Game(seed: Long = 20261028L) {
    val rng = Rng(seed)
    val player = Player()
    val patients = Array(48) { Patient() }
    val orderlies = Array(2) { Orderly() }
    val rats = Array(64) { Rat() }
    val boss = Boss()
    val arrows = Array(48) { Arrow() }
    val fx = Array(420) { Fx() }
    val towers = arrayOf(Tower(1.7f, 22.6f), Tower(14.3f, 22.6f))

    val piles = arrayOf(
        Pile(13.8f, 18.5f, 1.9f, 1.2f),
        Pile(World.roomX[0] - 0.95f, 5.6f, 1.3f, 1.0f),
        Pile(World.roomX[1] - 0.95f, 5.6f, 1.3f, 1.0f),
        Pile(World.roomX[2] - 0.95f, 5.6f, 1.3f, 1.0f),
        Pile(World.roomX[3] - 0.95f, 5.6f, 1.3f, 1.0f),
    )

    val stations = arrayOf(
        Station(StationKind.BATH, 13.8f, 10.6f, 11.3f, 10.8f, 0, -1),
        Station(StationKind.BATH, 13.8f, 15.4f, 11.3f, 15.6f, 0, -1),
        Station(StationKind.BED, World.roomX[0] - 0.5f, 2.6f, World.roomX[0] + 1.0f, 3.0f, 1, 0),
        Station(StationKind.BED, World.roomX[1] - 0.5f, 2.6f, World.roomX[1] + 1.0f, 3.0f, 2, 1),
        Station(StationKind.BED, World.roomX[2] - 0.5f, 2.6f, World.roomX[2] + 1.0f, 3.0f, 3, 2),
        Station(StationKind.BED, World.roomX[3] - 0.5f, 2.6f, World.roomX[3] + 1.0f, 3.0f, 4, 3),
    )

    val pads = arrayOf(
        PadDef(PadKind.ROOM, 0, World.roomX[0], 8.7f, 30, -1, "Chambre"),        // 0
        PadDef(PadKind.CARRY, 2, 5.0f, 12.4f, 60, 0, "Porter +1"),               // 1
        PadDef(PadKind.NURSE, 0, World.roomX[0] + 0.95f, 5.3f, 80, 0, "Infirmière"), // 2
        PadDef(PadKind.TOWER, 0, 1.7f, 22.6f, 90, 0, "Archère"),                 // 3
        PadDef(PadKind.ROOM, 1, World.roomX[1], 8.7f, 120, 2, "Chambre"),        // 4
        PadDef(PadKind.BATH, 1, 11.3f, 15.6f, 160, 4, "Chaudron"),               // 5
        PadDef(PadKind.NURSE, 1, World.roomX[1] + 0.95f, 5.3f, 200, 4, "Infirmière"), // 6
        PadDef(PadKind.ORDERLY, 0, 8.0f, 11.0f, 250, 5, "Brancardier"),          // 7
        PadDef(PadKind.SPEED, 0, 13.8f, 13.0f, 300, 5, "Remède +35%"),          // 8
        PadDef(PadKind.TOWER, 1, 14.3f, 22.6f, 300, 3, "Archère"),               // 9
        PadDef(PadKind.ROOM, 2, World.roomX[2], 8.7f, 400, 6, "Chambre"),        // 10
        PadDef(PadKind.NURSE, 2, World.roomX[2] + 0.95f, 5.3f, 500, 10, "Infirmière"), // 11
        PadDef(PadKind.PRICE, 0, 5.0f, 15.8f, 450, 8, "Tarifs +50%"),           // 12
        PadDef(PadKind.CARRY, 3, 5.0f, 12.4f, 600, 1, "Porter +1"),              // 13
        PadDef(PadKind.ROOM, 3, World.roomX[3], 8.7f, 900, 11, "Chambre"),       // 14
        PadDef(PadKind.NURSE, 3, World.roomX[3] + 0.95f, 5.3f, 1100, 14, "Infirmière"), // 15
        PadDef(PadKind.ORDERLY, 1, 8.0f, 11.0f, 1200, 7, "Brancardier"),         // 16
        PadDef(PadKind.FENCE, 0, 10.4f, 21.6f, 700, 9, "Palissade x2"),         // 17
        PadDef(PadKind.SPEED, 1, 13.8f, 13.0f, 1500, 8, "Remède +35%"),         // 18
        PadDef(PadKind.PRICE, 1, 5.0f, 15.8f, 2000, 12, "Tarifs +50%"),         // 19
        PadDef(PadKind.CARRY, 4, 5.0f, 12.4f, 2500, 13, "Porter +1"),            // 20
    )
    val padPaid = IntArray(pads.size)
    var bought = 0L
        private set

    val benchX = 0.75f
    val benchY = floatArrayOf(12.45f, 13.45f, 14.75f, 15.75f)
    val benchCenters = floatArrayOf(12.95f, 15.25f)
    val benchSeat = IntArray(4) { -1 }
    val benchPadX = 2.5f
    val benchPadY = 18.3f

    val doorOpen = BooleanArray(World.ROOM_COUNT)

    // Input, set by the UI every frame. Length <= 1.
    var inputX = 0f
    var inputY = 0f

    var time = 0f
    var cured = 0
    var goalIndex = 0
    var chapter = 1
    var speedMult = 1f
    var payMult = 1f
    var fenceMax = 600f
    var fenceHp = 600f
    var wave = 0
    var waveTimer = 50f
    private var ratsToSpawn = 0
    private var ratSpawnTick = 0f
    private var arrivalTimer = 0.5f
    var shake = 0f
    var bannerKind = Banner.NONE
    var bannerValue = 0
    var bannerTimer = 0f
    var bossDamageShown = 0f
    private var bossDamageAcc = 0f
    private var bossNumberTick = 0f
    private var fxCursor = 0
    private var saveTimer = 0f
    var saveRequested = false

    private val events = IntArray(32)
    private var evHead = 0
    private var evTail = 0

    val goal: Int
        get() = if (goalIndex < GOALS.size) GOALS[goalIndex] else GOALS.last() + 500 * (goalIndex - GOALS.size + 1)
    val previousGoal: Int
        get() = if (goalIndex == 0) 0 else if (goalIndex - 1 < GOALS.size) GOALS[goalIndex - 1] else GOALS.last() + 500 * (goalIndex - GOALS.size)

    init {
        stations[0].unlocked = true
        piles[0].enabled = true
        // A few sick people already waiting so the first seconds are not empty.
        for (i in 0 until 5) spawnPatient(lyingNow = true)
    }

    fun isBought(i: Int) = (bought and (1L shl i)) != 0L
    fun padVisible(i: Int): Boolean {
        if (isBought(i)) return false
        val r = pads[i].requires
        return r < 0 || isBought(r)
    }

    fun pollEvent(): Int {
        if (evHead == evTail) return 0
        val e = events[evHead]
        evHead = (evHead + 1) and 31
        return e
    }

    private fun emit(e: Int) {
        val next = (evTail + 1) and 31
        if (next != evHead) {
            events[evTail] = e; evTail = next
        }
    }

    // ------------------------------------------------------------------ update

    fun update(frameDt: Float) {
        var remaining = min(frameDt, 0.1f)
        // Sub-steps keep the simulation stable on slow frames.
        while (remaining > 0f) {
            val dt = min(remaining, 1f / 60f)
            step(dt)
            remaining -= dt
        }
    }

    private fun step(dt: Float) {
        time += dt
        updatePlayer(dt)
        updateArrivals(dt)
        updatePatients(dt)
        updateBench()
        updateStations(dt)
        updateOrderlies(dt)
        updatePiles(dt)
        updatePads(dt)
        updateWaves(dt)
        updateRats(dt)
        updateBoss(dt)
        updateFlame(dt)
        updateTowers(dt)
        updateArrows(dt)
        updateFx(dt)
        if (bannerTimer > 0f) bannerTimer -= dt
        if (shake > 0f) shake = max(0f, shake - dt * 3f)
        saveTimer += dt
        if (saveTimer > 5f) {
            saveTimer = 0f; saveRequested = true
        }
    }

    // ------------------------------------------------------------------ player

    private fun updatePlayer(dt: Float) {
        val p = player
        var ix = inputX
        var iy = inputY
        val len = sqrt(ix * ix + iy * iy)
        if (len > 1f) {
            ix /= len; iy /= len
        }
        p.moving = len > 0.08f
        if (p.moving) {
            val speed = 5.0f
            p.x += ix * speed * dt
            p.y += iy * speed * dt
            val l = sqrt(ix * ix + iy * iy)
            p.faceX = ix / l
            p.faceY = iy / l
            p.walkPhase += dt * 11f
        }
        collide(p, 0.35f)

        // Carried patients ride on top of the doctor's head.
        for (i in 0 until p.carriedCount) {
            val pt = patients[p.carried[i]]
            pt.x = p.x; pt.y = p.y + 0.01f; pt.z = 1.75f + i * 0.42f
            pt.faceX = p.faceX; pt.faceY = p.faceY
        }

        if (p.pickCooldown > 0f) p.pickCooldown -= dt
        if (p.pickCooldown > 0f) return

        // Drop on a free treatment pad.
        if (p.carriedCount > 0) {
            for (s in stations.indices) {
                val st = stations[s]
                if (!st.free) continue
                if (abs(p.x - st.padX) < 0.8f && abs(p.y - st.padY) < 0.8f) {
                    val idx = p.carried[--p.carriedCount]
                    sendToStation(idx, s)
                    p.pickCooldown = 0.18f
                    emit(GameEvent.DROP)
                    return
                }
            }
            if (abs(p.x - benchPadX) < 0.9f && abs(p.y - benchPadY) < 0.8f) {
                val seat = freeSeat()
                if (seat >= 0) {
                    val idx = p.carried[--p.carriedCount]
                    sendToBench(idx, seat)
                    p.pickCooldown = 0.18f
                    emit(GameEvent.DROP)
                    return
                }
            }
        }

        // Pick up a sick person lying in the courtyard.
        if (p.carriedCount < p.capacity) {
            var best = -1
            var bestD = 1.1f * 1.1f
            for (i in patients.indices) {
                val pt = patients[i]
                if (!pt.active || pt.state != PState.LYING) continue
                val dx = pt.x - p.x
                val dy = pt.y - p.y
                val d = dx * dx + dy * dy
                if (d < bestD) {
                    bestD = d; best = i
                }
            }
            if (best >= 0) {
                val pt = patients[best]
                if (pt.reservedBy >= 0) {
                    val o = orderlies[pt.reservedBy]
                    if (o.target == best) {
                        o.state = 0; o.target = -1
                    }
                    pt.reservedBy = -1
                }
                pt.state = PState.CARRIED
                pt.carrier = 0
                pt.slot = p.carriedCount
                p.carried[p.carriedCount++] = best
                p.pickCooldown = 0.2f
                emit(GameEvent.DROP)
            }
        }
    }

    private fun collide(p: Player, r: Float) {
        for (b in World.walls) pushOut(p, b, r)
        for (i in 0 until World.ROOM_COUNT) if (!doorOpen[i]) pushOut(p, World.doorBlockers[i], r)
        for (s in stations) {
            if (!s.unlocked) continue
            if (s.kind == StationKind.BATH) pushOutCircle(p, s.x, s.y + 0.1f, 1.0f + r)
            else pushOutBox(p, s.x - 0.62f, s.y - 1.15f, s.x + 0.62f, s.y + 1.15f, r)
        }
        for (t in towers) if (t.active) pushOutCircle(p, t.x, t.y, 0.75f + r)
        for (c in benchCenters) pushOutBox(p, 0.35f, c - 0.9f, 1.15f, c + 0.9f, r)
        p.x = p.x.coerceIn(0.35f, World.WIDTH - 0.35f)
        p.y = p.y.coerceIn(0.35f, World.FENCE_Y - 0.35f)
    }

    private fun pushOut(p: Player, b: Box, r: Float) = pushOutBox(p, b.x0, b.y0, b.x1, b.y1, r)

    private fun pushOutBox(p: Player, x0: Float, y0: Float, x1: Float, y1: Float, r: Float) {
        val cx = p.x.coerceIn(x0, x1)
        val cy = p.y.coerceIn(y0, y1)
        val dx = p.x - cx
        val dy = p.y - cy
        val d2 = dx * dx + dy * dy
        if (d2 >= r * r) return
        if (d2 > 1e-8f) {
            val d = sqrt(d2)
            p.x = cx + dx / d * r
            p.y = cy + dy / d * r
        } else {
            // Center inside the box: leave by the closest side.
            val l = p.x - x0
            val rr = x1 - p.x
            val t = p.y - y0
            val bt = y1 - p.y
            val m = min(min(l, rr), min(t, bt))
            when (m) {
                l -> p.x = x0 - r
                rr -> p.x = x1 + r
                t -> p.y = y0 - r
                else -> p.y = y1 + r
            }
        }
    }

    private fun pushOutCircle(p: Player, cx: Float, cy: Float, rad: Float) {
        val dx = p.x - cx
        val dy = p.y - cy
        val d2 = dx * dx + dy * dy
        if (d2 >= rad * rad || d2 < 1e-8f) return
        val d = sqrt(d2)
        p.x = cx + dx / d * rad
        p.y = cy + dy / d * rad
    }

    // ------------------------------------------------------------------ patients

    private fun freeSeat(): Int {
        for (i in benchSeat.indices) if (benchSeat[i] < 0) return i
        return -1
    }

    private fun sendToStation(idx: Int, s: Int) {
        val pt = patients[idx]
        val st = stations[s]
        st.occupant = idx
        st.reserved = -1
        st.progress = 0f
        pt.state = PState.FLY
        pt.station = s
        pt.bench = -1
        pt.carrier = -1
        pt.timer = 0f
        pt.fromX = pt.x; pt.fromY = pt.y
        pt.startZ = pt.z
    }

    private fun sendToBench(idx: Int, seat: Int) {
        val pt = patients[idx]
        benchSeat[seat] = idx
        pt.state = PState.FLY
        pt.station = -1
        pt.bench = seat
        pt.carrier = -1
        pt.timer = 0f
        pt.patience = 1f
        pt.fromX = pt.x; pt.fromY = pt.y
        pt.startZ = pt.z
    }

    private fun spawnPatient(lyingNow: Boolean) {
        for (pt in patients) {
            if (pt.active) continue
            pt.active = true
            pt.look = rng.int(4)
            pt.health = 1f
            pt.patience = 1f
            pt.station = -1
            pt.bench = -1
            pt.reservedBy = -1
            pt.carrier = -1
            pt.mood = 0
            pt.z = 0f
            pt.timer = 0f
            val lx = rng.range(1.3f, 14.7f)
            val ly = rng.range(22.6f, 28.8f)
            if (lyingNow) {
                pt.x = lx; pt.y = ly
                pt.state = PState.LYING
                pt.health = rng.range(0.75f, 1f)
            } else {
                pt.x = rng.range(4f, 12f); pt.y = World.FENCE_Y + 1.5f
                pt.state = PState.ARRIVE
                pt.path.clear()
                pt.path.route(pt.x, World.FENCE_Y - 0.5f, lx, ly)
            }
            pt.faceX = 0f; pt.faceY = -1f
            return
        }
    }

    private fun updateArrivals(dt: Float) {
        arrivalTimer -= dt
        if (arrivalTimer > 0f) return
        arrivalTimer = max(1.2f, 3.6f - 0.3f * (chapter - 1)) * rng.range(0.7f, 1.3f)
        var waiting = 0
        for (pt in patients) if (pt.active && (pt.state == PState.LYING || pt.state == PState.ARRIVE)) waiting++
        if (waiting < 12) spawnPatient(lyingNow = false)
    }

    /** Moves along a path. Returns true when the last waypoint is reached. */
    private fun walk(path: Path, pos: FloatArray, speed: Float, dt: Float): Boolean {
        var budget = speed * dt
        while (!path.done && budget > 0f) {
            val dx = path.tx - pos[0]
            val dy = path.ty - pos[1]
            val d = sqrt(dx * dx + dy * dy)
            if (d <= budget) {
                pos[0] = path.tx; pos[1] = path.ty
                budget -= d
                path.next()
            } else {
                pos[0] += dx / d * budget
                pos[1] += dy / d * budget
                pos[2] = dx / d; pos[3] = dy / d
                budget = 0f
            }
        }
        return path.done
    }

    private val tmpPos = FloatArray(4)

    private fun walkPatient(pt: Patient, speed: Float, dt: Float): Boolean {
        tmpPos[0] = pt.x; tmpPos[1] = pt.y; tmpPos[2] = pt.faceX; tmpPos[3] = pt.faceY
        val done = walk(pt.path, tmpPos, speed, dt)
        pt.x = tmpPos[0]; pt.y = tmpPos[1]; pt.faceX = tmpPos[2]; pt.faceY = tmpPos[3]
        pt.walkPhase += dt * 9f
        return done
    }

    fun seatX(pt: Patient): Float = if (pt.station >= 0) stations[pt.station].x else benchX
    fun seatY(pt: Patient): Float = if (pt.station >= 0) stations[pt.station].y else benchY[pt.bench]

    private fun updatePatients(dt: Float) {
        for (i in patients.indices) {
            val pt = patients[i]
            if (!pt.active) continue
            when (pt.state) {
                PState.ARRIVE -> if (walkPatient(pt, 1.4f, dt)) pt.state = PState.LYING
                PState.LYING -> {
                    pt.health -= dt / 110f
                    if (pt.health <= 0f) kill(i)
                }
                PState.BENCH -> {
                    pt.patience -= dt / 55f
                    pt.mood = if (pt.patience < 0.45f) 2 else 0
                    if (pt.patience <= 0f) {
                        benchSeat[pt.bench] = -1
                        pt.bench = -1
                        leave(pt, angry = true)
                    }
                }
                PState.TO_STATION -> if (walkPatient(pt, 1.8f, dt)) {
                    pt.state = PState.TREAT
                    pt.x = seatX(pt); pt.y = seatY(pt)
                }
                PState.FLY -> {
                    pt.timer += dt / 0.4f
                    val t = min(1f, pt.timer)
                    val tx = seatX(pt)
                    val ty = seatY(pt)
                    pt.x = pt.fromX + (tx - pt.fromX) * t
                    pt.y = pt.fromY + (ty - pt.fromY) * t
                    pt.z = pt.startZ * (1f - t) + 4f * t * (1f - t) * 1.2f
                    if (t >= 1f) {
                        pt.z = 0f
                        if (pt.station >= 0) {
                            pt.state = PState.TREAT
                            spawnFx(FxKind.SMOKE, pt.x, pt.y, 0.3f)
                        } else {
                            pt.state = PState.BENCH
                        }
                    }
                }
                PState.LEAVE_HAPPY, PState.LEAVE_ANGRY -> {
                    if (walkPatient(pt, if (pt.state == PState.LEAVE_HAPPY) 2.0f else 2.4f, dt)) pt.active = false
                }
                PState.DEAD -> {
                    pt.timer -= dt
                    if (pt.timer <= 0f) pt.active = false
                }
            }
        }
    }

    private fun kill(i: Int) {
        val pt = patients[i]
        if (pt.reservedBy >= 0) {
            val o = orderlies[pt.reservedBy]
            if (o.target == i) {
                o.state = 0; o.target = -1
            }
            pt.reservedBy = -1
        }
        pt.state = PState.DEAD
        pt.timer = 2.0f
        spawnFx(FxKind.SKULL, pt.x, pt.y, 0.6f)
    }

    private fun leave(pt: Patient, angry: Boolean) {
        pt.state = if (angry) PState.LEAVE_ANGRY else PState.LEAVE_HAPPY
        pt.mood = if (angry) 2 else 1
        pt.z = 0f
        pt.path.route(pt.x, pt.y, World.GATE_X + rng.range(-1.5f, 1.5f), World.FENCE_Y + 2f)
    }

    private fun updateBench() {
        for (seat in benchSeat.indices) {
            val idx = benchSeat[seat]
            if (idx < 0) continue
            val pt = patients[idx]
            if (pt.state != PState.BENCH) continue
            for (s in stations.indices) {
                val st = stations[s]
                if (!st.free) continue
                benchSeat[seat] = -1
                pt.bench = -1
                pt.station = s
                pt.mood = 0
                st.occupant = idx
                st.progress = 0f
                pt.state = PState.TO_STATION
                pt.path.route(pt.x, pt.y, st.padX, st.padY)
                break
            }
        }
    }

    // ------------------------------------------------------------------ treatment

    val baseBathTime = 7f
    val baseBedTime = 5.5f

    private fun updateStations(dt: Float) {
        for (s in stations) {
            if (!s.unlocked) continue
            val dx = player.x - s.x
            val dy = player.y - s.y
            s.doctorNear = dx * dx + dy * dy < 2.8f * 2.8f
            if (s.occupant < 0) continue
            val pt = patients[s.occupant]
            if (pt.state != PState.TREAT) continue
            val rate = if (s.kind == StationKind.BATH) 1f / baseBathTime
            else if (s.nurse || s.doctorNear) 1f / baseBedTime else 0f
            if (rate <= 0f) continue
            s.progress += rate * speedMult * dt
            if (rng.f() < dt * 3f) spawnFx(FxKind.PLUS, s.x + rng.range(-0.6f, 0.6f), s.y + rng.range(-0.3f, 0.3f), 0.8f)
            if (s.progress >= 1f) cure(s)
        }
    }

    private fun cure(s: Station) {
        val pt = patients[s.occupant]
        s.occupant = -1
        s.progress = 0f
        val base = if (s.kind == StationKind.BATH) 12f else 20f
        val pay = max(1, (base * payMult * (1f + 0.15f * (chapter - 1))).toInt())
        val pile = piles[s.pile]
        pile.enabled = true
        val chunks = 5
        var left = pay
        for (c in 0 until chunks) {
            val v = if (c == chunks - 1) left else pay / chunks
            if (v <= 0) continue
            left -= v
            spawnCoin(pt.x, pt.y, 1.0f, pile.x, pile.y, 0f, v, CoinDest.PILE, s.pile, 0.35f + c * 0.06f)
        }
        pt.station = -1
        leave(pt, angry = false)
        for (k in 0 until 4) spawnFx(FxKind.PLUS, pt.x, pt.y, 1.2f)
        cured++
        emit(GameEvent.CURED)
        if (cured >= goal) levelUp()
    }

    private fun levelUp() {
        val bonus = goal * 3
        goalIndex++
        chapter++
        bannerKind = Banner.LEVEL
        bannerValue = chapter
        bannerTimer = 2.8f
        emit(GameEvent.LEVEL)
        val n = 16
        for (c in 0 until n) {
            spawnCoin(player.x + rng.range(-2f, 2f), player.y + rng.range(-2f, 2f), 2.5f, player.x, player.y, 0f,
                if (c == n - 1) bonus - (bonus / n) * (n - 1) else bonus / n, CoinDest.PLAYER, 0, 0.5f + c * 0.04f)
        }
        for (c in 0 until 40) spawnFx(FxKind.CONFETTI, player.x, player.y, 2f)
    }

    // ------------------------------------------------------------------ orderlies

    private fun updateOrderlies(dt: Float) {
        for (oi in orderlies.indices) {
            val o = orderlies[oi]
            if (!o.active) continue
            tmpPos[0] = o.x; tmpPos[1] = o.y; tmpPos[2] = o.faceX; tmpPos[3] = o.faceY
            when (o.state) {
                0 -> {
                    o.think -= dt
                    if (o.think <= 0f) {
                        o.think = 0.4f
                        val target = findPatientFor(o)
                        if (target >= 0 && (anyFreeStation() || freeSeat() >= 0)) {
                            o.target = target
                            patients[target].reservedBy = oi
                            o.state = 1
                            o.path.route(o.x, o.y, patients[target].x, patients[target].y)
                        } else if (o.path.done) {
                            o.path.route(o.x, o.y, 8f + oi * 1.5f, 12.5f)
                        }
                    }
                    walk(o.path, tmpPos, 2.6f, dt)
                }
                1 -> {
                    val pt = patients[o.target]
                    if (!pt.active || pt.state != PState.LYING || pt.reservedBy != oi) {
                        o.state = 0; o.target = -1
                    } else if (walk(o.path, tmpPos, 3.4f, dt)) {
                        pt.state = PState.CARRIED
                        pt.carrier = oi + 1
                        pt.reservedBy = -1
                        o.carrying = o.target
                        o.target = -1
                        o.state = 2
                        routeOrderlyDelivery(o)
                    }
                }
                2 -> {
                    val pt = patients[o.carrying]
                    pt.x = tmpPos[0]; pt.y = tmpPos[1] + 0.01f; pt.z = 1.6f
                    if (walk(o.path, tmpPos, 2.9f, dt)) {
                        // Arrived: deliver to the reserved station, or to the bench.
                        var delivered = false
                        for (s in stations.indices) {
                            val st = stations[s]
                            if (st.reserved == oi + 100) {
                                pt.x = tmpPos[0]; pt.y = tmpPos[1]
                                sendToStation(o.carrying, s)
                                delivered = true
                                break
                            }
                        }
                        if (!delivered) {
                            val seat = freeSeat()
                            if (seat >= 0) {
                                pt.x = tmpPos[0]; pt.y = tmpPos[1]
                                sendToBench(o.carrying, seat)
                                delivered = true
                            } else {
                                routeOrderlyDelivery(o) // wait for a place
                            }
                        }
                        if (delivered) {
                            o.carrying = -1
                            o.state = 0
                            o.think = 0.2f
                        }
                    }
                    if (o.carrying >= 0) {
                        pt.x = tmpPos[0]; pt.y = tmpPos[1] + 0.01f
                    }
                }
            }
            if (tmpPos[0] != o.x || tmpPos[1] != o.y) o.walkPhase += dt * 11f
            o.x = tmpPos[0]; o.y = tmpPos[1]; o.faceX = tmpPos[2]; o.faceY = tmpPos[3]
        }
    }

    private fun anyFreeStation(): Boolean {
        for (s in stations) if (s.free) return true
        return false
    }

    private fun findPatientFor(o: Orderly): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in patients.indices) {
            val pt = patients[i]
            if (!pt.active || pt.state != PState.LYING || pt.reservedBy >= 0) continue
            // Leave the sickest ones close to the doctor alone: just take the nearest.
            val dx = pt.x - o.x
            val dy = pt.y - o.y
            val d = dx * dx + dy * dy - (1f - pt.health) * 20f
            if (d < bestD) {
                bestD = d; best = i
            }
        }
        return best
    }

    private fun routeOrderlyDelivery(o: Orderly) {
        val oi = orderlies.indexOf(o)
        for (s in stations) if (s.reserved == oi + 100) s.reserved = -1
        for (s in stations) {
            if (s.free) {
                s.reserved = oi + 100
                o.path.route(o.x, o.y, s.padX, s.padY)
                return
            }
        }
        o.path.route(o.x, o.y, benchPadX + 0.6f, benchPadY - 0.6f)
    }

    // ------------------------------------------------------------------ coins

    private fun updatePiles(dt: Float) {
        val p = player
        p.pileTick -= dt
        if (p.pileTick > 0f) return
        for (pi in piles.indices) {
            val pile = piles[pi]
            if (pile.value <= 0 || !pile.contains(p.x, p.y)) continue
            p.pileTick = 0.03f
            val chunk = max(1, pile.value / 16)
            pile.value -= chunk
            spawnCoin(pile.x, pile.y, 0.2f, p.x, p.y, 0f, chunk, CoinDest.PLAYER, 0, 0.32f)
            return
        }
    }

    private fun updatePads(dt: Float) {
        val p = player
        var on = -1
        for (i in pads.indices) {
            if (!padVisible(i)) continue
            val pd = pads[i]
            if (abs(p.x - pd.x) < 0.95f && abs(p.y - pd.y) < 0.75f) {
                on = i; break
            }
        }
        if (on < 0 || p.coins <= 0) {
            p.padTimer = 0f
            return
        }
        p.padTimer += dt
        if (p.padTimer < 0.25f) return
        p.padTick -= dt
        val pd = pads[on]
        while (p.padTick <= 0f && p.coins > 0) {
            p.padTick += 0.028f
            val remaining = pd.cost - padPaid[on]
            val chunk = min(min(max(1, pd.cost / 36), remaining), p.coins)
            p.coins -= chunk
            padPaid[on] += chunk
            spawnCoin(p.x, p.y, 1.0f + p.stackHeight * 0.06f, pd.x, pd.y, 0f, 0, CoinDest.NONE, 0, 0.3f)
            emit(GameEvent.COIN)
            if (padPaid[on] >= pd.cost) {
                buy(on, silent = false)
                p.padTimer = 0f
                break
            }
        }
    }

    fun buy(i: Int, silent: Boolean) {
        bought = bought or (1L shl i)
        padPaid[i] = pads[i].cost
        val pd = pads[i]
        when (pd.kind) {
            PadKind.ROOM -> {
                doorOpen[pd.arg] = true
                stations[2 + pd.arg].unlocked = true
                piles[1 + pd.arg].enabled = true
            }
            PadKind.NURSE -> stations[2 + pd.arg].nurse = true
            PadKind.BATH -> stations[pd.arg].unlocked = true
            PadKind.CARRY -> player.capacity = max(player.capacity, pd.arg)
            PadKind.TOWER -> towers[pd.arg].active = true
            PadKind.ORDERLY -> {
                val o = orderlies[pd.arg]
                o.active = true
                o.x = pd.x; o.y = pd.y + 0.8f
                o.state = 0
                o.path.clear()
            }
            PadKind.SPEED -> speedMult *= 1.35f
            PadKind.FENCE -> {
                fenceMax *= 2f; fenceHp = fenceMax
            }
            PadKind.PRICE -> payMult *= 1.5f
        }
        if (!silent) {
            bannerKind = Banner.UNLOCK
            bannerValue = i
            bannerTimer = 1.8f
            shake = 0.5f
            for (c in 0 until 28) spawnFx(FxKind.CONFETTI, pd.x, pd.y, 0.4f)
            emit(GameEvent.BUY)
            saveRequested = true
        }
    }

    // ------------------------------------------------------------------ rats & boss

    private fun updateWaves(dt: Float) {
        waveTimer -= dt
        if (waveTimer <= 0f) {
            wave++
            waveTimer = 42f
            ratsToSpawn = min(28, 3 + wave * 2)
            if (wave % 3 == 0 && !boss.active) {
                spawnBoss()
                bannerKind = Banner.BOSS
            } else {
                bannerKind = Banner.WAVE
            }
            bannerValue = wave
            bannerTimer = 2.4f
        }
        if (ratsToSpawn > 0) {
            ratSpawnTick -= dt
            if (ratSpawnTick <= 0f) {
                ratSpawnTick = 0.35f
                if (spawnRat()) ratsToSpawn-- else ratsToSpawn = 0
            }
        }
    }

    private fun spawnRat(): Boolean {
        for (r in rats) {
            if (r.active) continue
            r.active = true
            r.x = rng.range(1f, 15f)
            r.y = World.FENCE_Y + 0.9f
            r.z = 0f
            r.hop = 1f
            r.maxHp = 18f + wave * 4f
            r.hp = r.maxHp
            r.speed = rng.range(1.7f, 2.4f)
            r.hit = 0f
            r.life = rng.range(22f, 30f)
            r.phase = rng.range(0f, 6f)
            return true
        }
        return false
    }

    private fun updateRats(dt: Float) {
        for (ri in rats.indices) {
            val r = rats[ri]
            if (!r.active) continue
            r.phase += dt * 14f
            if (r.hit > 0f) r.hit -= dt
            if (r.hop > 0f) {
                // Jump over the fence into the courtyard.
                r.hop -= dt / 0.55f
                val t = 1f - max(0f, r.hop)
                r.y = World.FENCE_Y + 0.9f - 2.1f * t
                r.z = 4f * t * (1f - t) * 1.1f
                if (r.hop <= 0f) r.z = 0f
                continue
            }
            // Target: the closest sick person in the courtyard, else the doctor. Old rats go home.
            r.life -= dt
            var tx = player.x
            var ty = max(player.y, 21f)
            var best = -1
            var bestD = Float.MAX_VALUE
            if (r.life <= 0f) {
                tx = r.x; ty = World.FENCE_Y + 1f
                if (r.y >= World.FENCE_Y - 0.35f) {
                    r.active = false
                    continue
                }
            } else for (i in patients.indices) {
                val pt = patients[i]
                if (!pt.active || (pt.state != PState.LYING && pt.state != PState.ARRIVE)) continue
                if (pt.y < 20.6f) continue
                val dx = pt.x - r.x
                val dy = pt.y - r.y
                val d = dx * dx + dy * dy
                if (d < bestD) {
                    bestD = d; best = i
                }
            }
            if (best >= 0) {
                tx = patients[best].x; ty = patients[best].y
            }
            val dx = tx - r.x
            val dy = ty - r.y
            val d = sqrt(dx * dx + dy * dy)
            if (best >= 0 && d < 0.5f) {
                patients[best].health -= 0.2f * dt
                if (patients[best].health <= 0f) kill(best)
                r.vx = 0f; r.vy = 0f
            } else if (d > 0.3f) {
                // Wobbly rat run.
                val wob = kotlin.math.sin(r.phase * 0.35f) * 0.45f
                val nx = dx / d
                val ny = dy / d
                r.vx = (nx - ny * wob) * r.speed
                r.vy = (ny + nx * wob) * r.speed
            } else {
                r.vx = 0f; r.vy = 0f
            }
            // Keep rats apart a bit.
            for (oj in ri + 1 until rats.size) {
                val o = rats[oj]
                if (!o.active || o.hop > 0f) continue
                val sx = r.x - o.x
                val sy = r.y - o.y
                val s2 = sx * sx + sy * sy
                if (s2 < 0.25f && s2 > 1e-6f) {
                    val s = sqrt(s2)
                    val push = (0.5f - s) * 0.5f
                    r.x += sx / s * push; r.y += sy / s * push
                    o.x -= sx / s * push; o.y -= sy / s * push
                }
            }
            r.x = (r.x + r.vx * dt).coerceIn(0.3f, World.WIDTH - 0.3f)
            r.y = (r.y + r.vy * dt).coerceIn(20.8f, World.FENCE_Y - 0.3f)
        }
    }

    private fun spawnBoss() {
        val b = boss
        b.active = true
        b.x = World.GATE_X
        b.y = World.FENCE_Y + 4f
        b.state = 0
        b.maxHp = 380f * (1f + 0.6f * b.level)
        b.timer = 0f
        b.hp = b.maxHp
        b.hit = 0f
        emit(GameEvent.BOSS)
    }

    private fun updateBoss(dt: Float) {
        val b = boss
        if (!b.active) return
        b.phase += dt
        if (b.hit > 0f) b.hit -= dt
        when (b.state) {
            0 -> {
                b.y -= 1.0f * dt
                if (b.y <= World.FENCE_Y + 1.1f) b.state = 1
            }
            1 -> {
                fenceHp -= 26f * (1f + 0.3f * b.level) * dt
                if (kotlin.math.sin(b.phase * 4f) > 0.97f) shake = max(shake, 0.25f)
                if (fenceHp <= 0f) {
                    fenceHp = 0f
                    b.state = 2
                    bannerKind = Banner.FENCE
                    bannerTimer = 2.2f
                    shake = 1f
                }
            }
            2 -> {
                // It roams the courtyard for a while, crushing the sick, then goes back out.
                b.timer += dt
                if (b.timer > 38f) b.state = 3
                var tx = 8f + kotlin.math.sin(b.phase * 0.4f) * 5f
                var ty = 25.5f
                var best = -1
                var bestD = Float.MAX_VALUE
                for (i in patients.indices) {
                    val pt = patients[i]
                    if (!pt.active || pt.state != PState.LYING) continue
                    val dx = pt.x - b.x
                    val dy = pt.y - b.y
                    val d = dx * dx + dy * dy
                    if (d < bestD) {
                        bestD = d; best = i
                    }
                }
                if (best >= 0) {
                    tx = patients[best].x; ty = patients[best].y
                }
                ty = ty.coerceIn(22.5f, World.FENCE_Y + 1f)
                val dx = tx - b.x
                val dy = ty - b.y
                val d = sqrt(dx * dx + dy * dy)
                if (d > 0.2f) {
                    b.x += dx / d * 1.3f * dt
                    b.y += dy / d * 1.3f * dt
                }
                if (best >= 0 && d < 1.3f) {
                    kill(best)
                    shake = max(shake, 0.4f)
                }
            }
            3 -> {
                b.y += 1.4f * dt
                if (b.y > World.FENCE_Y + 3f) {
                    b.active = false
                    fenceHp = fenceMax
                }
            }
        }
        if (bossNumberTick > 0f) bossNumberTick -= dt
        if (bossDamageAcc >= 1f && bossNumberTick <= 0f) {
            spawnNumber(b.x + rng.range(-1f, 1f), b.y, 2.6f, bossDamageAcc.toInt(), 0xFFFF5A4A.toInt())
            bossDamageAcc = 0f
            bossNumberTick = 0.18f
        }
    }

    private fun damageBoss(amount: Float) {
        val b = boss
        b.hp -= amount
        b.hit = 0.08f
        bossDamageAcc += amount
        if (b.hp <= 0f) {
            b.active = false
            b.level++
            fenceHp = fenceMax
            shake = 1.2f
            bannerKind = Banner.BOSS_DOWN
            bannerTimer = 2.4f
            val reward = 120 * b.level + 40 * wave
            val n = 24
            for (c in 0 until n) {
                spawnCoin(b.x + rng.range(-1.5f, 1.5f), b.y + rng.range(-1f, 1f), 1.5f, player.x, player.y, 0f,
                    if (c == n - 1) reward - (reward / n) * (n - 1) else reward / n, CoinDest.PLAYER, 0, 0.6f + c * 0.03f)
            }
            for (c in 0 until 30) spawnFx(FxKind.SMOKE, b.x + rng.range(-1.5f, 1.5f), b.y + rng.range(-1f, 1f), rng.range(0f, 2f))
            emit(GameEvent.LEVEL)
        }
    }

    private fun damageRat(ri: Int, amount: Float) {
        val r = rats[ri]
        r.hp -= amount
        r.hit = 0.08f
        if (r.hp <= 0f) {
            r.active = false
            spawnFx(FxKind.SMOKE, r.x, r.y, 0.2f)
            spawnCoin(r.x, r.y, 0.3f, player.x, player.y, 0f, 1 + wave / 3, CoinDest.PLAYER, 0, 0.45f)
        }
    }

    private fun updateFlame(dt: Float) {
        val p = player
        p.flameTarget = -1
        var bestD = 2.6f * 2.6f
        for (ri in rats.indices) {
            val r = rats[ri]
            if (!r.active || r.hop > 0.3f) continue
            val dx = r.x - p.x
            val dy = r.y - p.y
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d; p.flameTarget = ri
            }
        }
        if (p.flameTarget < 0 && boss.active) {
            val dx = boss.x - p.x
            val dy = boss.y - p.y
            if (dx * dx + dy * dy < 3.6f * 3.6f) p.flameTarget = -2
        }
        if (p.flameTarget == -1) return
        val tx: Float
        val ty: Float
        if (p.flameTarget >= 0) {
            tx = rats[p.flameTarget].x; ty = rats[p.flameTarget].y
            damageRat(p.flameTarget, 45f * dt)
        } else {
            tx = boss.x; ty = boss.y
            damageBoss(45f * dt)
        }
        p.flameX = tx; p.flameY = ty
        // Fire stream particles.
        if (rng.f() < dt * 70f) {
            for (k in 0 until 2) {
                val f = spawnFx(FxKind.FLAME, p.x + p.faceX * 0.2f, p.y + 0.05f, 1.0f) ?: break
                val dx = tx - p.x
                val dy = ty - p.y
                val d = max(0.01f, sqrt(dx * dx + dy * dy))
                val sp = rng.range(5f, 8f)
                f.vx = dx / d * sp + rng.range(-0.8f, 0.8f)
                f.vy = dy / d * sp + rng.range(-0.8f, 0.8f)
                f.vz = rng.range(-0.5f, 0.6f)
                f.life = min(0.45f, d / sp + 0.08f)
            }
        }
    }

    private fun updateTowers(dt: Float) {
        for (t in towers) {
            if (!t.active) continue
            t.cooldown -= dt
            if (t.cooldown > 0f) continue
            var target = -1
            var tx = 0f
            var ty = 0f
            var bestD = 11f * 11f
            for (ri in rats.indices) {
                val r = rats[ri]
                if (!r.active || r.hop > 0f) continue
                val dx = r.x - t.x
                val dy = r.y - t.y
                val d = dx * dx + dy * dy
                if (d < bestD) {
                    bestD = d; target = ri; tx = r.x; ty = r.y
                }
            }
            if (target < 0 && boss.active) {
                val dx = boss.x - t.x
                val dy = boss.y - t.y
                if (dx * dx + dy * dy < 13f * 13f) {
                    target = -2; tx = boss.x; ty = boss.y
                }
            }
            if (target == -1) continue
            t.cooldown = 0.65f
            for (a in arrows) {
                if (a.active) continue
                a.active = true
                a.x = t.x; a.y = t.y
                val dx = tx - t.x
                val dy = ty - t.y
                val d = max(0.01f, sqrt(dx * dx + dy * dy))
                a.vx = dx / d * 17f; a.vy = dy / d * 17f
                a.life = 1.2f
                a.target = target
                t.aimX = dx / d; t.aimY = dy / d
                break
            }
        }
    }

    private fun updateArrows(dt: Float) {
        for (a in arrows) {
            if (!a.active) continue
            a.life -= dt
            if (a.life <= 0f) {
                a.active = false; continue
            }
            val tx: Float
            val ty: Float
            val hitR: Float
            if (a.target >= 0) {
                val r = rats[a.target]
                if (!r.active) {
                    a.x += a.vx * dt; a.y += a.vy * dt; continue
                }
                tx = r.x; ty = r.y; hitR = 0.45f
            } else {
                if (!boss.active) {
                    a.active = false; continue
                }
                tx = boss.x; ty = boss.y; hitR = 1.3f
            }
            // Light homing.
            val dx = tx - a.x
            val dy = ty - a.y
            val d = sqrt(dx * dx + dy * dy)
            if (d < hitR) {
                a.active = false
                if (a.target >= 0) damageRat(a.target, 16f) else damageBoss(16f)
                continue
            }
            a.vx += (dx / d * 17f - a.vx) * min(1f, dt * 8f)
            a.vy += (dy / d * 17f - a.vy) * min(1f, dt * 8f)
            a.x += a.vx * dt
            a.y += a.vy * dt
        }
    }

    // ------------------------------------------------------------------ effects

    fun spawnFx(kind: Int, x: Float, y: Float, z: Float): Fx? {
        for (n in fx.indices) {
            fxCursor = (fxCursor + 1) % fx.size
            val f = fx[fxCursor]
            if (f.active) continue
            f.active = true
            f.kind = kind
            f.x = x; f.y = y; f.z = z
            f.t = 0f
            f.vx = 0f; f.vy = 0f; f.vz = 0f
            f.value = 0
            f.dest = CoinDest.NONE
            when (kind) {
                FxKind.PLUS -> {
                    f.life = 0.9f; f.vz = 1.2f
                }
                FxKind.SMOKE -> f.life = 0.6f
                FxKind.SKULL -> {
                    f.life = 1.4f; f.vz = 0.8f
                }
                FxKind.CONFETTI -> {
                    f.life = rng.range(0.8f, 1.4f)
                    val a = rng.range(0f, 6.283f)
                    val s = rng.range(1.5f, 4f)
                    f.vx = kotlin.math.cos(a) * s; f.vy = kotlin.math.sin(a) * s * 0.7f
                    f.vz = rng.range(3f, 7f)
                    f.color = CONFETTI[rng.int(CONFETTI.size)]
                }
                else -> f.life = 0.5f
            }
            return f
        }
        return null
    }

    private fun spawnNumber(x: Float, y: Float, z: Float, value: Int, color: Int) {
        val f = spawnFx(FxKind.NUMBER, x, y, z) ?: return
        f.value = value
        f.color = color
        f.life = 0.8f
        f.vz = 1.6f
    }

    private fun spawnCoin(
        sx: Float, sy: Float, sz: Float, tx: Float, ty: Float, tz: Float,
        value: Int, dest: Int, destIndex: Int, life: Float,
    ) {
        val f = spawnFx(FxKind.COIN, sx, sy, sz)
        if (f == null) {
            // Pool exhausted: credit immediately so no coin is ever lost.
            deliverCoin(dest, destIndex, value)
            return
        }
        f.sx = sx; f.sy = sy; f.sz = sz
        f.tx = tx; f.ty = ty; f.tz = tz
        f.value = value
        f.dest = dest
        f.destIndex = destIndex
        f.life = life
    }

    private fun deliverCoin(dest: Int, destIndex: Int, value: Int) {
        when (dest) {
            CoinDest.PLAYER -> {
                player.coins += value
                emit(GameEvent.COIN)
            }
            CoinDest.PILE -> piles[destIndex].value += value
        }
    }

    private fun updateFx(dt: Float) {
        for (f in fx) {
            if (!f.active) continue
            f.t += dt
            when (f.kind) {
                FxKind.COIN -> {
                    if (f.dest == CoinDest.PLAYER) {
                        f.tx = player.x; f.ty = player.y; f.tz = 1.0f + player.stackHeight * 0.06f
                    }
                    val t = min(1f, f.t / f.life)
                    val e = t * t * (3f - 2f * t)
                    f.x = f.sx + (f.tx - f.sx) * e
                    f.y = f.sy + (f.ty - f.sy) * e
                    f.z = f.sz + (f.tz - f.sz) * e + 4f * t * (1f - t) * 1.6f
                    if (t >= 1f) {
                        f.active = false
                        deliverCoin(f.dest, f.destIndex, f.value)
                    }
                    continue
                }
                FxKind.CONFETTI -> {
                    f.vz -= 14f * dt
                    f.x += f.vx * dt; f.y += f.vy * dt; f.z += f.vz * dt
                    if (f.z < 0f) {
                        f.z = 0f; f.vz = 0f; f.vx *= 0.5f; f.vy *= 0.5f
                    }
                }
                else -> {
                    f.x += f.vx * dt; f.y += f.vy * dt; f.z += f.vz * dt
                }
            }
            if (f.t >= f.life) f.active = false
        }
    }

    // ------------------------------------------------------------------ save

    fun save(): String {
        var coins = player.coins
        for (p in piles) coins += p.value
        for (f in fx) if (f.active && f.kind == FxKind.COIN && f.dest != CoinDest.NONE) coins += f.value
        val sb = StringBuilder("2;")
        sb.append(coins).append(';').append(bought).append(';').append(cured).append(';')
            .append(goalIndex).append(';').append(chapter).append(';').append(wave).append(';')
            .append(boss.level).append(';')
        for (i in padPaid.indices) {
            if (i > 0) sb.append(',')
            sb.append(padPaid[i])
        }
        return sb.toString()
    }

    fun load(s: String?) {
        if (s.isNullOrEmpty()) return
        val parts = s.split(';')
        if (parts.size < 9 || parts[0] != "2") return
        try {
            player.coins = parts[1].toInt()
            val mask = parts[2].toLong()
            cured = parts[3].toInt()
            goalIndex = parts[4].toInt()
            chapter = parts[5].toInt()
            wave = parts[6].toInt()
            boss.level = parts[7].toInt()
            val paid = parts[8].split(',')
            for (i in pads.indices) {
                if ((mask and (1L shl i)) != 0L) buy(i, silent = true)
                else if (i < paid.size) padPaid[i] = paid[i].toInt()
            }
            waveTimer = 50f
        } catch (_: NumberFormatException) {
        }
    }

    companion object {
        val GOALS = intArrayOf(10, 25, 50, 90, 150, 250, 400, 600, 900, 1300)
        val CONFETTI = intArrayOf(
            0xFFFFD34A.toInt(), 0xFF5BE37A.toInt(), 0xFFFF6B5A.toInt(), 0xFF6BC6FF.toInt(), 0xFFFFFFFF.toInt(),
        )
    }
}
