"""
Builds the 3D models of Last Asylum in Blender and renders them to sprite atlases.

    python render_sprites.py <repo_root> [--quick]

Requires `bpy`, `Pillow` and `numpy` (pip install bpy pillow numpy) and git: the CC0 KayKit packs by
Kay Lousberg (characters, dungeon, halloween, furniture) are cloned into $KAYKIT_DIR on first run.
The plague doctor outfit, rats, plague beast, cauldron and IV pole are modeled here in code.

Camera: orthographic, pitched 45 degrees down, looking north. The game projects the world
with the same angle (k = sin 45, kz = cos 45), so sprites line up with the floor exactly.
Outputs:
    composeApp/src/commonMain/composeResources/drawable/atlas0.png, atlas1.png, ...
    composeApp/src/commonMain/kotlin/com/lastasylum/game/render/SpriteData.kt
"""
import math
import os
import sys
import time

import subprocess

import bpy  # must come before bmesh
import bmesh
import numpy as np
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Matrix, Vector
from PIL import Image

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
QUICK = "--quick" in sys.argv
PPM = 128  # sprite pixels per meter
PITCH = math.radians(45)
TMP = "/tmp/lastasylum_sprites"
ASSETS = os.environ.get("KAYKIT_DIR", "/tmp/kaykit")
os.makedirs(TMP, exist_ok=True)

# ----------------------------------------------------------------------------- scene


def reset_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    sc.cycles.device = "CPU"
    sc.cycles.samples = 16 if QUICK else 40
    sc.cycles.use_denoising = True
    try:
        sc.cycles.denoiser = "OPENIMAGEDENOISE"
    except Exception:
        pass
    sc.cycles.max_bounces = 4
    sc.render.film_transparent = True
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGBA"
    sc.view_settings.view_transform = "Standard"
    sc.view_settings.look = "None"
    sc.view_settings.exposure = 0.15
    world = bpy.data.worlds.new("w")
    sc.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes["Background"]
    bg.inputs[0].default_value = (0.32, 0.36, 0.46, 1)
    bg.inputs[1].default_value = 0.55

    cam = bpy.data.cameras.new("cam")
    cam.type = "ORTHO"
    co = bpy.data.objects.new("cam", cam)
    sc.collection.objects.link(co)
    sc.camera = co

    def sun(name, rot, energy, color):
        l = bpy.data.lights.new(name, "SUN")
        l.energy = energy
        l.color = color
        l.angle = math.radians(12)
        o = bpy.data.objects.new(name, l)
        o.rotation_euler = rot
        sc.collection.objects.link(o)

    # Warm key from the upper left (torch light), cool fill and a rim from behind.
    sun("key", (math.radians(48), math.radians(-28), math.radians(-35)), 4.2, (1.0, 0.86, 0.68))
    sun("fill", (math.radians(60), math.radians(30), math.radians(40)), 1.1, (0.6, 0.72, 1.0))
    sun("rim", (math.radians(-120), 0, math.radians(15)), 2.4, (0.85, 0.9, 1.0))
    coll = bpy.data.collections.new("Models")
    sc.collection.children.link(coll)
    return sc, co, coll


SC, CAM, COLL = reset_scene()
MATS = {}


def clear_models():
    for o in list(COLL.objects):
        bpy.data.objects.remove(o, do_unlink=True)


def mat(name, color, rough=0.6, metal=0.0, emit=None, strength=0.0, sss=0.0, coat=0.0, alpha=1.0):
    if name in MATS:
        return MATS[name]
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*color, 1)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    if emit is not None:
        b.inputs["Emission Color"].default_value = (*emit, 1)
        b.inputs["Emission Strength"].default_value = strength
    if sss > 0:
        b.inputs["Subsurface Weight"].default_value = sss
        b.inputs["Subsurface Radius"].default_value = (0.3, 0.1, 0.05)
    if coat > 0:
        b.inputs["Coat Weight"].default_value = coat
    if alpha < 1:
        b.inputs["Alpha"].default_value = alpha
    m.use_fake_user = True
    MATS[name] = m
    return m


def noise_mat(name, c1, c2, scale=6.0, rough=0.85, bump=0.3, brick=False, brick_mortar=None):
    """Stone / dirt / wood material with procedural texture and bump."""
    if name in MATS:
        return MATS[name]
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    b = nt.nodes["Principled BSDF"]
    b.inputs["Roughness"].default_value = rough
    tc = nt.nodes.new("ShaderNodeTexCoord")
    noise = nt.nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = scale
    noise.inputs["Detail"].default_value = 8
    nt.links.new(tc.outputs["Object"], noise.inputs["Vector"])
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].color = (*c1, 1)
    ramp.color_ramp.elements[1].color = (*c2, 1)
    nt.links.new(noise.outputs["Fac"], ramp.inputs["Fac"])
    bmp = nt.nodes.new("ShaderNodeBump")
    bmp.inputs["Strength"].default_value = bump
    if brick:
        br = nt.nodes.new("ShaderNodeTexBrick")
        br.inputs["Scale"].default_value = 1.0
        br.inputs["Brick Width"].default_value = 0.45
        br.inputs["Row Height"].default_value = 0.2
        br.inputs["Mortar Size"].default_value = 0.018
        nt.links.new(tc.outputs["Object"], br.inputs["Vector"])
        nt.links.new(ramp.outputs["Color"], br.inputs["Color1"])
        mix = nt.nodes.new("ShaderNodeMix")
        mix.data_type = "RGBA"
        nt.links.new(ramp.outputs["Color"], br.inputs["Color2"])
        br.inputs["Mortar"].default_value = (*(brick_mortar or (0.08, 0.08, 0.09)), 1)
        nt.links.new(br.outputs["Color"], b.inputs["Base Color"])
        nt.links.new(br.outputs["Fac"], bmp.inputs["Height"])
    else:
        nt.links.new(ramp.outputs["Color"], b.inputs["Base Color"])
        nt.links.new(noise.outputs["Fac"], bmp.inputs["Height"])
    nt.links.new(bmp.outputs["Normal"], b.inputs["Normal"])
    m.use_fake_user = True
    MATS[name] = m
    return m


# ----------------------------------------------------------------------------- primitives


def _finish(o, m, parent, smooth, subsurf, bevel):
    COLL.objects.link(o)
    if m is not None:
        o.data.materials.append(m)
    if parent is not None:
        o.parent = parent
    if smooth:
        o.data.polygons.foreach_set("use_smooth", [True] * len(o.data.polygons))
    if bevel:
        mod = o.modifiers.new("bevel", "BEVEL")
        mod.width = bevel
        mod.segments = 2
    if subsurf:
        mod = o.modifiers.new("sub", "SUBSURF")
        mod.levels = subsurf
        mod.render_levels = subsurf
    return o


def _mesh_from_bmesh(name, bm):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    return bpy.data.objects.new(name, me)


def empty(name, loc=(0, 0, 0), parent=None, rot=(0, 0, 0)):
    o = bpy.data.objects.new(name, None)
    o.location = loc
    o.rotation_euler = rot
    COLL.objects.link(o)
    if parent is not None:
        o.parent = parent
    return o


