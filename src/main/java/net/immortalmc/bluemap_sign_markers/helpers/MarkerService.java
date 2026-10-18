package net.immortalmc.bluemap_sign_markers.helpers;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import net.immortalmc.bluemap_sign_markers.FeatureProvider;
import net.immortalmc.bluemap_sign_markers.watcher.MarkerContent;
import org.bukkit.World;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.immortalmc.bluemap_sign_markers.Constants.*;

/**
 * Creates or replaces the marker for a sign position.
 * <p>
 * The same marker must be produced whether a player writes a sign directly or applies a book to
 * it, so the icon resolution, label joining and popup rendering live here rather than in the
 * event listener.
 */
public final class MarkerService {

    private final FeatureProvider featureProvider;

    /**
     * @param featureProvider provider exposing the marker sets, webroot, data folder and logger
     */
    public MarkerService(FeatureProvider featureProvider) {
        this.featureProvider = Objects.requireNonNull(featureProvider, "featureProvider");
    }

    /**
     * A marker that was written successfully.
     *
     * @param id       the coordinate-derived marker id
     * @param marker   the marker as stored
     * @param icon     the resolved icon and anchor
     * @param replaced whether an existing marker at this position was overwritten
     */
    public record Result(String id, POIMarker marker, IconResolver.IconRef icon, boolean replaced) {
    }

    /**
     * Creates or replaces the marker for the block at the given coordinates.
     *
     * @param world    the sign's world
     * @param x        block x
     * @param y        block y
     * @param z        block z
     * @param iconToken the text between the brackets on the sign's first line
     * @param label1   second sign line
     * @param label2   third sign line
     * @param label3   fourth sign line
     * @param author   the player credited in the popup
     * @return the written marker, or {@code null} when there is no label or no marker set for the
     *         world
     */
    public Result createOrUpdate(World world, int x, int y, int z,
                                 String iconToken,
                                 String label1, String label2, String label3,
                                 String author) {
        Objects.requireNonNull(world, "world");
        String safeLabel1 = label1 == null ? "" : label1;
        String safeLabel2 = label2 == null ? "" : label2;
        String safeLabel3 = label3 == null ? "" : label3;
        String label = joinLabels(safeLabel1, safeLabel2, safeLabel3);
        if (label.isBlank()) return null;

        MarkerSet set = featureProvider.getMarkerSet().get(world);
        if (set == null) {
            // BlueMap may not have initialised yet, or this world has no map.
            featureProvider.getLogger().warning("No marker set for world " + world.getName()
                    + "; marker not created");
            return null;
        }

        String token = iconToken == null ? "" : iconToken.toLowerCase(Locale.ROOT);
        IconResolver.IconRef icon = IconResolver.resolve(
                featureProvider.getWebRoot(), token, featureProvider.getLogger());

        Vector3d pos = new Vector3d(x, y, z);
        MarkerContent content = MarkerContent.builder()
                .label1(safeLabel1)
                .label2(safeLabel2)
                .label3(safeLabel3)
                .position(pos)
                .author(author == null ? "" : author)
                .build();
        String detail = buildDetail(content, label, icon.path(), world.getName());

        String id = markerIdAt(x, y, z);
        POIMarker marker = POIMarker.builder()
                .position(pos)
                .label(label)
                .icon(icon.path(), icon.anchor())
                .maxDistance(100000)
                .detail(detail)
                .build();

        boolean replaced = set.get(id) != null;
        set.put(id, marker);
        // Persist immediately: the shutdown save only covers a clean stop.
        featureProvider.saveMarkerSet(world);
        return new Result(id, marker, icon, replaced);
    }

    /**
     * Removes the marker for the block at the given coordinates.
     *
     * @param world the sign's world
     * @param x     block x
     * @param y     block y
     * @param z     block z
     * @return the removed marker, or {@code null} when there is no marker set or no marker there
     */
    public Marker remove(World world, int x, int y, int z) {
        MarkerSet set = featureProvider.getMarkerSet().get(world);
        if (set == null) return null;

        String id = markerIdAt(x, y, z);
        Marker removed = set.get(id);
        if (removed == null) return null;

        set.remove(id);
        featureProvider.saveMarkerSet(world);
        return removed;
    }

    /**
     * Renders a marker's popup HTML from the user-editable template in the plugin's data folder.
     * <p>
     * The values that no marker field retains - the individual sign lines, author and creation
     * time - are appended as a comment so the template can be reapplied to this marker later
     * without losing them.
     *
     * @param content   the sign's label lines, position, timestamp and author
     * @param label     the joined label as shown by the marker itself
     * @param icon      the resolved icon path, e.g. {@code markers/portal.svg}
     * @param worldName the world the sign is in
     * @return the detail HTML, ready for {@code POIMarker.Builder#detail}
     */
    private String buildDetail(MarkerContent content, String label, String icon, String worldName) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("label", label);
        values.put("label1", content.getLabel1());
        values.put("label2", content.getLabel2());
        values.put("label3", content.getLabel3());
        values.put("x", String.valueOf(content.getX()));
        values.put("y", String.valueOf(content.getY()));
        values.put("z", String.valueOf(content.getZ()));
        values.put("position", content.getX() + " " + content.getY() + " " + content.getZ());
        values.put("timestamp", content.getTimestamp());
        values.put("author", content.getAuthor());
        values.put("icon", icon);
        values.put("world", worldName);

        Path dataFolder = featureProvider.getPlugin().getDataFolder().toPath();
        String template = DetailTemplate.readTemplate(dataFolder, featureProvider.getLogger());
        return DetailTemplate.render(template, values) + DetailTemplate.storedComment(values);
    }

    /** Joins the label lines the way a sign marker always has: blanks dropped, spaces between. */
    private static String joinLabels(String... lines) {
        return Stream.of(lines)
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" "));
    }
}
