package com.example.gamebooster

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Pipeline: frame game (di-capture) -> 2 texture (prev, curr)
 * -> (opsional) motion estimation per blok 8x8 -> frame tengah di-render tiap vsync.
 */
class FrameGenRenderer(
    private val srcW: Int,
    private val srcH: Int,
    private val onSurface: (Surface) -> Unit,
    private val onStats: (Int, Int) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private class Target(val tex: Int, val fbo: Int)

    private val lock = Any()
    private var newFrame = false
    private var oesTex = 0
    private var st: SurfaceTexture? = null
    private val texMat = FloatArray(16)
    private val ident = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private lateinit var prev: Target
    private lateinit var curr: Target
    private lateinit var mvT: Target
    private var progCopy = 0
    private var progMv = 0
    private var progMix = 0

    private val mvW = (srcW + 7) / 8
    private val mvH = (srcH + 7) / 8
    private var viewW = 1
    private var viewH = 1

    private var frameCount = 0
    private var phaseIdx = 99
    private var mvValid = false
    private var lastArrival = 0L
    private var srcCount = 0
    private var outCount = 0
    private var lastStat = 0L

    companion object {
        const val R = 4
        const val STEP = 3.0f
        const val MAXV = R * STEP

        const val VS = """#version 300 es
uniform mat4 uTexMat;
out vec2 vUV;
void main(){
  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  gl_Position = vec4(p*2.0-1.0, 0.0, 1.0);
  vUV = (uTexMat * vec4(p, 0.0, 1.0)).xy;
}"""

        const val FS_COPY = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
in vec2 vUV; out vec4 o;
void main(){ o = texture(uTex, vUV); }"""

        const val FS_MV = """#version 300 es
precision highp float;
uniform sampler2D uPrev;
uniform sampler2D uCurr;
uniform vec2 uTexel;
in vec2 vUV; out vec4 o;
const int R = 4;
const float STEP = 3.0;
const float MAXV = 12.0;
float L(vec3 c){ return dot(c, vec3(0.299,0.587,0.114)); }
float sad(vec2 v){
  float s = 0.0;
  for (int j=0;j<3;j++) for (int i=0;i<3;i++){
    vec2 off = (vec2(float(i),float(j)) - 1.0) * 3.0 * uTexel;
    float a = L(texture(uPrev, vUV + off - v*0.5*uTexel).rgb);
    float b = L(texture(uCurr, vUV + off + v*0.5*uTexel).rgb);
    s += abs(a-b);
  }
  return s;
}
void main(){
  float zero = sad(vec2(0.0));
  float best = zero;
  vec2 bv = vec2(0.0);
  for (int y=-R;y<=R;y++) for (int x=-R;x<=R;x++){
    if (x==0 && y==0) continue;
    vec2 v = vec2(float(x),float(y))*STEP;
    float s = sad(v) + 0.004*length(v);
    if (s < best){ best = s; bv = v; }
  }
  if (best > zero*0.8) bv = vec2(0.0);
  o = vec4(bv/MAXV*0.5+0.5, 0.0, 1.0);
}"""

        const val FS_MIX = """#version 300 es
precision highp float;
uniform sampler2D uPrev;
uniform sampler2D uCurr;
uniform sampler2D uMv;
uniform float uPhase;
uniform int uMode;
uniform vec2 uTexel;
in vec2 vUV; out vec4 o;
const float MAXV = 12.0;
void main(){
  if (uMode == 2) { o = texture(uCurr, vUV); return; }
  vec2 mv = vec2(0.0);
  if (uMode == 1) mv = (texture(uMv, vUV).rg*2.0-1.0)*MAXV;
  vec4 a = texture(uPrev, vUV - mv*uPhase*uTexel);
  vec4 b = texture(uCurr, vUV + mv*(1.0-uPhase)*uTexel);
  o = mix(a, b, uPhase);
}"""
    }

    override fun onFrameAvailable(s: SurfaceTexture?) {
        synchronized(lock) { newFrame = true }
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("FG", GLES30.glGetShaderInfoLog(s))
        return s
    }

    private fun program(fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, VS))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("FG", GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun target(w: Int, h: Int): Target {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[0], 0)
        return Target(t[0], f[0])
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        progCopy = program(FS_COPY)
        progMv = program(FS_MV)
        progMix = program(FS_MIX)

        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        oesTex = t[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        st?.release()
        val s = SurfaceTexture(oesTex)
        s.setDefaultBufferSize(srcW, srcH)
        s.setOnFrameAvailableListener(this)
        st = s

        prev = target(srcW, srcH)
        curr = target(srcW, srcH)
        mvT = target(mvW, mvH)
        frameCount = 0
        mvValid = false

        onSurface(Surface(s))
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    private fun u(p: Int, n: String) = GLES30.glGetUniformLocation(p, n)

    private fun bind(p: Int, unit: Int, tex: Int, name: String, target: Int = GLES30.GL_TEXTURE_2D) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(target, tex)
        GLES30.glUniform1i(u(p, name), unit)
    }

    private fun drawMix(phase: Float, mode: Int) {
        GLES30.glUseProgram(progMix)
        GLES30.glUniformMatrix4fv(u(progMix, "uTexMat"), 1, false, ident, 0)
        bind(progMix, 0, prev.tex, "uPrev")
        bind(progMix, 1, curr.tex, "uCurr")
        bind(progMix, 2, mvT.tex, "uMv")
        GLES30.glUniform1f(u(progMix, "uPhase"), phase)
        GLES30.glUniform1i(u(progMix, "uMode"), mode)
        GLES30.glUniform2f(u(progMix, "uTexel"), 1f / srcW, 1f / srcH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.uptimeMillis()
        var gotNew = false
        synchronized(lock) { if (newFrame) { newFrame = false; gotNew = true } }
        val mult = FgConfig.mult
        val mode = FgConfig.mode

        if (gotNew) {
            val s = st!!
            s.updateTexImage()
            s.getTransformMatrix(texMat)
            if (now - lastArrival > 1000) frameCount = 0
            lastArrival = now

            val t = prev; prev = curr; curr = t
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, curr.fbo)
            GLES30.glViewport(0, 0, srcW, srcH)
            GLES30.glUseProgram(progCopy)
            GLES30.glUniformMatrix4fv(u(progCopy, "uTexMat"), 1, false, texMat, 0)
            bind(progCopy, 0, oesTex, "uTex", GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            frameCount++
            srcCount++

            mvValid = false
            if (frameCount >= 2 && mode == 1) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mvT.fbo)
                GLES30.glViewport(0, 0, mvW, mvH)
                GLES30.glUseProgram(progMv)
                GLES30.glUniformMatrix4fv(u(progMv, "uTexMat"), 1, false, ident, 0)
                bind(progMv, 0, prev.tex, "uPrev")
                bind(progMv, 1, curr.tex, "uCurr")
                GLES30.glUniform2f(u(progMv, "uTexel"), 1f / srcW, 1f / srcH)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                mvValid = true
            }
            phaseIdx = 1
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)

        val stale = now - lastArrival > 1500
        if (frameCount == 0 || stale) {
            // belum ada frame / game berhenti update: transparan supaya game asli kelihatan
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        } else if (frameCount >= 2 && phaseIdx < mult) {
            val eff = if (mode == 1 && !mvValid) 0 else mode
            drawMix(phaseIdx / mult.toFloat(), eff)
            phaseIdx++
        } else {
            drawMix(1f, 2)
        }
        outCount++

        if (now - lastStat >= 1000) {
            onStats(srcCount, outCount)
            srcCount = 0
            outCount = 0
            lastStat = now
        }
    }
}