def sphere(loc, scale, m, parent=None, seg=24, rot=(0, 0, 0), subsurf=0):
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=seg, v_segments=seg // 2, radius=1.0)
    o = _mesh_from_bmesh("sphere", bm)
    o.location = loc
    o.scale = scale if isinstance(scale, tuple) else (scale, scale, scale)
    o.rotation_euler = rot
    return _finish(o, m, parent, True, subsurf, 0)


def cyl(loc, r1, r2, depth, m, parent=None, rot=(0, 0, 0), seg=24, smooth=True, bevel=0.0, subsurf=0):
    """Cylinder / cone along local Z, centered on loc."""
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=seg, radius1=r1, radius2=r2, depth=depth)
    o = _mesh_from_bmesh("cyl", bm)
    o.location = loc
    o.rotation_euler = rot
    return _finish(o, m, parent, smooth, subsurf, bevel)


def box(loc, size, m, parent=None, rot=(0, 0, 0), bevel=0.0, subsurf=0):
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    for v in bm.verts:
        v.co.x *= size[0]
        v.co.y *= size[1]
        v.co.z *= size[2]
    o = _mesh_from_bmesh("box", bm)
    o.location = loc
    o.rotation_euler = rot
    return _finish(o, m, parent, False, subsurf, bevel)


def torus(loc, major, minor, m, parent=None, rot=(0, 0, 0), seg=32):
    bm = bmesh.new()
    for i in range(seg):
        a = 2 * math.pi * i / seg
        for j in range(12):
            b = 2 * math.pi * j / 12
            r = major + minor * math.cos(b)
            bm.verts.new((r * math.cos(a), r * math.sin(a), minor * math.sin(b)))
    bm.verts.ensure_lookup_table()
    for i in range(seg):
        for j in range(12):
            a = i * 12 + j
            b = ((i + 1) % seg) * 12 + j
            c = ((i + 1) % seg) * 12 + (j + 1) % 12
            d = i * 12 + (j + 1) % 12
            bm.faces.new((bm.verts[a], bm.verts[b], bm.verts[c], bm.verts[d]))
    o = _mesh_from_bmesh("torus", bm)
    o.location = loc
    o.rotation_euler = rot
    return _finish(o, m, parent, True, 0, 0)


def tube(points, radius, m, parent=None):
    """A bent tube through points (tails, IV lines)."""
    cu = bpy.data.curves.new("tube", "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = radius
    cu.bevel_resolution = 4
    sp = cu.splines.new("POLY")
    sp.points.add(len(points) - 1)
    for i, p in enumerate(points):
        sp.points[i].co = (*p, 1)
        sp.points[i].radius = 1.0 - 0.8 * i / max(1, len(points) - 1)
    o = bpy.data.objects.new("tube", cu)
    COLL.objects.link(o)
    o.data.materials.append(m)
    if parent is not None:
        o.parent = parent
    return o


def bowl(loc, radius, height, cut, m, parent=None):
    """Sphere with its top cut off (cauldron)."""
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=40, v_segments=20, radius=1.0)
    gone = [v for v in bm.verts if v.co.z > cut]
    bmesh.ops.delete(bm, geom=gone, context="VERTS")
    for v in bm.verts:
        v.co.x *= radius
        v.co.y *= radius
        v.co.z *= height
    o = _mesh_from_bmesh("bowl", bm)
    o.location = loc
    mod = o.modifiers.new("solid", "SOLIDIFY")
    mod.thickness = 0.06
    return _finish(o, m, parent, True, 0, 0)


# ----------------------------------------------------------------------------- materials

M = {}


def build_materials():
    M["leather"] = mat("leather", (0.16, 0.105, 0.07), rough=0.55, coat=0.2)
    M["leather_dark"] = mat("leather_dark", (0.07, 0.05, 0.04), rough=0.6)
    M["hat"] = mat("hat", (0.045, 0.04, 0.036), rough=0.7)
    M["band"] = mat("band", (0.32, 0.09, 0.06), rough=0.5)
    M["mask"] = mat("mask", (0.83, 0.77, 0.63), rough=0.45, coat=0.3)
    M["brass"] = mat("brass", (0.75, 0.52, 0.22), rough=0.3, metal=1.0)
    M["lens"] = mat("lens", (0.2, 0.9, 0.35), rough=0.1, emit=(0.3, 1.0, 0.4), strength=3.0)
    M["vial_green"] = mat("vial_green", (0.2, 0.85, 0.3), rough=0.15, emit=(0.2, 0.9, 0.3), strength=1.2)
    M["vial_red"] = mat("vial_red", (0.85, 0.15, 0.1), rough=0.15, emit=(0.9, 0.2, 0.1), strength=0.8)
    M["skin"] = mat("skin", (0.85, 0.58, 0.42), rough=0.55, sss=0.15)
    M["skin_sick"] = mat("skin_sick", (0.55, 0.66, 0.42), rough=0.6, sss=0.1)
    M["spot"] = mat("spot", (0.3, 0.12, 0.3), rough=0.6)
    M["hair"] = mat("hair", (0.16, 0.08, 0.04), rough=0.7)
    M["hair_red"] = mat("hair_red", (0.55, 0.16, 0.06), rough=0.6)
    M["shirt"] = mat("shirt", (0.82, 0.8, 0.74), rough=0.8)
    M["vest"] = mat("vest", (0.1, 0.2, 0.42), rough=0.7)
    M["trousers"] = mat("trousers", (0.17, 0.15, 0.15), rough=0.8)
    M["boot"] = mat("boot", (0.09, 0.06, 0.04), rough=0.5)
    M["eye"] = mat("eye", (0.02, 0.02, 0.02), rough=0.2)
    M["white"] = mat("white", (0.9, 0.9, 0.88), rough=0.6)
    M["dress"] = mat("dress", (0.14, 0.12, 0.16), rough=0.7)
    M["orderly"] = mat("orderly", (0.22, 0.32, 0.26), rough=0.75)
    M["wood"] = noise_mat("wood", (0.22, 0.13, 0.07), (0.36, 0.22, 0.12), scale=3.0, rough=0.7, bump=0.15)
    M["wood_dark"] = noise_mat("wood_dark", (0.11, 0.07, 0.04), (0.2, 0.12, 0.07), scale=3.0, rough=0.75, bump=0.15)
    M["iron"] = mat("iron", (0.05, 0.05, 0.06), rough=0.35, metal=0.9)
    M["steel"] = mat("steel", (0.6, 0.6, 0.66), rough=0.25, metal=1.0)
    M["water"] = mat("water", (0.08, 0.3, 0.7), rough=0.05, emit=(0.1, 0.35, 0.8), strength=0.6)
    M["potion"] = mat("potion", (0.25, 0.85, 0.3), rough=0.05, emit=(0.3, 0.95, 0.35), strength=1.2)
    M["ember"] = mat("ember", (1.0, 0.35, 0.05), rough=0.5, emit=(1.0, 0.4, 0.05), strength=6.0)
    M["blanket"] = noise_mat("blanket", (0.2, 0.28, 0.18), (0.27, 0.36, 0.23), scale=20, rough=0.9, bump=0.1)
    M["pillow"] = mat("pillow", (0.85, 0.83, 0.76), rough=0.8)
    M["blood"] = mat("blood", (0.45, 0.02, 0.03), rough=0.15, coat=0.5)
    M["glass"] = mat("glass", (0.8, 0.9, 0.95), rough=0.05, alpha=0.5)
    M["copper"] = mat("copper", (0.78, 0.38, 0.17), rough=0.3, metal=1.0)
    M["copper_dark"] = mat("copper_dark", (0.45, 0.2, 0.08), rough=0.4, metal=1.0)
    M["rat"] = noise_mat("rat", (0.12, 0.1, 0.1), (0.22, 0.18, 0.17), scale=40, rough=0.9, bump=0.2)
    M["rat_pink"] = mat("rat_pink", (0.75, 0.45, 0.45), rough=0.6, sss=0.2)
    M["red_eye"] = mat("red_eye", (1, 0.05, 0.05), emit=(1, 0.05, 0.05), strength=4.0)
    M["flesh"] = noise_mat("flesh", (0.22, 0.03, 0.05), (0.45, 0.07, 0.1), scale=4, rough=0.45, bump=0.5)
    M["flesh_dark"] = noise_mat("flesh_dark", (0.06, 0.03, 0.05), (0.16, 0.05, 0.08), scale=5, rough=0.5, bump=0.4)
    M["spike"] = mat("spike", (0.55, 0.52, 0.62), rough=0.25, metal=0.9)
    M["yellow_eye"] = mat("yellow_eye", (1, 0.85, 0.2), emit=(1, 0.8, 0.2), strength=6.0)
    M["bone"] = mat("bone", (0.8, 0.76, 0.64), rough=0.6)
    M["candle"] = mat("candle", (0.9, 0.85, 0.7), rough=0.6, sss=0.3)
    M["flame"] = mat("flame", (1, 0.75, 0.3), emit=(1, 0.7, 0.25), strength=12.0)
    M["stone"] = noise_mat("stone", (0.17, 0.18, 0.21), (0.27, 0.28, 0.32), scale=5, rough=0.85, bump=0.35)
    M["stone_room"] = noise_mat("stone_room", (0.22, 0.22, 0.25), (0.31, 0.31, 0.34), scale=5, rough=0.85, bump=0.35)
    M["stone_bath"] = noise_mat("stone_bath", (0.16, 0.19, 0.22), (0.24, 0.29, 0.32), scale=5, rough=0.6, bump=0.3)
    M["dirt"] = noise_mat("dirt", (0.08, 0.09, 0.06), (0.17, 0.17, 0.11), scale=1.6, rough=0.95, bump=0.4)
    M["wall"] = noise_mat("wall", (0.17, 0.16, 0.16), (0.26, 0.24, 0.23), scale=6, rough=0.9, bump=0.4, brick=True)
    M["wall_top"] = noise_mat("wall_top", (0.24, 0.23, 0.22), (0.34, 0.32, 0.3), scale=8, rough=0.9, bump=0.3)
    M["bow"] = mat("bow", (0.55, 0.36, 0.15), rough=0.5)
    M["archer"] = mat("archer", (0.12, 0.3, 0.55), rough=0.6)


