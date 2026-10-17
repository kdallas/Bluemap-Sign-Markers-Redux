package net.immortalmc.bluemap_sign_markers.watcher;


import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import net.immortalmc.bluemap_sign_markers.FeatureProvider;
import net.immortalmc.bluemap_sign_markers.helpers.DetailTemplate;
import net.immortalmc.bluemap_sign_markers.helpers.IconResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.immortalmc.bluemap_sign_markers.Constants.*;

/**
 * Listener that watches for sign creation and destruction events and manages
 * corresponding BlueMap markers based on sign contents.
 * Behavior summary:
 * - When a player writes a sign with a recognized icon tag on the first line (e.g. "[map]")
 * the listener will attempt to create a POI marker on BlueMap using the chosen icon and
 * the text from lines 1-3 as the marker label/details.
 * - When a sign is broken, the listener will remove the marker with the corresponding
 * coordinate-based id from the marker set for that world.
 */
public class SignWatcher implements Listener {

    /**
     * FeatureProvider used to access plugin-level facilities:
     * - BlueMap webroot path (for icons)
     * - plugin {@link java.util.logging.Logger}
     * - the central map of {@link org.bukkit.World} -> {@link de.bluecolored.bluemap.api.markers.MarkerSet}
     */
    private final FeatureProvider featureProvider;

    /**
     * Constructs a SignWatcher using the provided FeatureProvider to access plugin features.
     *
     * @param featureProvider provider exposing BlueMap webroot, logger and marker map
     */
    public SignWatcher(FeatureProvider featureProvider) {
        this.featureProvider = featureProvider;
    }

    /**
     * Handles sign change events (player writes a sign).
     * <p>
     * Expected workflow:
     * - Reads the first line and attempts to match an icon token (e.g. "[map]").
     * - If an icon is found and its image exists in BlueMap's webroot, constructs a POI marker
     * using the sign's position and lines 1-3 as label/detail.
     * - Stores the marker in the marker map under an id built from the block coordinates.
     * - Replaces the first line of the sign with a marker indicator and notifies the player.
     * If the icon cannot be resolved, the bundled fallback icon is used. If it exists but its size
     * cannot be determined, the marker is still created with a default anchor and a warning is
     * logged.
     *
     * @param event the SignChangeEvent triggered when a player finalizes sign text
     */
    @EventHandler
    public void onSignWrite(SignChangeEvent event) {
        // ### Mapping sign's line 0 to specific marker type (translates to icon)
        final String line0 = readLine(event, 0);
        if (line0 == null || line0.isBlank() || !line0.startsWith("[") || !line0.endsWith("]")) {
            return;
        }

        // Locale.ROOT: the default-locale toLowerCase() maps "I" to a dotless "i" under a Turkish
        // locale, so "[ICON]" would stop matching icon.png. IconResolver trims the token, rejects
        // anything that is not a plain file name, and never returns null: an icon that exists but
        // cannot be measured is used with a guessed anchor rather than being refused.
        final String token = line0.substring(1, line0.length() - 1).toLowerCase(Locale.ROOT);
        final IconResolver.IconRef iconRef =
                IconResolver.resolve(featureProvider.getWebRoot(), token, featureProvider.getLogger());

        // Webroot-relative and always forward-slashed, e.g. "markers/portal.svg": this string goes
        // into the marker JSON and is fetched by the browser, so it must not be a Path - on
        // Windows, Path.toString() would produce backslashes.
        final String icon = iconRef.path();
        final Vector2i anchor = iconRef.anchor();


        // ### Building label for the sign, from lines 1-3
        String label1 = readLine(event, 1);
        String label2 = readLine(event, 2);
        String label3 = readLine(event, 3);

        // Blank lines are dropped rather than concatenated, which would otherwise
        // leave stray separators: a sign with only line 2 filled labelled as
        // "text  " with two trailing spaces.
        String fullLabel = Stream.of(label1, label2, label3)
                .filter(part -> !part.isBlank())
                .collect(Collectors.joining(" "));

        // ### No description - no marker
        if (fullLabel.isBlank()) return;

        // ### Getting sign's block XYZ position. This will be marker's id
        Block block = event.getBlock();
        Vector3d pos = new Vector3d(block.getX(), block.getY(), block.getZ());

        String id = markerIdAt(block.getX(), block.getY(), block.getZ());
        MarkerContent markerContent = MarkerContent.builder()
                .label1(label1)
                .label2(label2)
                .label3(label3)
                .position(pos)
                .author(event.getPlayer().getName())
                .build();
        String markerDetails = buildDetail(markerContent, fullLabel, icon, block.getWorld().getName());
        POIMarker marker = POIMarker.builder().position(pos).label(fullLabel).icon(icon, anchor).maxDistance(100000).detail(markerDetails).build();
        featureProvider.getMarkerSet().get(block.getWorld()).put(id, marker);
        // Persist immediately: the shutdown save only covers a clean stop, so without
        // this an unclean shutdown would lose every marker created since the last one.
        featureProvider.saveMarkerSet(block.getWorld());

        // ### Replace first line, with prefix, e.g. [map], to <marker> indicator
        event.line(0, LegacyComponentSerializer.legacySection().deserialize(MARKER_PLACEHOLDER));
        event.getPlayer().sendMessage(formatMessage(String.format(ADDED_TEMPLATE, iconRef.name(), pos.getFloorX(), pos.getFloorY(), pos.getFloorZ())));
    }

    /**
     * Handles block break events to remove markers when signs are destroyed.
     * If the broken block is a sign and a marker with the corresponding coordinate-based id
     * exists in the marker set, the marker will be removed and the player will be notified.
     *
     * @param event the BlockBreakEvent representing the block destruction
     */
    @EventHandler
    public void onSignDestroy(BlockBreakEvent event) {

        Block block = event.getBlock();
        if (!(block.getState() instanceof Sign)) return;

        MarkerSet set = featureProvider.getMarkerSet().get(block.getWorld());
        if (set == null) return;

        Vector3d pos = new Vector3d(block.getX(), block.getY(), block.getZ());
        String id = markerIdAt(block.getX(), block.getY(), block.getZ());

        Marker marker = set.get(id);
        if (marker == null) return;
        set.remove(id);
        featureProvider.saveMarkerSet(block.getWorld());

        event.getPlayer().sendMessage(formatMessage(String.format(REMOVED_TEMPLATE, (int) pos.getX(), (int) pos.getY(), (int) pos.getZ())));
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

    /**
     * Formats a message using MiniMessage/Legacy templates for player feedback.
     *
     * @param message the message text to format
     * @return an Adventure {@link String} ready to send to a player
     */
    private String formatMessage(String message) {
        return LegacyComponentSerializer.legacySection()
                .serialize(MiniMessage.miniMessage().deserialize(String.format(MSG_PREFIX, message)));
    }

    /**
     * Reads a single line from the sign involved in a {@link SignChangeEvent}.
     * <p>
     * The event's line accessors are Adventure {@link Component}-based. The icon tag on line 0 and
     * the label lines are matched as legacy text, so the component is serialized back into the
     * legacy String form the rest of this class works with.
     *
     * @param event the sign change event
     * @param index the line index to read (0-3)
     * @return the line's text, or an empty string when the line is unset
     */
    private String readLine(SignChangeEvent event, int index) {
        Component line = event.line(index);
        return line == null ? "" : LegacyComponentSerializer.legacySection().serialize(line);
    }
}