#!/usr/bin/env python3
"""在软件 GLES 上执行实际 Echo shader，检查筛色像素与反馈尾迹（不代写一份颜色算法）。

Windows 使用 Android SDK emulator 自带 SwiftShader，无需安装依赖或连接设备：
python -I scripts/test-echo-color-gles.py --gles-dir <SDK>/emulator/lib64/gles_swiftshader
实时累积测试仅把 OES 输入适配为 2D 测试纹理；数学代码不替换。离线源码原样编译。
"""

import argparse
import ctypes as c
import os
from pathlib import Path
import re
import sys
import textwrap


def source_of(text, name):
    match = re.search(r'\b(?:val|const val)\s+' + name + r'\s*=\s*"""(.*?)"""', text, re.S)
    if not match:
        raise AssertionError(f"找不到 shader {name}")
    return textwrap.dedent(match[1]).strip()


class Gles:
    def __init__(self, directory):
        self.dll_directory = os.add_dll_directory(str(directory.resolve()))
        self.egl = c.CDLL(str(directory / "libEGL.dll"))
        self.gl = c.CDLL(str(directory / "libGLESv2.dll"))
        p, i, u, f = c.c_void_p, c.c_int, c.c_uint, c.c_float
        definitions = [
            ("eglGetDisplay", p, [p]), ("eglInitialize", u, [p, c.POINTER(i), c.POINTER(i)]),
            ("eglBindAPI", u, [u]), ("eglChooseConfig", u, [p, c.POINTER(i), c.POINTER(p), i, c.POINTER(i)]),
            ("eglCreatePbufferSurface", p, [p, p, c.POINTER(i)]),
            ("eglCreateContext", p, [p, p, p, c.POINTER(i)]),
            ("eglMakeCurrent", u, [p, p, p, p]), ("eglDestroyContext", u, [p, p]),
            ("eglDestroySurface", u, [p, p]), ("eglTerminate", u, [p]),
            ("glCreateShader", u, [u]), ("glShaderSource", None, [u, i, c.POINTER(c.c_char_p), p]),
            ("glCompileShader", None, [u]), ("glGetShaderiv", None, [u, u, c.POINTER(i)]),
            ("glGetShaderInfoLog", None, [u, i, p, p]), ("glCreateProgram", u, []),
            ("glAttachShader", None, [u, u]), ("glLinkProgram", None, [u]),
            ("glGetProgramiv", None, [u, u, c.POINTER(i)]), ("glGetProgramInfoLog", None, [u, i, p, p]),
            ("glUseProgram", None, [u]), ("glDeleteProgram", None, [u]), ("glDeleteShader", None, [u]),
            ("glGenTextures", None, [i, c.POINTER(u)]), ("glActiveTexture", None, [u]),
            ("glBindTexture", None, [u, u]), ("glTexParameteri", None, [u, u, i]),
            ("glTexImage2D", None, [u, i, i, i, i, i, u, u, p]),
            ("glDeleteTextures", None, [i, c.POINTER(u)]),
            ("glGenFramebuffers", None, [i, c.POINTER(u)]), ("glBindFramebuffer", None, [u, u]),
            ("glFramebufferTexture2D", None, [u, u, u, u, i]), ("glCheckFramebufferStatus", u, [u]),
            ("glDeleteFramebuffers", None, [i, c.POINTER(u)]),
            ("glGetUniformLocation", i, [u, c.c_char_p]), ("glUniform1i", None, [i, i]),
            ("glUniform1f", None, [i, f]), ("glUniform3f", None, [i, f, f, f]),
            ("glGetAttribLocation", i, [u, c.c_char_p]), ("glEnableVertexAttribArray", None, [u]),
            ("glVertexAttribPointer", None, [u, i, u, u, i, p]),
            ("glViewport", None, [i, i, i, i]), ("glDrawArrays", None, [u, i, i]),
            ("glReadPixels", None, [i, i, i, i, u, u, p]), ("glGetError", u, []),
            ("glGetString", c.c_char_p, [u]),
        ]
        for name, result, arguments in definitions:
            fn = getattr(self.egl if name.startswith("egl") else self.gl, name)
            fn.restype, fn.argtypes = result, arguments
            setattr(self, name, fn)
        self.display = self.eglGetDisplay(None)
        assert self.eglInitialize(self.display, None, None), "eglInitialize 失败"
        assert self.eglBindAPI(0x30A0)
        attrs = (i * 13)(0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
        config, count = p(), i()
        assert self.eglChooseConfig(self.display, attrs, c.byref(config), 1, c.byref(count)) and count.value
        self.surface = self.eglCreatePbufferSurface(self.display, config, (i * 5)(0x3057, 1, 0x3056, 1, 0x3038))
        self.context = self.eglCreateContext(self.display, config, None, (i * 3)(0x3098, 2, 0x3038))
        assert self.surface and self.context and self.eglMakeCurrent(self.display, self.surface, self.surface, self.context)
        print("Renderer:", self.glGetString(0x1F01).decode())
        self.vertices = (f * 8)(-1, -1, 1, -1, -1, 1, 1, 1)
        self.textures = (u * 4)()
        self.glGenTextures(4, self.textures)
        self.fbo = u()
        self.glGenFramebuffers(1, c.byref(self.fbo))
        self.texture(3, (0, 0, 0))
        self.glBindFramebuffer(0x8D40, self.fbo)
        self.glFramebufferTexture2D(0x8D40, 0x8CE0, 0x0DE1, self.textures[3], 0)
        assert self.glCheckFramebufferStatus(0x8D40) == 0x8CD5
        self.glViewport(0, 0, 1, 1)

    def compile(self, kind, source):
        shader = self.glCreateShader(kind)
        data = c.c_char_p(source.encode())
        self.glShaderSource(shader, 1, c.byref(data), None)
        self.glCompileShader(shader)
        ok = c.c_int()
        self.glGetShaderiv(shader, 0x8B81, c.byref(ok))
        if not ok.value:
            log = c.create_string_buffer(8192)
            self.glGetShaderInfoLog(shader, len(log), None, log)
            raise AssertionError(log.value.decode())
        return shader

    def program(self, fragment):
        vertex = "attribute vec2 aPosition; varying vec2 vUv; varying vec2 vUvEcho; void main() { vUv=vec2(0.5); vUvEcho=vec2(0.5); gl_Position=vec4(aPosition,0.0,1.0); }"
        vs, fs = self.compile(0x8B31, vertex), self.compile(0x8B30, fragment)
        program = self.glCreateProgram()
        self.glAttachShader(program, vs)
        self.glAttachShader(program, fs)
        self.glLinkProgram(program)
        self.glDeleteShader(vs)
        self.glDeleteShader(fs)
        ok = c.c_int()
        self.glGetProgramiv(program, 0x8B82, c.byref(ok))
        if not ok.value:
            log = c.create_string_buffer(8192)
            self.glGetProgramInfoLog(program, len(log), None, log)
            raise AssertionError(log.value.decode())
        return program

    def texture(self, unit, rgb):
        self.glActiveTexture(0x84C0 + unit)
        self.glBindTexture(0x0DE1, self.textures[unit])
        for name, value in [(0x2801, 0x2600), (0x2800, 0x2600), (0x2802, 0x812F), (0x2803, 0x812F)]:
            self.glTexParameteri(0x0DE1, name, value)
        pixel = (c.c_ubyte * 4)(*rgb, 255)
        self.glTexImage2D(0x0DE1, 0, 0x1908, 1, 1, 0, 0x1908, 0x1401, pixel)

    def render(self, program, frame, previous=(0, 0, 0), tint=(1, 0, 0), mask=1, tol=.3, mode=1, decay=.5, threshold=.9, tint_mode=0):
        self.glUseProgram(program)
        self.texture(0, frame)
        self.texture(1, previous)
        self.texture(2, (0, 0, 0))
        samplers = {"uSrc": 0, "uFrame": 0, "uBase": 0, "uEchoPrev": 1, "uEchoSrc": 1, "uGlowSrc": 2}
        for name, unit in samplers.items():
            self.glUniform1i(self.glGetUniformLocation(program, name.encode()), unit)
        uniforms = {"uEchoDecay": decay, "uEchoMode": mode, "uEchoThreshold": threshold,
                    "uEchoMask": mask, "uEchoTol": tol, "uEchoGain": 1, "uEchoTintMode": tint_mode}
        for name, value in uniforms.items():
            self.glUniform1f(self.glGetUniformLocation(program, name.encode()), value)
        self.glUniform3f(self.glGetUniformLocation(program, b"uEchoTint"), *tint)
        pos = self.glGetAttribLocation(program, b"aPosition")
        self.glEnableVertexAttribArray(pos)
        self.glVertexAttribPointer(pos, 2, 0x1406, 0, 0, self.vertices)
        self.glDrawArrays(0x0005, 0, 4)
        pixel = (c.c_ubyte * 4)()
        self.glReadPixels(0, 0, 1, 1, 0x1908, 0x1401, pixel)
        error = self.glGetError()
        assert error == 0, f"GL error: {error:#x}"
        return tuple(pixel[:3])

    def close(self):
        self.glDeleteFramebuffers(1, c.byref(self.fbo))
        self.glDeleteTextures(4, self.textures)
        self.eglMakeCurrent(self.display, None, None, None)
        self.eglDestroyContext(self.display, self.context)
        self.eglDestroySurface(self.display, self.surface)
        self.eglTerminate(self.display)


def run(gl, text):
    failures = []
    checks = 0

    def check(label, actual, expected):
        nonlocal checks
        checks += 1
        if any(abs(a - b) > 2 for a, b in zip(actual, expected)):
            failures.append(f"{label}: expected={expected}, actual={actual}")

    tails = {}
    colors = [("red", (255, 0, 0)), ("green", (0, 255, 0)), ("blue", (0, 0, 255))]
    for name in ["SCENE_ECHO_ACCUM_FS", "ECHO_ACCUM_FS"]:
        shader = source_of(text, name)
        # 实时原始 OES 版本也必须能链接；像素夹具另外适配输入采样器。
        original = gl.program(shader)
        gl.glDeleteProgram(original)
        # 只适配输入纹理类型，不修改任何筛色／累积算式。
        shader = re.sub(r"#extension GL_OES_EGL_image_external[^\n]*", "", shader)
        shader = shader.replace("samplerExternalOES", "sampler2D")
        program = gl.program(shader)
        try:
            for color_name, color in colors:
                tint = tuple(v / 255 for v in color)
                check(f"{name} ONLY {color_name}", gl.render(program, color, tint=tint), color)
                check(f"{name} ONLY rejects white for {color_name}", gl.render(program, (255, 255, 255), tint=tint), (0, 0, 0))
                check(f"{name} EXCEPT rejects {color_name}", gl.render(program, color, tint=tint, mask=2), (0, 0, 0))
                other = colors[(colors.index((color_name, color)) + 1) % 3][1]
                check(f"{name} EXCEPT preserves {color_name}", gl.render(program, color, tint=tuple(v / 255 for v in other), mask=2), color)
                check(f"{name} decayed {color_name} tail", gl.render(program, (0, 0, 0), previous=color, tint=tint), tuple(round(v / 2) for v in color))
            for tol in (.05, .3, .8):
                check(f"{name} tol={tol} rejects white for red", gl.render(program, (255, 255, 255), tol=tol), (0, 0, 0))
                check(f"{name} tol={tol} rejects gray for red", gl.render(program, (245, 245, 245), tol=tol), (0, 0, 0))
            for tol in (.05, .3, .8):
                check(f"{name} pale red rejects white tol={tol}", gl.render(program, (255, 255, 255), tint=(1, .8, .8), tol=tol), (0, 0, 0))
                check(f"{name} pale red rejects gray tol={tol}", gl.render(program, (245, 245, 245), tint=(1, .8, .8), tol=tol), (0, 0, 0))
                check(f"{name} white target rejects pale red tol={tol}", gl.render(program, (255, 204, 204), tint=(1, 1, 1), tol=tol), (0, 0, 0))
            check(f"{name} ONLY pale red keeps its source color", gl.render(program, (255, 204, 204), tint=(1, .8, .8)), (255, 204, 204))
            check(f"{name} EXCEPT pale red keeps white", gl.render(program, (255, 255, 255), tint=(1, .8, .8), mask=2), (255, 255, 255))
            check(f"{name} ONLY gray matches white target", gl.render(program, (245, 245, 245), tint=(1, 1, 1)), (245, 245, 245))
            check(f"{name} black input is safe", gl.render(program, (0, 0, 0), tint=(0, 0, 0)), (0, 0, 0))
            check(f"{name} same hue below full intensity", gl.render(program, (245, 0, 0)), (245, 0, 0))
            check(f"{name} dark tint matches bright red", gl.render(program, (255, 0, 0), tint=(.5, 0, 0)), (255, 0, 0))
            check(f"{name} narrow tolerance rejects orange", gl.render(program, (255, 64, 0), tol=.05), (0, 0, 0))
            check(f"{name} wide tolerance selects orange", gl.render(program, (255, 64, 0), tol=.3), (255, 64, 0))
            check(f"{name} neutral boundary below 5 percent", gl.render(program, (255, 243, 243), tint=(1, 1, 1)), (255, 243, 243))
            check(f"{name} neutral boundary above 5 percent", gl.render(program, (255, 242, 242), tint=(1, 1, 1)), (0, 0, 0))
            check(f"{name} colored target excludes neutral source", gl.render(program, (255, 255, 255), tint=(1, 242 / 255, 242 / 255)), (0, 0, 0))
            check(f"{name} black target rejects colored source safely", gl.render(program, (255, 0, 0), tint=(0, 0, 0)), (0, 0, 0))
            edge_tol = 64 / 255 - .05
            check(f"{name} tolerance soft midpoint", gl.render(program, (255, 64, 0), tol=edge_tol), (128, 32, 0))
            check(f"{name} EXCEPT soft midpoint", gl.render(program, (255, 64, 0), tol=edge_tol, mask=2), (128, 32, 0))
            check(f"{name} brightness upper endpoint", gl.render(program, (255, 0, 0), threshold=1), (128, 0, 0))
            check(f"{name} brightness lower endpoint", gl.render(program, (153, 0, 0), threshold=.6), (77, 0, 0))
            first = gl.render(program, (255, 0, 0))
            tails[name] = gl.render(program, (0, 0, 0), previous=first)
            check(f"{name} moving red leaves colored feedback", tails[name], (128, 0, 0))
            for color_name, color in [("yellow", (255, 255, 0)), ("cyan", (0, 255, 255)), ("magenta", (255, 0, 255))]:
                check(f"{name} ONLY {color_name}", gl.render(program, color, tint=tuple(v / 255 for v in color)), color)
            check(f"{name} ONLY white", gl.render(program, (255, 255, 255), tint=(1, 1, 1)), (255, 255, 255))
            check(f"{name} EXCEPT white preserves red", gl.render(program, (255, 0, 0), tint=(1, 1, 1), mask=2), (255, 0, 0))
            check(f"{name} dim background rejected", gl.render(program, (40, 0, 0)), (0, 0, 0))
            check(f"{name} KB unchanged", gl.render(program, (255, 0, 0), previous=(0, 255, 0), mode=0), (255, 128, 0))
            check(f"{name} OFF legacy red rejected", gl.render(program, (255, 0, 0), mask=0), (0, 0, 0))
            check(f"{name} OFF legacy white", gl.render(program, (255, 255, 255), mask=0), (255, 255, 255))
        finally:
            gl.glDeleteProgram(program)
    for name in ["SCENE_COMPOSITE_ECHO_FS", "ECHO_DECL"]:
        shader = source_of(text, name)
        if name == "ECHO_DECL":
            shader = "precision mediump float; uniform sampler2D uBase;\n" + shader + "\nvoid main() { gl_FragColor=vec4(applyEcho(texture2D(uBase,vUvEcho).rgb,vUvEcho),1.0); }"
        program = gl.program(shader)
        try:
            accumulation = "SCENE_ECHO_ACCUM_FS" if name.startswith("SCENE_") else "ECHO_ACCUM_FS"
            check(f"{name} generated red feedback stays red", gl.render(program, (0, 0, 0), previous=tails[accumulation]), (128, 0, 0))
            check(f"{name} ADD red", gl.render(program, (0, 0, 0), previous=(255, 255, 255), tint_mode=1), (255, 0, 0))
            check(f"{name} SUBTRACT red", gl.render(program, (0, 0, 0), previous=(255, 255, 255), tint_mode=2), (0, 255, 255))
        finally:
            gl.glDeleteProgram(program)
    for failure in failures:
        print("FAIL", failure)
    print(f"{checks} GLES pixel checks; {len(failures)} failures")
    return not failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gles-dir", type=Path, required=True)
    parser.add_argument("--shaders", type=Path, default=Path(__file__).resolve().parents[1] / "app/src/main/java/com/wotagei/cam/camera/Shaders.kt")
    args = parser.parse_args()
    gl = Gles(args.gles_dir)
    try:
        return 0 if run(gl, args.shaders.read_text(encoding="utf-8")) else 1
    finally:
        gl.close()


if __name__ == "__main__":
    sys.exit(main())