build_materials()

# ----------------------------------------------------------------------------- characters


class Rig:
    def __init__(self):
        self.root = empty("root")
        self.body = empty("body", parent=self.root)
        self.parts = {}

    def __getitem__(self, k):
        return self.parts[k]


def build_rat():
    rig = Rig()
    b = rig.body
    sphere((0, 0.03, 0.15), (0.15, 0.27, 0.14), M["rat"], b)
    head = empty("head", (0, -0.25, 0.17), b)
    rig.parts["head"] = head
    cyl((0, -0.06, 0), 0.09, 0.035, 0.2, M["rat"], head, rot=(math.radians(90), 0, 0), subsurf=1)
    sphere((0, -0.17, -0.01), 0.03, M["rat_pink"], head)
    for sx in (-1, 1):
        sphere((sx * 0.07, 0.02, 0.08), (0.05, 0.015, 0.05), M["rat_pink"], head)
        sphere((sx * 0.055, -0.07, 0.04), 0.02, M["red_eye"], head)
    tube([(0, 0.28, 0.13), (0.05, 0.42, 0.1), (0.13, 0.52, 0.05), (0.2, 0.6, 0.03)], 0.022, M["rat_pink"], b)
    for i, (x, y) in enumerate(((-0.1, -0.13), (0.1, -0.13), (-0.1, 0.17), (0.1, 0.17))):
        f = empty("foot%d" % i, (x, y, 0.03), b)
        sphere((0, 0, 0), (0.035, 0.05, 0.03), M["rat_pink"], f)
        rig.parts["foot%d" % i] = f
    return rig


def build_boss():
    rig = Rig()
    b = rig.body
    for sx in (-1, 1):
        cyl((sx * 0.55, 0, 0.4), 0.3, 0.26, 0.8, M["flesh_dark"], b, subsurf=1)
        for c in range(3):
            cyl((sx * 0.55 + (c - 1) * 0.12, -0.3, 0.05), 0.04, 0.0, 0.18, M["bone"], b, rot=(math.radians(-90), 0, 0))
    torso = empty("torso", (0, 0, 0.8), b)
    rig.parts["torso"] = torso
    sphere((0, 0.05, 0.75), (1.0, 0.8, 0.85), M["flesh"], torso, subsurf=1)
    sphere((0, -0.35, 0.55), (0.65, 0.4, 0.5), M["flesh_dark"], torso)
    sphere((0, 0.25, 1.2), (0.85, 0.6, 0.45), M["flesh"], torso)
    # Spiked iron ball grown into the chest
    sphere((0.1, -0.62, 0.9), 0.38, M["iron"], torso)
    for i in range(14):
        a = i * 2.39996
        zz = 1 - 2 * (i + 0.5) / 14
        r = math.sqrt(1 - zz * zz)
        d = Vector((math.cos(a) * r, math.sin(a) * r, zz))
        if d.y > 0.5:
            continue
        loc = Vector((0.1, -0.62, 0.9)) + d * 0.4
        o = cyl((0, 0, 0), 0.08, 0.0, 0.3, M["spike"], torso)
        o.location = loc + d * 0.12
        o.rotation_mode = "QUATERNION"
        o.rotation_quaternion = Vector((0, 0, 1)).rotation_difference(d)
    # Back spikes
    for i in range(7):
        a = math.radians(-70 + i * 23)
        loc = Vector((math.sin(a) * 0.75, 0.35, 1.25 + math.cos(a) * 0.35))
        d = Vector((math.sin(a) * 0.6, 0.5, math.cos(a))).normalized()
        o = cyl((0, 0, 0), 0.1, 0.0, 0.55, M["spike"], torso)
        o.location = loc + d * 0.2
        o.rotation_mode = "QUATERNION"
        o.rotation_quaternion = Vector((0, 0, 1)).rotation_difference(d)
    for side, sx in (("L", -1), ("R", 1)):
        sh = empty("sh" + side, (sx * 0.95, -0.05, 1.15), torso)
        rig.parts["sh" + side] = sh
        cyl((0, 0, -0.45), 0.24, 0.18, 0.9, M["flesh"], sh, subsurf=1)
        sphere((0, -0.05, -0.95), (0.22, 0.25, 0.2), M["flesh_dark"], sh)
        for c in range(3):
            o = cyl(((c - 1) * 0.1, -0.2, -1.05), 0.045, 0.0, 0.32, M["bone"], sh, rot=(math.radians(-130), 0, 0))
    head = empty("head", (0, -0.45, 1.55), torso)
    rig.parts["head"] = head
    sphere((0, 0, 0), (0.36, 0.32, 0.3), M["flesh_dark"], head)
    for sx in (-1, 1):
        sphere((sx * 0.14, -0.27, 0.05), 0.06, M["yellow_eye"], head)
        cyl((sx * 0.25, 0.0, 0.3), 0.07, 0.0, 0.35, M["bone"], head, rot=(math.radians(-20), math.radians(sx * 30), 0))
    for c in range(5):
        cyl(((c - 2) * 0.07, -0.28, -0.15), 0.025, 0.0, 0.09, M["bone"], head, rot=(math.radians(180), 0, 0))
    return rig


