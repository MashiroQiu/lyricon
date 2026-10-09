package io.github.proify.lyricon.statusbarlyric;

/** All inputs are pixels in the same screen coordinate system. */
public final class CameraWidth {
    private CameraWidth() {}
    public static int available(int cameraLeft, int lyricLeft, int gap) {
        long width = (long) cameraLeft - lyricLeft - Math.max(0, gap);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, width));
    }
}
