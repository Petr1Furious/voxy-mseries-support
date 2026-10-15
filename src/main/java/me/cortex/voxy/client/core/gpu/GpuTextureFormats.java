package me.cortex.voxy.client.core.gpu;

/** Extra texture-format tokens needed by shared render targets. */
public final class GpuTextureFormats {
    private GpuTextureFormats() {}
    /** BGRA8 (GL_BGRA8_EXT). CGL binds IOSurface storage rather than allocating a GL texture with this token. */
    public static final int BGRA8 = 0x93a1;
}
