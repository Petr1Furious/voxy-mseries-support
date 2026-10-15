package me.cortex.voxy.client.core.metal;

/** Backend selection is independent of GL compute capabilities. */
public final class MetalSupport {
    private MetalSupport() {}
    public static boolean selected() {
        String backend = System.getProperty("voxy.backend", "auto");
        return switch (backend) {
            case "metal" -> true;
            case "opengl" -> false;
            case "auto" -> System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("mac")
                    && System.getProperty("os.arch").equals("aarch64");
            default -> throw new IllegalArgumentException("Unknown voxy.backend: " + backend);
        };
    }
}
