package com.MrBRos.wallpapercreator;

import android.util.Log;
import android.view.SurfaceHolder;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * EGL10 instance-pattern helper ({@code (EGL10) EGLContext.getEGL()}) that
 * requests an OpenGL ES 3 context and renders into a wallpaper SurfaceHolder.
 * All methods must be called on the same render thread.
 */
public class EglHelper {

    private static final String TAG = "EglHelper";
    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x40;

    private final EGL10 egl = (EGL10) EGLContext.getEGL();
    private EGLDisplay display = EGL10.EGL_NO_DISPLAY;
    private EGLContext context = EGL10.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL10.EGL_NO_SURFACE;
    private EGLConfig config;

    public void start() {
        display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if (display == EGL10.EGL_NO_DISPLAY) {
            throw new RuntimeException("eglGetDisplay failed");
        }
        if (!egl.eglInitialize(display, new int[2])) {
            throw new RuntimeException("eglInitialize failed: " + egl.eglGetError());
        }
        int[] attribs = {
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 0,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL10.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!egl.eglChooseConfig(display, attribs, configs, 1, num) || num[0] == 0) {
            throw new RuntimeException("eglChooseConfig failed: " + egl.eglGetError());
        }
        config = configs[0];
        int[] ctxAttribs = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE};
        context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, ctxAttribs);
        if (context == null || context == EGL10.EGL_NO_CONTEXT) {
            throw new RuntimeException("eglCreateContext (ES3) failed: " + egl.eglGetError());
        }
    }

    public boolean hasSurface() {
        return surface != EGL10.EGL_NO_SURFACE;
    }

    public void createSurface(SurfaceHolder holder) {
        destroySurface();
        surface = egl.eglCreateWindowSurface(display, config, holder, null);
        if (surface == null || surface == EGL10.EGL_NO_SURFACE) {
            surface = EGL10.EGL_NO_SURFACE;
            throw new RuntimeException("eglCreateWindowSurface failed: " + egl.eglGetError());
        }
        if (!egl.eglMakeCurrent(display, surface, surface, context)) {
            throw new RuntimeException("eglMakeCurrent failed: " + egl.eglGetError());
        }
    }

    public boolean swap() {
        if (!hasSurface()) return false;
        if (!egl.eglSwapBuffers(display, surface)) {
            Log.w(TAG, "eglSwapBuffers failed: " + egl.eglGetError());
            return false;
        }
        return true;
    }

    /** Detaches the context from this thread so the surface/context can be handed off or destroyed. */
    public void releaseCurrent() {
        if (display != EGL10.EGL_NO_DISPLAY) {
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
        }
    }

    public void destroySurface() {
        if (hasSurface()) {
            releaseCurrent();
            egl.eglDestroySurface(display, surface);
            surface = EGL10.EGL_NO_SURFACE;
        }
    }

    public void finish() {
        destroySurface();
        if (context != EGL10.EGL_NO_CONTEXT) {
            egl.eglDestroyContext(display, context);
            context = EGL10.EGL_NO_CONTEXT;
        }
        if (display != EGL10.EGL_NO_DISPLAY) {
            egl.eglTerminate(display);
            display = EGL10.EGL_NO_DISPLAY;
        }
    }
}