# ----------------------------------------------------------------------------- poses


def pose_walk(rig, phase, kind):
    s = math.sin(phase)
    c = math.cos(phase)
    if kind == "rat":
        rig.body.location.z = abs(s) * 0.03
        for i in range(4):
            f = rig["foot%d" % i]
            off = 1 if i in (0, 3) else -1
            f.location.y = (-0.13 if i < 2 else 0.17) + off * s * 0.05
        return
    swing = 0.55 if kind != "doctor" else 0.45
    rig["hipL"].rotation_euler.x = s * swing
    rig["hipR"].rotation_euler.x = -s * swing
    rig["kneeL"].rotation_euler.x = -max(0, -c) * 0.6
    rig["kneeR"].rotation_euler.x = -max(0, c) * 0.6
    rig.body.location.z = abs(s) * 0.035
    if kind in ("doctor", "healthy"):
        rig["shL"].rotation_euler.x = -s * 0.45
        rig["shR"].rotation_euler.x = s * 0.45
    if kind == "sick":
        rig["spine"].rotation_euler.x = math.radians(18)
        rig["head"].rotation_euler.x = math.radians(-8)
        rig["shL"].rotation_euler = (math.radians(55), 0, math.radians(25))
        rig["shR"].rotation_euler = (math.radians(50), 0, math.radians(-25))
    if kind == "orderly":
        rig["shL"].rotation_euler = (math.radians(170), 0, math.radians(-12))
        rig["shR"].rotation_euler = (math.radians(170), 0, math.radians(12))


# ----------------------------------------------------------------------------- rendering

SPRITES = []  # (name, frame, path, anchor_x, anchor_y)


def setup_camera(target, res_w, res_h):
    SC.render.resolution_x = res_w
    SC.render.resolution_y = res_h
    CAM.data.ortho_scale = max(res_w, res_h) / PPM
    d = 20.0
    CAM.location = Vector(target) + Vector((0, -d * math.cos(PITCH), d * math.sin(PITCH)))
    CAM.rotation_euler = (math.pi / 2 - PITCH, 0, 0)
    bpy.context.view_layer.update()


def render(name, frame, target, res_w, res_h, anchor=(0, 0, 0)):
    setup_camera(target, res_w, res_h)
    path = os.path.join(TMP, "%s_%02d.png" % (name, frame))
    SC.render.filepath = path
    bpy.ops.render.render(write_still=True)
    uv = world_to_camera_view(SC, CAM, Vector(anchor))
    ax = uv.x * res_w
    ay = (1 - uv.y) * res_h
    SPRITES.append((name, frame, path, ax, ay))


# Facing directions rendered: S, SE, E, NE, N (the game mirrors SW, W, NW).
DIRS = [(0, 1), (0.7071, 0.7071), (1, 0), (0.7071, -0.7071), (0, -1)]


def face(rig, fx, fy):
    rig.root.rotation_euler.z = math.atan2(fx, fy)



# ----------------------------------------------------------------------------- KayKit assets (CC0)

KAYKIT_REPOS = [
    "KayKit-Character-Pack-Adventures-1.0",
    "KayKit-Dungeon-Remastered-1.0",
    "KayKit-Halloween-Bits-1.0",
    "KayKit-Furniture-Bits-1.0",
]


def ensure_assets():
    os.makedirs(ASSETS, exist_ok=True)
    for r in KAYKIT_REPOS:
        if not os.path.isdir(os.path.join(ASSETS, r)):
            subprocess.run(["git", "clone", "--depth", "1", "https://github.com/KayKit-Game-Assets/" + r,
                            os.path.join(ASSETS, r)], check=True)


ensure_assets()
CH = os.path.join(ASSETS, "KayKit-Character-Pack-Adventures-1.0/addons/kaykit_character_pack_adventures/Characters/gltf/")
TEX = os.path.join(ASSETS, "KayKit-Character-Pack-Adventures-1.0/addons/kaykit_character_pack_adventures/Textures/")
DUN = os.path.join(ASSETS, "KayKit-Dungeon-Remastered-1.0/addons/kaykit_dungeon_remastered/Assets/gltf/")
HAL = os.path.join(ASSETS, "KayKit-Halloween-Bits-1.0/addons/kaykit_halloween_bits/Assets/gltf/")
FUR = os.path.join(ASSETS, "KayKit-Furniture-Bits-1.0/addons/kaykit_furniture_bits/Assets/gltf/")
KS = 0.72  # KayKit units to meters: characters end up about 1.6 m tall


def import_file(path):
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=path)
    new = [o for o in bpy.data.objects if o not in before]
    for o in new:
        for c in o.users_collection:
            c.objects.unlink(o)
        COLL.objects.link(o)
    return new


def prop(path, scale, loc=(0, 0, 0), rot_z=0.0, parent=None):
    new = import_file(path)
    root = empty("prop", loc, parent, (0, 0, rot_z))
    for o in new:
        if o.parent is None:
            o.parent = root
    root.scale = scale if isinstance(scale, tuple) else (scale, scale, scale)
    return root


# --- texture recoloring (KayKit textures are palettes of gradient swatches)


def rgb_to_hsv(a):
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    mx = a.max(-1)
    mn = a.min(-1)
    d = mx - mn
    h = np.zeros_like(mx)
    m = d > 1e-6
    rr = m & (mx == r)
    gg = m & (mx == g) & ~rr
    bb = m & ~rr & ~gg
    h[rr] = ((g - b)[rr] / d[rr]) % 6
    h[gg] = (b - r)[gg] / d[gg] + 2
    h[bb] = (r - g)[bb] / d[bb] + 4
    h = h * 60
    s = np.where(mx > 1e-6, d / np.maximum(mx, 1e-6), 0)
    return h, s, mx


def recolor(tex_name, rules, out_name):
    """rules: (hue_min, hue_max, sat_min, val_min, val_max, target_rgb). Keeps each swatch's shading."""
    out = os.path.join(TMP, out_name + ".png")
    im = Image.open(os.path.join(TEX, tex_name)).convert("RGBA")
    a = np.asarray(im).astype(np.float32) / 255.0
    rgb = a[..., :3]
    h, s, v = rgb_to_hsv(rgb)
    res = rgb.copy()
    for hmin, hmax, smin, vmin, vmax, target in rules:
        if hmin <= hmax:
            hm = (h >= hmin) & (h <= hmax)
        else:
            hm = (h >= hmin) | (h <= hmax)
        m = hm & (s >= smin) & (v >= vmin) & (v <= vmax)
        t = np.array(target, dtype=np.float32)
        shade = (v[m] / max(1e-3, float(np.median(v[m])) if m.any() else 1.0))[:, None]
        res[m] = np.clip(t[None, :] * shade, 0, 1)
    a[..., :3] = res
    Image.fromarray((a * 255).astype(np.uint8)).save(out)
    return out


