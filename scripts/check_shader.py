#!/usr/bin/env python3
"""Compile the actual GLSL assets and test pixels on a headless EGL OpenGL context.

Requires Linux libEGL and libGL with a surfaceless driver (Mesa works).
This checks shader maths, not Minecraft's renderer integration.
"""
import ctypes as c
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / 'src/main/resources/assets/simpleschematics/shaders/core'
egl = c.CDLL('libEGL.so.1')
gl = c.CDLL('libGL.so.1')
I, U, F, P = c.c_int, c.c_uint, c.c_float, c.c_void_p


def bind(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.restype, fn.argtypes = result, args
    return fn


def ints(*args):
    return (I * len(args))(*args)


get_proc = bind(egl, 'eglGetProcAddress', P, c.c_char_p)
platform_display = c.CFUNCTYPE(P, U, P, c.POINTER(I))(get_proc(b'eglGetPlatformDisplayEXT'))
display = platform_display(0x31DD, None, None)  # EGL_PLATFORM_SURFACELESS_MESA
initialise = bind(egl, 'eglInitialize', U, P, c.POINTER(I), c.POINTER(I))
assert initialise(display, c.byref(I()), c.byref(I())), 'Cannot initialise EGL'
assert bind(egl, 'eglBindAPI', U, U)(0x30A2), 'OpenGL API unavailable'
config, count = P(), I()
choose = bind(egl, 'eglChooseConfig', U, P, c.POINTER(I), c.POINTER(P), I, c.POINTER(I))
assert choose(display, ints(0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8,
                            0x3022, 8, 0x3021, 8, 0x3025, 24, 0x3038),
              c.byref(config), 1, c.byref(count)) and count.value
surface = bind(egl, 'eglCreatePbufferSurface', P, P, P, c.POINTER(I))(
    display, config, ints(0x3057, 32, 0x3056, 32, 0x3038))
context = bind(egl, 'eglCreateContext', P, P, P, P, c.POINTER(I))(
    display, config, None, ints(0x3098, 3, 0x30FB, 2, 0x30FD, 1, 0x3038))
assert context and surface
assert bind(egl, 'eglMakeCurrent', U, P, P, P, P)(display, surface, surface, context)

create_shader = bind(gl, 'glCreateShader', U, U)
shader_source = bind(gl, 'glShaderSource', None, U, I, c.POINTER(c.c_char_p), c.POINTER(I))
compile_shader = bind(gl, 'glCompileShader', None, U)
shader_info = bind(gl, 'glGetShaderiv', None, U, U, c.POINTER(I))
shader_log = bind(gl, 'glGetShaderInfoLog', None, U, I, c.POINTER(I), P)
create_program = bind(gl, 'glCreateProgram', U)
attach_shader = bind(gl, 'glAttachShader', None, U, U)
link_program = bind(gl, 'glLinkProgram', None, U)
program_info = bind(gl, 'glGetProgramiv', None, U, U, c.POINTER(I))
program_log = bind(gl, 'glGetProgramInfoLog', None, U, I, c.POINTER(I), P)
attribute = bind(gl, 'glBindAttribLocation', None, U, U, c.c_char_p)
program = create_program()
for ext, kind in [('vsh', 0x8B31), ('fsh', 0x8B30)]:
    shader = create_shader(kind)
    source = c.c_char_p((SHADERS / ('hologram.' + ext)).read_bytes())
    shader_source(shader, 1, c.byref(source), None)
    compile_shader(shader)
    ok = I()
    shader_info(shader, 0x8B81, c.byref(ok))
    log = c.create_string_buffer(8192)
    shader_log(shader, len(log), None, log)
    assert ok.value, log.value.decode()
    attach_shader(program, shader)
metadata = json.loads((SHADERS / 'hologram.json').read_text())
for i, name in enumerate(metadata['attributes']):
    attribute(program, i, name.encode())
link_program(program)
ok = I()
program_info(program, 0x8B82, c.byref(ok))
log = c.create_string_buffer(8192)
program_log(program, len(log), None, log)
assert ok.value, log.value.decode()
bind(gl, 'glUseProgram', None, U)(program)
uniform_location = bind(gl, 'glGetUniformLocation', I, U, c.c_char_p)
uniform1 = bind(gl, 'glUniform1f', None, I, F)
uniform4 = bind(gl, 'glUniform4f', None, I, F, F, F, F)
matrix = bind(gl, 'glUniformMatrix4fv', None, I, I, c.c_ubyte, c.POINTER(F))


def loc(name):
    value = uniform_location(program, name.encode())
    assert value >= 0, 'Missing uniform ' + name
    return value


for uniform in metadata['uniforms']:
    loc(uniform['name'])
bind(gl, 'glUniform1i', None, I, I)(loc('Sampler0'), 0)

vao, vbo, texture = U(), U(), U()
bind(gl, 'glGenVertexArrays', None, I, c.POINTER(U))(1, c.byref(vao))
bind(gl, 'glBindVertexArray', None, U)(vao)
bind(gl, 'glGenBuffers', None, I, c.POINTER(U))(1, c.byref(vbo))
bind(gl, 'glBindBuffer', None, U, U)(0x8892, vbo)
# Position, normalised byte colour, UV, packed light, normal and padding.
# This deliberately matches Minecraft 1.20.1 DefaultVertexFormat.BLOCK.
import struct
vertices = b''.join(struct.pack('<3f4B2f2h4b', x, y, 0, 255, 255, 255, 255,
                                .5, .5, 240, 240, 0, 0, 127, 0)
                    for x, y in [(-1, -1), (1, -1), (1, 1), (-1, -1), (1, 1), (-1, 1)])
data = c.create_string_buffer(vertices)
bind(gl, 'glBufferData', None, U, c.c_ssize_t, P, U)(0x8892, len(vertices), data, 0x88E4)
enable_attribute = bind(gl, 'glEnableVertexAttribArray', None, U)
pointer = bind(gl, 'glVertexAttribPointer', None, U, I, U, c.c_ubyte, I, P)
for index, size, kind, normalised, offset in [(0, 3, 0x1406, 0, 0), (1, 4, 0x1401, 1, 12), (2, 2, 0x1406, 0, 16)]:
    enable_attribute(index)
    pointer(index, size, kind, normalised, 32, P(offset))
bind(gl, 'glGenTextures', None, I, c.POINTER(U))(1, c.byref(texture))
bind(gl, 'glBindTexture', None, U, U)(0x0DE1, texture)
parameter = bind(gl, 'glTexParameteri', None, U, U, I)
parameter(0x0DE1, 0x2801, 0x2600)
parameter(0x0DE1, 0x2800, 0x2600)
tex_image = bind(gl, 'glTexImage2D', None, U, I, I, I, I, I, U, U, P)
enable = bind(gl, 'glEnable', None, U)
disable = bind(gl, 'glDisable', None, U)
depth_mask = bind(gl, 'glDepthMask', None, c.c_ubyte)
clear_depth = bind(gl, 'glClearDepth', None, c.c_double)
clear = bind(gl, 'glClear', None, U)
read = bind(gl, 'glReadPixels', None, I, I, I, I, U, U, P)
draw = bind(gl, 'glDrawArrays', None, U, I, I)
bind(gl, 'glViewport', None, I, I, I, I)(0, 0, 32, 32)
enable(0x0BE2)  # GL_BLEND
enable(0x0B71)  # GL_DEPTH_TEST
disable(0x0B44)  # GL_CULL_FACE
bind(gl, 'glDepthFunc', None, U)(0x0203)
bind(gl, 'glBlendFunc', None, U, U)(0x0302, 0x0303)
bind(gl, 'glClearColor', None, F, F, F, F)(0, 0, 1, 1)


def translate(z):
    return (F * 16)(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, z, 1)


def sample(alpha=.35, distance=4, near=True, world=True, tex_alpha=255, depth=1, bias=False, ndc_depth=0):
    tex_image(0x0DE1, 0, 0x8058, 1, 1, 0, 0x1908, 0x1401,
              (c.c_ubyte * 4)(255, 0, 0, tex_alpha))
    uniform4(loc('ColorModulator'), 1, 1, 1, alpha)
    for name, value in [('WorldPass', float(world)), ('NearFade', float(near)),
                        ('FadeStart', .35), ('FadeEnd', 2), ('FogStart', 128), ('FogEnd', 192)]:
        uniform1(loc(name), value)
    matrix(loc('ModelViewMat'), 1, 0, translate(-distance))
    matrix(loc('ProjMat'), 1, 0, translate(distance + ndc_depth))
    depth_mask(1)
    clear_depth(depth)
    clear(0x4000 | 0x0100)
    depth_mask(0)
    (enable if bias else disable)(0x8037)
    bind(gl, 'glPolygonOffset', None, F, F)(-1, -2)
    draw(0x0004, 0, 6)
    colour, stored_depth = (c.c_ubyte * 4)(), F()
    read(16, 16, 1, 1, 0x1908, 0x1401, colour)
    read(16, 16, 1, 1, 0x1902, 0x1406, c.byref(stored_depth))
    return tuple(colour)[:3], stored_depth.value


checks = 0


def expect(name, red, **args):
    global checks
    pixel, depth = sample(**args)
    expected = (round(red * 255), 0, round((1 - red) * 255))
    assert all(abs(a - b) <= 2 for a, b in zip(pixel, expected)), (name, pixel, expected)
    assert abs(depth - args.get('depth', 1)) < 1e-6, (name, 'ghost changed world depth', depth)
    checks += 1


expect('zero opacity', 0, alpha=0)
expect('solid texture is transparent', .35)
expect('full opacity', 1, alpha=1)
expect('cutout survives low opacity', .05, alpha=.05)
expect('cutout hole stays clear', 0, tex_alpha=0)
expect('texture transparency is retained', .35 * 128 / 255, tex_alpha=128)
expect('close surface fades out', 0, distance=.1)
expect('near fade can be disabled', .35, distance=.1, near=False)
mid, _ = sample(distance=1.175)
assert 40 < mid[0] < 50, ('smooth near fade midpoint', mid)
checks += 1
expect('far fog midpoint', .175, distance=160)
expect('far fog hides ghost', 0, distance=192)
expect('gallery ignores world fog', 1, alpha=1, distance=200, world=False)
expect('terrain occludes ghost', 0, depth=.25)
expect('surface behind terrain is occluded without bias', 0, distance=0, near=False, depth=.5, ndc_depth=1.8e-7)
expect('small coplanar depth bias', .35, distance=0, near=False, depth=.5, ndc_depth=1.8e-7, bias=True)
assert bind(gl, 'glGetError', U)() == 0, 'OpenGL error'
renderer = bind(gl, 'glGetString', c.c_char_p, U)(0x1F01).decode()
print(f'PASS: GLSL 150 compile/link, shader metadata and {checks} pixel/depth checks ({renderer})')
bind(egl, 'eglMakeCurrent', U, P, P, P, P)(display, None, None, None)
bind(egl, 'eglDestroyContext', U, P, P)(display, context)
bind(egl, 'eglDestroySurface', U, P, P)(display, surface)
bind(egl, 'eglTerminate', U, P)(display)
