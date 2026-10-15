package net.immortalmc.bluemap_sign_markers;

/**
 * Common constants used across the plugin.
 */
public class Constants {
    /** 
     * Relative path inside BlueMap webroot where marker images are stored.
     */
    public static final String IMAGE_PATH = "markers/";
    /**
     * Resource folder inside the plugin JAR where marker images are located.
     */
    public static final String RES_FOLDER = IMAGE_PATH + "icons";
    /**
     * Prefix used when creating per-world marker set filenames.
     */
    public final static String MARKER_SET_PREFIX = "marker-set-";

    /**
     * Prefix used when creating per-world marker id.
     */
    public final static String MARKER_ID_PREFIX = "marker-";

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
    /**
     * HTML template used as the marker detail content. The first three string placeholders
     * are used for the three label lines. Subsequent placeholders are used for X, Y, Z,
     * timestamp and author (in that order).
     */
    public final static String HTML_TEMPLATE = """
            <div style='
                padding: 10px;
                text-align: center;
                line-height: 1.4;
                font-family: sans-serif;
                min-width: 170px;
            '>
            <div style='border-style: dashed; border-width: 2px; border-color: #727272; padding: 5px; margin-bottom: 10px'>
                            <div style='color: #727272; font-size: 1.1em;'>%s</div>
                            <div style='color: #727272; font-size: 1.1em;'>%s</div>
                            <div style='color: #727272; font-size: 1.1em;'>%s</div>
                            </div>
                <div style='color: #727272; font-size: 0.7em;'>position (x y z)</div>
                <div style='color: #727272; font-size: 0.8em;'>%d %d %d</div>
                <div style='color: #727272; font-size: 0.7em;'>created %s</div>
                <div style='color: #727272; font-size: 0.8em;'>by %s</div>
            </div>
            """;
}