def retexture(objs, png):
    img = bpy.data.images.load(png, check_existing=True)
    for o in objs:
        if o.type != "MESH":
            continue
        for m in o.data.materials:
            if m is None or not m.use_nodes:
                continue
            for n in m.node_tree.nodes:
                if n.type == "TEX_IMAGE":
                    n.image = img


# --- characters


class Char:
    def __init__(self, name, keep, texture=None):
        new = import_file(CH + name + ".glb")
        self.rig = [o for o in new if o.type == "ARMATURE"][0]
        self.meshes = []
        for o in new:
            if o.type != "MESH":
                continue
            base = o.name.split(".")[0]
            if base in keep:
                self.meshes.append(o)
            else:
                bpy.data.objects.remove(o, do_unlink=True)
        if texture:
            # Copy materials so recolors never leak into the next character.
            for o in self.meshes:
                for i, m in enumerate(o.data.materials):
                    if m is not None:
                        o.data.materials[i] = m.copy()
            retexture(self.meshes, texture)
        self.root = empty("root")
        self.rig.parent = self.root
        ad = self.rig.animation_data or self.rig.animation_data_create()
        for t in ad.nla_tracks:
            t.mute = True
        self.action = None

    def scale(self, s=KS):
        self.root.scale = (s, s, s)

    def act(self, name):
        act = bpy.data.actions.get(name)
        if act is None:
            act = [a for a in bpy.data.actions if a.name.split(".")[0] == name][0]
        ad = self.rig.animation_data
        ad.action = act
        try:
            if act.slots:
                ad.action_slot = act.slots[0]
        except AttributeError:
            pass
        self.action = act

    def at(self, f):
        """Pose at fraction f (0..1) of the current action."""
        s, e = self.action.frame_range
        fr = s + (e - s) * f
        SC.frame_set(int(fr), subframe=fr - int(fr))
        bpy.context.view_layer.update()

    def capture(self, bones):
        return {b: self.rig.pose.bones[b].matrix_basis.copy() for b in bones}

    def apply(self, pose):
        for b, m in pose.items():
            self.rig.pose.bones[b].matrix_basis = m
        bpy.context.view_layer.update()

    def attach(self, obj, bone):
        """Parent obj to a bone, keeping its current world transform (character in rest pose)."""
        self.rig.data.pose_position = "REST"
        bpy.context.view_layer.update()
        pb = self.rig.pose.bones[bone]
        mw = obj.matrix_world.copy()
        obj.parent = self.rig
        obj.parent_type = "BONE"
        obj.parent_bone = bone
        mb = self.rig.matrix_world @ pb.bone.matrix_local @ Matrix.Translation((0, pb.bone.length, 0))
        obj.matrix_parent_inverse = mb.inverted()
        obj.matrix_world = mw
        self.rig.data.pose_position = "POSE"
        bpy.context.view_layer.update()

    def face(self, fx, fy):
        self.root.rotation_euler.z = math.atan2(fx, fy)

    def bone_world(self, bone):
        pb = self.rig.pose.bones[bone]
        return self.root.matrix_world @ self.rig.matrix_basis @ pb.head if False else self.rig.matrix_world @ pb.head


ARMS = ["upperarm.l", "lowerarm.l", "wrist.l", "hand.l", "upperarm.r", "lowerarm.r", "wrist.r", "hand.r"]


def arms_up_pose(c):
    c.act("Cheer")
    c.at(0.45)
    return c.capture(ARMS)


def doctor_texture():
    return recolor("mage_texture.png", [
        (215, 300, 0.2, 0.05, 1.0, (0.22, 0.14, 0.09)),  # purple robe -> dark leather
        (300, 359, 0.35, 0.05, 1.0, (0.12, 0.07, 0.05)),  # magenta cape -> near black
        (0, 12, 0.6, 0.05, 1.0, (0.12, 0.07, 0.05)),
    ], "tex_doctor")


def build_doctor():
    c = Char("Mage", {"Mage_ArmLeft", "Mage_ArmRight", "Mage_Body", "Mage_Cape", "Mage_Head", "Mage_LegLeft", "Mage_LegRight"},
             doctor_texture())
    hat = mat("hat", (0.035, 0.03, 0.028), rough=0.65)
    band = mat("band", (0.35, 0.08, 0.05), rough=0.5)
    mask = mat("mask", (0.86, 0.8, 0.66), rough=0.5, coat=0.25)
    brass = mat("brass", (0.8, 0.55, 0.22), rough=0.3, metal=1.0)
    lens = mat("lens", (0.25, 1.0, 0.4), rough=0.1, emit=(0.3, 1.0, 0.45), strength=4.0)
    parts = []
    # Wide-brim hat, tilted back so the beak stays visible from above.
    hat_root = empty("hatroot", (0, 0.08, 2.0), rot=(math.radians(14), 0, 0))
    parts.append(hat_root)
    cyl((0, 0, 0), 0.8, 0.78, 0.07, hat, hat_root, seg=14, smooth=False, bevel=0.015)
    cyl((0, 0, 0.27), 0.47, 0.43, 0.5, hat, hat_root, seg=14, smooth=False, bevel=0.02)
    cyl((0, 0, 0.09), 0.48, 0.475, 0.11, band, hat_root, seg=14, smooth=False)
    # Leather mask with the long beak
    parts.append(sphere((0, -0.33, 1.52), (0.46, 0.3, 0.42), mask, seg=16))
    parts.append(cyl((0, -0.86, 1.36), 0.2, 0.025, 0.85, mask, rot=(math.radians(-105), 0, 0), seg=10, smooth=False))
    parts.append(torus((0, -0.62, 1.42), 0.17, 0.035, brass, rot=(math.radians(-15), 0, 0), seg=16))
    for sx in (-1, 1):
        parts.append(cyl((sx * 0.21, -0.6, 1.66), 0.14, 0.14, 0.07, brass, rot=(math.radians(80), 0, 0), seg=14))
        parts.append(cyl((sx * 0.21, -0.64, 1.66), 0.105, 0.105, 0.04, lens, rot=(math.radians(80), 0, 0), seg=14))
    # Potion vials on the belt
    for x, m in ((0.18, mat("vial_g", (0.2, 0.9, 0.3), rough=0.1, emit=(0.2, 0.9, 0.3), strength=1.5)),
                 (0.3, mat("vial_r", (0.9, 0.15, 0.1), rough=0.1, emit=(0.9, 0.2, 0.1), strength=1.0))):
        v = cyl((x, -0.4, 0.62), 0.055, 0.055, 0.2, m, seg=10)
        c.attach(v, "hips")
    for p in parts:
        c.attach(p, "head")
    return c


