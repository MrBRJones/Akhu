package com.MrBRos.akhu;

import android.content.Context;
import android.opengl.GLES30;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Compiles and links a GLES30 program and caches uniform locations. */
public class ShaderProgram {

    private final int program;
    private final Map<String, Integer> uniforms = new HashMap<>();

    public ShaderProgram(String vertexSrc, String fragmentSrc) {
        int vs = compile(GLES30.GL_VERTEX_SHADER, vertexSrc);
        int fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc);
        program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vs);
        GLES30.glAttachShader(program, fs);
        GLES30.glLinkProgram(program);
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        int[] status = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(program);
            GLES30.glDeleteProgram(program);
            throw new RuntimeException("Program link failed: " + log);
        }
    }

    private static int compile(int type, String src) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, src);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(shader);
            GLES30.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return shader;
    }

    public static String loadRaw(Context context, int resId) {
        try (InputStream in = context.getResources().openRawResource(resId)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader resource", e);
        }
    }

    public void use() {
        GLES30.glUseProgram(program);
    }

    /** Returns the uniform location (-1 if the compiler optimized it out). */
    public int uniform(String name) {
        Integer loc = uniforms.get(name);
        if (loc == null) {
            loc = GLES30.glGetUniformLocation(program, name);
            uniforms.put(name, loc);
        }
        return loc;
    }

    public void release() {
        GLES30.glDeleteProgram(program);
        uniforms.clear();
    }
}
