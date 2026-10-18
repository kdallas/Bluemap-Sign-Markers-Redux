package net.immortalmc.bluemap_sign_markers;

import com.flowpowered.math.vector.Vector3d;

/**
 * Common constants used across the plugin.
 */
public class Constants {
    /** 
     * Relative path inside BlueMap webroot where marker images are stored.
     */
    public static final String IMAGE_PATH = "markers/";
    /**
     * Resource folder inside the plugin JAR where marker images are located. It mirrors the
     * webroot's marker folder, so bundled icon sets keep their sub-directory names
     * ({@code markers/set1/...} is copied to {@code web/markers/set1/...}).
     */
    public static final String RES_FOLDER = "markers";
    /**
     * Prefix used when creating per-world marker set filenames.
     */
    public final static String MARKER_SET_PREFIX = "marker-set-";

    /**
     * Prefix used when creating per-world marker id.
     */
    public final static String MARKER_ID_PREFIX = "marker-";

    /**
     * Builds the marker id for a sign at the given block coordinates.
     * <p>
     * The components render as doubles - {@code marker--7924.0-112.0--181.0} - because they come
     * from a {@link Vector3d}. That formatting is load-bearing: marker creation, marker removal and
     * the sign-markers command all have to produce the same key, so it lives in one place rather
     * than being written out three times.
     *
     * @param x block x
     * @param y block y
     * @param z block z
     * @return the marker id for a sign at those coordinates
     */
    public static String markerIdAt(int x, int y, int z) {
        Vector3d pos = new Vector3d(x, y, z);
        return MARKER_ID_PREFIX + pos.getX() + "-" + pos.getY() + "-" + pos.getZ();
    }

    /**
     * Label prefix displayed in BlueMap for sign markers.
     */
    public final static String SIGN_MARKERS_PREFIX = "Sign Markers For ";
    /**
     * Suffix for the JSON files that store marker sets.
     */
    public final static String JSON_FILENAME = ".json";
    /**
     * Template message shown when a marker is removed. Format args: x, y, z (integers).
     */
    public final static String REMOVED_TEMPLATE = "Marker successfully removed at %d %d %d";

    /**
     * Template message shown when a marker is added. Format args: s (string), x, y, z (integers).
     */
    public final static String ADDED_TEMPLATE = "Marker <%s> successfully added at %d %d %d";

    /**
    * Placeholder text used in marker labels to indicate where the marker text goes.
     */
    public final static String MARKER_PLACEHOLDER = "> map marker <";

    /**
     * MiniMessage template used for player messages. Argument: marker text.
     */
    public final static String MSG_PREFIX = "<red>[SignMarkersRedux]<red> <yellow>%s</yellow>";
}