def build_patient(sick):
    tex = recolor("barbarian_texture.png", [
        (5, 40, 0.15, 0.6, 1.0, (0.62, 0.72, 0.5)),  # skin -> plague green
    ], "tex_sick") if sick else None
    c = Char("Barbarian", {"Barbarian_ArmLeft", "Barbarian_ArmRight", "Barbarian_Body", "Barbarian_Head",
                           "Barbarian_LegLeft", "Barbarian_LegRight"}, tex)
    if sick:
        spot = mat("spot", (0.32, 0.1, 0.32), rough=0.6)
        for p in ((0.25, -0.45, 1.5), (-0.3, -0.4, 1.7), (0.1, -0.5, 1.35)):
            c.attach(sphere(p, 0.05, spot, seg=8), "head")
    return c


def build_nurse():
    tex = recolor("rogue_texture.png", [
        (100, 180, 0.25, 0.05, 1.0, (0.2, 0.18, 0.25)),  # green -> dark grey dress
    ], "tex_nurse")
    c = Char("Rogue", {"Rogue_ArmLeft", "Rogue_ArmRight", "Rogue_Body", "Rogue_Head", "Rogue_LegLeft", "Rogue_LegRight"}, tex)
    white = mat("apron", (0.9, 0.9, 0.86), rough=0.7)
    a = box((0, -0.36, 0.75), (0.62, 0.05, 0.8), white, rot=(math.radians(-6), 0, 0), bevel=0.03)
    c.attach(a, "hips")
    cap = cyl((0, -0.05, 2.05), 0.36, 0.33, 0.14, white, seg=12, smooth=False, bevel=0.02)
    c.attach(cap, "head")
    redc = mat("redcross", (0.85, 0.1, 0.1), rough=0.5)
    c.attach(box((0, -0.3, 2.06), (0.16, 0.02, 0.05), redc), "head")
    c.attach(box((0, -0.3, 2.06), (0.05, 0.02, 0.16), redc), "head")
    # Big syringe in the right hand
    glass = mat("glass", (0.85, 0.92, 0.95), rough=0.05, alpha=0.45)
    potion = mat("potion", (0.25, 0.85, 0.3), rough=0.05, emit=(0.3, 0.95, 0.35), strength=1.5)
    steel = mat("steel", (0.7, 0.7, 0.75), rough=0.25, metal=1.0)
    hand = c.bone_world("handslot.r")
    syr = empty("syringe", tuple(hand))
    cyl((0, -0.35, 0), 0.11, 0.11, 0.7, glass, syr, rot=(math.radians(90), 0, 0), seg=14)
    cyl((0, -0.32, 0), 0.085, 0.085, 0.55, potion, syr, rot=(math.radians(90), 0, 0), seg=14)
    cyl((0, -0.85, 0), 0.015, 0.015, 0.3, steel, syr, rot=(math.radians(90), 0, 0), seg=8)
    cyl((0, 0.12, 0), 0.03, 0.03, 0.3, steel, syr, rot=(math.radians(90), 0, 0), seg=8)
    cyl((0, 0.28, 0), 0.1, 0.1, 0.03, steel, syr, rot=(math.radians(90), 0, 0), seg=12)
    c.attach(syr, "handslot.r")
    return c


def build_orderly():
    tex = recolor("rogue_texture.png", [
        (100, 180, 0.25, 0.05, 1.0, (0.55, 0.57, 0.52)),  # green -> grey linen
    ], "tex_orderly")
    c = Char("Rogue_Hooded", {"Rogue_ArmLeft", "Rogue_ArmRight", "Rogue_Body", "Rogue_Head_Hooded", "Rogue_LegLeft",
                              "Rogue_LegRight", "Rogue_Cape"}, tex)
    white = mat("apron", (0.9, 0.9, 0.86), rough=0.7)
    c.attach(sphere((0, -0.45, 1.42), (0.32, 0.12, 0.18), white, seg=10), "head")  # mouth mask
    return c


def build_archer():
    c = Char("Rogue_Hooded", {"Rogue_ArmLeft", "Rogue_ArmRight", "Rogue_Body", "Rogue_Head_Hooded", "Rogue_LegLeft",
                              "Rogue_LegRight", "Rogue_Cape", "2H_Crossbow"})
    return c


# --- procedural models that KayKit does not have


def build_cauldron(liquid_mat, with_patient):
    iron = mat("iron", (0.05, 0.05, 0.06), rough=0.35, metal=0.9)
    bowl((0, 0, 0.62), 1.0, 0.6, 0.55, iron)
    torus((0, 0, 0.95), 0.96, 0.07, iron)
    for a in range(3):
        ang = a * 2.094 + 0.5
        cyl((math.cos(ang) * 0.62, math.sin(ang) * 0.62, 0.12), 0.07, 0.045, 0.28, iron, seg=8)
    wood = M["wood_dark"]
    for a in range(4):
        ang = a * 0.785 + 0.3
        cyl((0, 0, 0.08), 0.07, 0.07, 1.7, wood, rot=(math.radians(88), 0, ang), seg=8, smooth=False)
    sphere((0, 0, 0.05), (0.55, 0.55, 0.06), M["ember"])
    cyl((0, 0, 0.9), 0.93, 0.93, 0.03, liquid_mat, seg=40)
    if with_patient:
        c = build_patient(True)
        c.act("Sit_Floor_Pose")
        c.at(0)
        c.root.location = (0, 0.1, 0.0)
        c.scale()
        return c
    return None


def build_iv_pole(x, y):
    steel = mat("steel", (0.7, 0.7, 0.75), rough=0.25, metal=1.0)
    blood = mat("blood", (0.5, 0.02, 0.03), rough=0.15, coat=0.6)
    cyl((x, y, 0.95), 0.02, 0.02, 1.9, steel, seg=8)
    cyl((x, y, 0.02), 0.18, 0.18, 0.03, steel, seg=12)
    cyl((x + 0.14, y, 1.88), 0.012, 0.012, 0.3, steel, rot=(0, math.radians(90), 0), seg=6)
    box((x + 0.25, y, 1.66), (0.17, 0.06, 0.3), blood, bevel=0.03, subsurf=1)
    tube([(x + 0.25, y, 1.5), (x + 0.4, y - 0.3, 1.0), (x + 0.6, y - 0.55, 0.75)], 0.008, mat("tubeglass", (0.8, 0.9, 0.95), rough=0.1))


# ----------------------------------------------------------------------------- rendering helpers


def clear_models():
    keep = {CAM}
    for o in list(bpy.data.objects):
        if o in keep or o.type == "LIGHT":
            continue
        bpy.data.objects.remove(o, do_unlink=True)
    for a in list(bpy.data.actions):
        bpy.data.actions.remove(a)
    bpy.data.orphans_purge(do_recursive=True)
    SC.frame_set(1)


def render_dirs(name, c, frames, fn, res, height):
    """fn(frame_fraction) poses the character; renders 5 directions x frames."""
    for d, (fx, fy) in enumerate(DIRS):
        c.face(fx, fy)
        for f in range(frames):
            fn(f / frames)
            render(name, d * frames + f, (0, 0, height / 2), res, res)



def all_sprites():
    t0 = time.time()
    fr = 4 if QUICK else 6

    # ---- Plague doctor: run, run while carrying (arms up), idle
    clear_models()
    c = build_doctor()
    c.scale()
    up = arms_up_pose(c)
    c.act("Running_A")
    render_dirs("doctor", c, fr, lambda f: c.at(f), 300, 1.9)
    render_dirs("doctor_carry", c, fr, lambda f: (c.at(f), c.apply(up)), 300, 1.9)
    c.act("Idle")
    render_dirs("doctor_idle", c, 1, lambda f: c.at(0.0), 300, 1.9)

    # ---- Patients: sick walking, cured walking, lying, sitting, dead
    clear_models()
    c = build_patient(True)
    c.scale()
    c.act("Walking_B")
    render_dirs("sick", c, fr, lambda f: c.at(f), 260, 1.6)
    c.act("Lie_Pose")
    c.at(0)
    # Lay him along the x axis, head to the west, centered on the origin.
    for _ in range(2):
        head = c.bone_world("head")
        hips = c.bone_world("hips")
        d = head - hips
        ang = math.atan2(d.y, d.x)
        c.root.rotation_euler.z += math.pi - ang
        bpy.context.view_layer.update()
    mid = (c.bone_world("head") + c.bone_world("hips")) / 2
    c.root.location.x -= mid.x
    c.root.location.y -= mid.y
    bpy.context.view_layer.update()
    render("lying", 0, (0, 0, 0.2), 260, 260)
    c.root.location = (0, 0, 0)
    c.root.rotation_euler = (0, 0, 0)
    c.act("Sit_Chair_Pose")
    c.face(1, 0)
    c.at(0)
    render("sitting", 0, (0, 0, 0.6), 240, 240)

    clear_models()
    c = build_patient(False)
    c.scale()
    c.act("Walking_A")
    render_dirs("healthy", c, fr, lambda f: c.at(f), 260, 1.6)

    # ---- Orderly carrying stretcher-style, arms up
    clear_models()
    c = build_orderly()
    c.scale()
    up = arms_up_pose(c)
    c.act("Running_B")
    render_dirs("orderly", c, fr, lambda f: (c.at(f), c.apply(up)), 280, 1.9)

    # ---- Nurse giving an injection, facing west
    clear_models()
    c = build_nurse()
    c.scale()
    c.act("1H_Melee_Attack_Stab")
    c.face(-1, 0)
    for f, t in enumerate((0.15, 0.45)):
        c.at(t)
        render("nurse", f, (0, 0, 0.8), 300, 300)

    # ---- Rats and the plague beast (procedural)
    clear_models()
    rig = build_rat()
    rig.root.scale = (1.3, 1.3, 1.3)
    for d, (fx, fy) in enumerate(DIRS):
        face(rig, fx, fy)
        for f in range(4):
            pose_walk(rig, 2 * math.pi * f / 4, "rat")
            render("rat", d * 4 + f, (0, 0, 0.25), 180, 180)

    clear_models()
    rig = build_boss()
    for f in range(4):
        ph = 2 * math.pi * f / 4
        rig["torso"].scale = (1 + 0.03 * math.sin(ph), 1, 1 + 0.03 * math.sin(ph))
        rig["shL"].rotation_euler.x = math.radians(20 + 60 * max(0, math.sin(ph)))
        rig["shR"].rotation_euler.x = math.radians(20 + 60 * max(0, math.sin(ph)))
        render("boss", f, (0, 0, 1.4), 560, 560)

    # ---- Cauldron variants
    for liquid in ("water", "potion"):
        for occupied in (False, True):
            clear_models()
            c = build_cauldron(M[liquid], occupied)
            if c is not None:
                c.root.location = (0, 0.05, 0.28)
                bpy.context.view_layer.update()
            render("cauldron_%s%s" % (liquid, "_p" if occupied else ""), 0, (0, 0, 0.6), 320, 320)

    # ---- Bed (KayKit) with IV pole, empty and occupied
    for occupied in (False, True):
        clear_models()
        prop(FUR + "bed_single_A.gltf", 0.75)
        build_iv_pole(-0.85, 0.95)
        if occupied:
            c = build_patient(True)
            c.scale()
            c.act("Lie_Pose")
            c.at(0)
            # head on the pillow (north end)
            for _ in range(2):
                d = c.bone_world("head") - c.bone_world("hips")
                c.root.rotation_euler.z += math.pi / 2 - math.atan2(d.y, d.x)
                bpy.context.view_layer.update()
            mid = (c.bone_world("head") + c.bone_world("hips")) / 2
            c.root.location = (-mid.x, -mid.y + 0.15, 0.45)
            bpy.context.view_layer.update()
        render("bed%s" % ("_p" if occupied else ""), 0, (0, 0, 0.6), 320, 400)

    # ---- Archer tower
    clear_models()
    wood = M["wood"]
    wood_dark = M["wood_dark"]
    for x in (-0.6, 0.6):
        for y in (-0.5, 0.5):
            box((x, y, 1.05), (0.14, 0.14, 2.1), wood_dark, bevel=0.015)
    for y in (-0.5, 0.5):
        box((0, y, 1.0), (1.35, 0.07, 0.09), wood, rot=(0, math.radians(55), 0))
        box((0, y, 1.0), (1.35, 0.07, 0.09), wood, rot=(0, math.radians(-55), 0))
    box((0, 0, 2.15), (1.6, 1.4, 0.14), wood, bevel=0.02)
    for x in (-0.74, 0.74):
        for y in (-0.64, 0.64):
            box((x, y, 2.5), (0.08, 0.08, 0.6), wood_dark)
    box((0, -0.66, 2.7), (1.55, 0.06, 0.08), wood)
    box((0, 0.66, 2.7), (1.55, 0.06, 0.08), wood)
    c = build_archer()
    c.scale(0.62)
    c.root.location = (0, 0.1, 2.22)
    c.act("2H_Ranged_Aiming")
    c.at(0.3)
    render("tower", 0, (0, 0, 1.7), 320, 500)

    # ---- KayKit props
    def one(name, path, scale, h, res, rot=0.0, target=None):
        clear_models()
        prop(path, scale, rot_z=rot)
        render(name, 0, target or (0, 0, h / 2), res[0], res[1])

    one("table", DUN + "table_medium_decorated_A.gltf.glb", 0.55, 1.0, (220, 220))
    one("shelf", DUN + "shelf_small_candles.gltf.glb", 0.9, 0.6, (140, 140))
    one("barrel", DUN + "barrel_large.gltf.glb", 0.4, 0.8, (140, 160))
    one("crates", DUN + "crates_stacked.gltf.glb", 0.45, 1.0, (180, 200))
    one("keg", DUN + "keg.gltf.glb", 0.4, 0.8, (160, 170))
    one("candles", DUN + "candle_triple.gltf.glb", 0.6, 0.5, (80, 100))
    one("torch", DUN + "torch_mounted.gltf.glb", 1.0, 0.8, (120, 170), target=(0, -0.3, 0.2))
    one("skull", HAL + "skull.gltf", 0.35, 0.3, (80, 80))
    one("ribcage", HAL + "ribcage.gltf", 0.45, 0.4, (110, 110))
    one("bone", HAL + "bone_A.gltf", 0.6, 0.1, (80, 60))
    one("lantern", HAL + "lantern_standing.gltf", 0.8, 0.8, (90, 130))
    one("tree", HAL + "tree_dead_medium.gltf", 0.75, 3.0, (240, 420))
    one("grave", HAL + "grave_A.gltf", 0.45, 1.0, (140, 160))
    one("bench", HAL + "bench.gltf", 0.9, 0.5, (160, 260), rot=math.pi / 2)
    one("coin", DUN + "coin.gltf.glb", 0.8, 0.1, (60, 60))
    one("coins_s", DUN + "coin_stack_small.gltf.glb", 0.5, 0.3, (90, 90))
    one("coins_m", DUN + "coin_stack_medium.gltf.glb", 0.5, 0.4, (110, 110))
    one("coins_l", DUN + "coin_stack_large.gltf.glb", 0.5, 0.6, (120, 130))

    # Coin facing the camera, for the HUD
    clear_models()
    p = prop(DUN + "coin.gltf.glb", 1.0)
    p.rotation_euler = (math.pi / 2 - PITCH, 0, 0)
    render("coin_icon", 0, (0, 0, 0), 64, 64)

    # ---- Floors, walls, fences (KayKit pieces scaled to the 1 m grid of the game)
    for name, path, s in (("tile_hall", DUN + "floor_tile_large.gltf.glb", 0.25),
                          ("tile_hall", DUN + "floor_tile_large_rocks.gltf.glb", 0.25),
                          ("tile_room", DUN + "floor_wood_large.gltf.glb", 0.25),
                          ("tile_bath", DUN + "floor_tile_small.gltf.glb", 0.5)):
        clear_models()
        prop(path, s)
        n = len([x for x in SPRITES if x[0] == name])
        render(name, n, (0, 0, 0), 150, 150)
    clear_models()
    prop(HAL + "floor_dirt.gltf", 0.5)
    render("ground", 0, (0, 0, 0), 300, 300)
    clear_models()
    prop(HAL + "path_A.gltf", 1.0)
    render("path", 0, (0, 0, 0), 300, 300)

    clear_models()
    prop(DUN + "wall.gltf.glb", (0.25, 0.4, 0.26))
    render("wall_h", 0, (0, 0, 0.5), 180, 220)
    clear_models()
    prop(DUN + "wall.gltf.glb", (0.25, 0.4, 0.26), rot_z=math.pi / 2)
    render("wall_v", 0, (0, 0, 0.5), 180, 220)
    clear_models()
    prop(DUN + "wall_gated.gltf.glb", (0.4, 0.4, 0.26))
    render("door", 0, (0, 0, 0.5), 260, 220)
    clear_models()
    prop(HAL + "fence.gltf", (0.25, 0.5, 0.3))
    render("fence_h", 0, (0, 0, 0.35), 160, 160)
    clear_models()
    prop(HAL + "fence.gltf", (0.25, 0.5, 0.3), rot_z=math.pi / 2)
    render("fence_v", 0, (0, 0, 0.35), 160, 200)

    print("rendered %d sprites in %.0fs" % (len(SPRITES), time.time() - t0))


# ----------------------------------------------------------------------------- packing


def pack():
    items = []
    for name, frame, path, ax, ay in SPRITES:
        im = Image.open(path).convert("RGBA")
        bbox = im.getchannel("A").point(lambda a: 255 if a > 6 else 0).getbbox()
        if bbox is None:
            bbox = (0, 0, 1, 1)
        im = im.crop(bbox)
        items.append([name, frame, im, ax - bbox[0], ay - bbox[1]])
    page_w, page_h = 2048, 2048
    pages = []
    order = sorted(range(len(items)), key=lambda i: -items[i][2].height)
    placed = [None] * len(items)
    page = Image.new("RGBA", (page_w, page_h), (0, 0, 0, 0))
    x = y = shelf = 0
    pad = 2
    for i in order:
        im = items[i][2]
        if x + im.width + pad > page_w:
            x = 0
            y += shelf + pad
            shelf = 0
        if y + im.height + pad > page_h:
            pages.append(page)
            page = Image.new("RGBA", (page_w, page_h), (0, 0, 0, 0))
            x = y = shelf = 0
        page.paste(im, (x, y))
        placed[i] = (len(pages), x, y, im.width, im.height)
        x += im.width + pad
        shelf = max(shelf, im.height)
    pages.append(page)

    res_dir = os.path.join(ROOT, "composeApp/src/commonMain/composeResources/drawable")
    os.makedirs(res_dir, exist_ok=True)
    for f in os.listdir(res_dir):
        if f.startswith("atlas") and f.endswith(".png"):
            os.remove(os.path.join(res_dir, f))
    for i, p in enumerate(pages):
        bbox = p.getbbox() or (0, 0, 1, 1)
        h = min(page_h, ((bbox[3] + 63) // 64) * 64)
        p.crop((0, 0, page_w, h)).save(os.path.join(res_dir, "atlas%d.png" % i), optimize=True)

    names = []
    for it in items:
        if it[0] not in names:
            names.append(it[0])
    lines = [
        "package com.lastasylum.game.render",
        "",
        "// Generated by tools/sprites/render_sprites.py. Do not edit by hand.",
        "",
        "object SpriteData {",
        "    const val PPM = %df" % PPM,
        "    const val PAGES = %d" % len(pages),
        "",
        "    /** Per frame: page, x, y, w, h, anchorX, anchorY (anchor = ground point under the object). */",
        "    val frames = intArrayOf(",
    ]
    index = {}
    k = 0
    for name in names:
        group = sorted([i for i in range(len(items)) if items[i][0] == name], key=lambda i: items[i][1])
        index[name] = (k, len(group))
        for i in group:
            pg, px, py, w, h = placed[i]
            lines.append("        %d, %d, %d, %d, %d, %d, %d, // %s %d" % (
                pg, px, py, w, h, round(items[i][3]), round(items[i][4]), name, items[i][1]))
            k += 1
    lines.append("    )")
    lines.append("")
    for name in names:
        base, count = index[name]
        const = name.upper()
        lines.append("    const val %s = %d" % (const, base))
        if count > 1:
            lines.append("    const val %s_COUNT = %d" % (const, count))
    lines.append("}")
    out = os.path.join(ROOT, "composeApp/src/commonMain/kotlin/com/lastasylum/game/render/SpriteData.kt")
    with open(out, "w") as f:
        f.write("\n".join(lines) + "\n")
    ui = [
        "package com.lastasylum.game.ui",
        "",
        "// Generated by tools/sprites/render_sprites.py. Do not edit by hand.",
        "",
        "import androidx.compose.runtime.Composable",
        "import androidx.compose.ui.graphics.ImageBitmap",
        "import com.lastasylum.game.resources.Res",
        "import com.lastasylum.game.resources.*",
        "import org.jetbrains.compose.resources.imageResource",
        "",
        "@Composable",
        "fun atlasPages(): List<ImageBitmap> = listOf(",
    ] + ["    imageResource(Res.drawable.atlas%d)," % i for i in range(len(pages))] + [")"]
    out = os.path.join(ROOT, "composeApp/src/commonMain/kotlin/com/lastasylum/game/ui/AtlasPages.kt")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        f.write("\n".join(ui) + "\n")
    print("atlas pages:", len(pages))



all_sprites()
pack()
